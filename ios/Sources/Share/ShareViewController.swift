import UIKit
import SwiftUI
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    private let model = NavigatorModel()
    override func viewDidLoad() {
        super.viewDidLoad()
        let root = NavigatorView(model: model, close: { [weak self] in self?.extensionContext?.completeRequest(returningItems: nil) })
        let host = UIHostingController(rootView: root)
        addChild(host); view.addSubview(host.view); host.didMove(toParent: self)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor), host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor), host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        Task {
            await model.activate()
            do {
                guard let text = try await sharedText() else { throw NavigatorError.invalidInput }
                await model.importText(text)
            } catch { model.error = error.localizedDescription }
        }
    }
    private func sharedText() async throws -> String? {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }.prefix(8)
        for type in [UTType.url.identifier, UTType.plainText.identifier] {
            for provider in providers where provider.hasItemConformingToTypeIdentifier(type) {
                let item: NSSecureCoding? = try await withCheckedThrowingContinuation { continuation in
                    provider.loadItem(forTypeIdentifier: type, options: nil) { value, error in
                        if let error { continuation.resume(throwing: error) } else { continuation.resume(returning: value) }
                    }
                }
                if let url = item as? URL { return url.absoluteString }
                if let text = item as? String, !text.isEmpty { return text }
            }
        }
        return nil
    }
}
