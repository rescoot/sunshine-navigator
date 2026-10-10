import Foundation

protocol HTTPTransport: Sendable {
    func execute(_ request: URLRequest) async throws -> (Data, HTTPURLResponse)
}
final class Network: NSObject, HTTPTransport, URLSessionTaskDelegate, @unchecked Sendable {
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 45
        config.httpShouldSetCookies = false
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()
    override init() { super.init(); _ = session }
    func execute(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, data.count <= 4_194_304 else { throw NavigatorError.invalidInput }
        return (data, http)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

func form(_ fields: [String: String]) -> Data {
    let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
    return Data(fields.sorted(by: { $0.key < $1.key }).map { "\($0.key.addingPercentEncoding(withAllowedCharacters: allowed)!)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed)!)" }.joined(separator: "&").utf8)
}

protocol Credentials: Sendable {
    func current() async throws -> Session?
    func authorized(_ session: Session, refresh: Bool) async throws -> Session
}

actor SunshineAPI {
    let credentials: any Credentials
    let transport: any HTTPTransport
    let ledger: CommandLedger
    init(credentials: any Credentials, ledger: CommandLedger, transport: any HTTPTransport = Network()) {
        self.credentials = credentials; self.ledger = ledger; self.transport = transport
    }
    func current() async throws -> Session? { try await credentials.current() }
    func pending(_ session: Session) async throws -> PendingCommand? { try await ledger.pending(session.identity) }
    func request<T: Decodable & Sendable>(_ type: T.Type, session: Session, path: String, method: String = "GET", body: Data? = nil) async throws -> T {
        var record = try await credentials.authorized(session, refresh: false)
        var (data, response) = try await transport.execute(makeRequest(record, path: path, method: method, body: body))
        if response.statusCode == 401 && method == "GET" {
            record = try await credentials.authorized(session, refresh: true)
            (data, response) = try await transport.execute(makeRequest(record, path: path, method: method, body: body))
        }
        guard (200..<300).contains(response.statusCode) else { throw NavigatorError.http(response.statusCode) }
        guard try await credentials.current()?.id == session.id else { throw NavigatorError.signedOut }
        return try JSONDecoder.sunshine.decode(T.self, from: data)
    }
    func deleteFavorite(_ id: Int64, session: Session) async throws {
        let record = try await credentials.authorized(session, refresh: false)
        let (_, response) = try await transport.execute(makeRequest(record, path: "saved_destinations/\(id)", method: "DELETE"))
        guard (200..<300).contains(response.statusCode) else { throw NavigatorError.http(response.statusCode) }
    }
    func send(_ command: PendingCommand, session: Session) async throws {
        guard command.identity == session.identity, command.place?.coordinate.isValid != false else { throw NavigatorError.invalidInput }
        let record = try await credentials.authorized(session, refresh: false)
        let body = try command.place.map { try JSONSerialization.data(withJSONObject: ["latitude": $0.coordinate.latitude, "longitude": $0.coordinate.longitude, "address": $0.label]) }
        var request = makeRequest(record, path: "scooters/\(command.scooterID)/destination", method: body == nil ? "DELETE" : "PUT", body: body)
        request.setValue(command.key.uuidString.lowercased(), forHTTPHeaderField: "Idempotency-Key")
        // Both processes reserve before transport; receipt checks never enter this path.
        try await ledger.reserve(command)
        do {
            let (data, response) = try await transport.execute(request)
            if [400, 401, 403, 404, 422].contains(response.statusCode) {
                try await ledger.clear(command)
                throw NavigatorError.http(response.statusCode)
            }
            guard (200..<300).contains(response.statusCode), let receipt = try? JSONDecoder().decode(Receipt.self, from: data), ["success", "accepted"].contains(receipt.status) else { throw NavigatorError.uncertain }
            try await ledger.clear(command)
        } catch let error as NavigatorError { throw error }
        catch { throw NavigatorError.uncertain }
    }
    func receipt(_ command: PendingCommand, session: Session) async throws -> String {
        guard command.identity == session.identity else { throw NavigatorError.signedOut }
        let receipt = try await request(Receipt.self, session: session, path: "scooters/\(command.scooterID)/navigation_receipts/\(command.key.uuidString.lowercased())")
        if ["accepted", "rejected"].contains(receipt.status) { try await ledger.clear(command) }
        return receipt.status
    }
    func acknowledge(_ command: PendingCommand, session: Session) async throws {
        guard command.identity == session.identity, try await credentials.current()?.id == session.id else { throw NavigatorError.signedOut }
        try await ledger.clear(command)
    }
    private func makeRequest(_ session: Session, path: String, method: String, body: Data? = nil) -> URLRequest {
        var request = URLRequest(url: session.server.appendingPathComponent("api/v1/" + path))
        request.httpMethod = method; request.httpBody = body
        request.setValue("Bearer " + session.accessToken, forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        return request
    }
}
