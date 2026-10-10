import Foundation
import CryptoKit
import Security

struct AuthorizationRequest: Sendable {
    static let callback = "dev.ligustah.lsnav:/oauth2redirect"
    static let defaultClient = "g_YyrEsELn5DvWju1pc6zx-pLVGRTFNXsTMueslc7As"
    let server: URL
    let clientID: String
    let verifier: String
    let state: String
    let url: URL
    init(server: URL, clientID: String) throws {
        self.server = server; self.clientID = clientID
        verifier = try Self.random(); state = try Self.random()
        var url = URLComponents(url: server.appendingPathComponent("oauth/authorize"), resolvingAgainstBaseURL: false)!
        url.queryItems = ["client_id": clientID, "redirect_uri": Self.callback, "response_type": "code", "scope": "read write scooter_control", "state": state, "code_challenge_method": "S256", "code_challenge": Data(SHA256.hash(data: Data(verifier.utf8))).base64URL].map { URLQueryItem(name: $0.key, value: $0.value) }
        self.url = url.url!
    }
    func code(from callback: URL) throws -> String {
        guard let parts = URLComponents(url: callback, resolvingAgainstBaseURL: false), parts.scheme == "dev.ligustah.lsnav", parts.host == nil, parts.path == "/oauth2redirect" else { throw NavigatorError.signedOut }
        let items = parts.queryItems ?? []
        guard items.filter({ $0.name == "state" }).count == 1, items.first(where: { $0.name == "state" })?.value == state,
              items.filter({ $0.name == "code" }).count == 1, items.first(where: { $0.name == "error" }) == nil,
              let code = items.first(where: { $0.name == "code" })?.value, !code.isEmpty else { throw NavigatorError.signedOut }
        return code
    }
    private static func random() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw NavigatorError.storage }
        return Data(bytes).base64URL
    }
}
private extension Data { var base64URL: String { base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") } }

actor OAuth: Credentials {
    private let files: SharedFiles
    private let keychain = KeychainStore()
    private let network: any HTTPTransport
    init(files: SharedFiles, network: any HTTPTransport = Network()) { self.files = files; self.network = network }
    func current() async throws -> Session? {
        try await files.locked("oauth") { try self.keychain.load() }
    }
    func complete(_ authorization: AuthorizationRequest, callback: URL) async throws -> Session {
        let code = try authorization.code(from: callback)
        let keychain = self.keychain, network = self.network
        return try await files.locked("oauth") {
            let token = try await Self.token(server: authorization.server, fields: ["grant_type": "authorization_code", "code": code, "redirect_uri": AuthorizationRequest.callback, "client_id": authorization.clientID, "code_verifier": authorization.verifier], network: network)
            var request = URLRequest(url: authorization.server.appendingPathComponent("api/v1/account"))
            request.setValue("Bearer " + token.accessToken, forHTTPHeaderField: "Authorization")
            let (data, response) = try await network.execute(request)
            guard response.statusCode == 200 else { throw NavigatorError.http(response.statusCode) }
            let account = try JSONDecoder.sunshine.decode(Account.self, from: data)
            let session = Session(id: UUID(), server: authorization.server, clientID: authorization.clientID, account: account, accessToken: token.accessToken, refreshToken: token.refreshToken ?? "", expiresAt: Date().addingTimeInterval(token.expiresIn - 30), scope: token.scope ?? "")
            guard !session.refreshToken.isEmpty, session.scope.split(separator: " ").contains("read") else { throw NavigatorError.signedOut }
            try keychain.save(session)
            return session
        }
    }
    func authorized(_ session: Session, refresh: Bool) async throws -> Session {
        let keychain = self.keychain, network = self.network
        return try await files.locked("oauth") {
            guard var stored = try keychain.load(), stored.id == session.id else { throw NavigatorError.signedOut }
            if stored.expiresAt > Date() && (!refresh || stored.accessToken != session.accessToken) { return stored }
            guard !stored.refreshToken.isEmpty else { throw NavigatorError.signedOut }
            let token = try await Self.token(server: stored.server, fields: ["grant_type": "refresh_token", "refresh_token": stored.refreshToken, "client_id": stored.clientID], network: network)
            stored.accessToken = token.accessToken
            stored.refreshToken = token.refreshToken ?? stored.refreshToken
            stored.expiresAt = Date().addingTimeInterval(token.expiresIn - 30)
            stored.scope = token.scope ?? stored.scope
            try keychain.save(stored)
            return stored
        }
    }
    func signOut(_ session: Session) async throws -> Bool {
        let keychain = self.keychain, network = self.network
        return try await files.locked("oauth") {
            guard let stored = try keychain.load(), stored.id == session.id else { throw NavigatorError.signedOut }
            var request = URLRequest(url: stored.server.appendingPathComponent("oauth/revoke"))
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            request.httpBody = form(["client_id": stored.clientID, "token": stored.refreshToken, "token_type_hint": "refresh_token"])
            let result = try? await network.execute(request)
            try keychain.clear()
            return result.map { (200..<300).contains($0.1.statusCode) } ?? false
        }
    }
    private struct Token: Decodable {
        let accessToken: String
        let refreshToken: String?
        let expiresIn: Double
        let scope: String?
    }
    private static func token(server: URL, fields: [String: String], network: any HTTPTransport) async throws -> Token {
        var request = URLRequest(url: server.appendingPathComponent("oauth/token"))
        request.httpMethod = "POST"; request.httpBody = form(fields)
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let (data, response) = try await network.execute(request)
        guard response.statusCode == 200 else { throw NavigatorError.signedOut }
        let token = try JSONDecoder.sunshine.decode(Token.self, from: data)
        guard !token.accessToken.isEmpty, token.expiresIn > 0 else { throw NavigatorError.signedOut }
        return token
    }
}
