import SwiftUI
import AuthenticationServices

@main struct NavigatorApp: App {
    @StateObject private var model = NavigatorModel()
    @StateObject private var browser = SignInBrowser()
    var body: some Scene {
        WindowGroup {
            NavigatorView(model: model, signIn: {
                guard !model.busy else { return }
                model.busy = true; model.error = nil
                Task {
                    defer { model.busy = false }
                    do {
                        guard let oauth = model.oauth else { throw NavigatorError.storage }
                        let request = try AuthorizationRequest(server: serverURL(model.server), clientID: model.clientID)
                        let callback = try await browser.open(request.url)
                        let session = try await oauth.complete(request, callback: callback)
                        await model.signedIn(session)
                    } catch { model.error = error.localizedDescription }
                }
            })
            .onOpenURL { url in
                if url.scheme != "dev.ligustah.lsnav" { Task { await model.importText(url.absoluteString) } }
            }
        }
    }
}

@MainActor final class SignInBrowser: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    private var authentication: ASWebAuthenticationSession?
    func open(_ url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "dev.ligustah.lsnav") { callback, error in
                if let callback { continuation.resume(returning: callback) }
                else { continuation.resume(throwing: error ?? NavigatorError.signedOut) }
            }
            session.presentationContextProvider = self
            authentication = session
            if !session.start() { authentication = nil; continuation.resume(throwing: NavigatorError.signedOut) }
        }
    }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
}
