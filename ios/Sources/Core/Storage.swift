import Foundation
import Security
import Darwin

final class SharedFiles: @unchecked Sendable {
    let root: URL
    static let group = "group.org.rescoot.sunshine-navigator"
    init(root: URL? = nil) throws {
        guard let directory = root ?? FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.group) else { throw NavigatorError.storage }
        self.root = directory.appendingPathComponent("Navigator", isDirectory: true)
        try FileManager.default.createDirectory(at: self.root, withIntermediateDirectories: true)
        var location = self.root
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try location.setResourceValues(values)
    }
    func locked<T: Sendable>(_ name: String, _ operation: @Sendable () async throws -> T) async throws -> T {
        let path = root.appendingPathComponent(name + ".lock").path
        let descriptor = try await Task.detached { () throws -> Int32 in
            let fd = open(path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
            guard fd >= 0 else { throw NavigatorError.storage }
            guard flock(fd, LOCK_EX) == 0 else { close(fd); throw NavigatorError.storage }
            return fd
        }.value
        defer { flock(descriptor, LOCK_UN); close(descriptor) }
        try Task.checkCancellation()
        return try await operation()
    }
    func read<T: Decodable>(_ name: String, as: T.Type) throws -> T? {
        let path = root.appendingPathComponent(name)
        guard FileManager.default.fileExists(atPath: path.path) else { return nil }
        return try JSONDecoder().decode(T.self, from: Data(contentsOf: path))
    }
    func write<T: Encodable>(_ name: String, _ value: T) throws {
        let data = try JSONEncoder().encode(value)
        try data.write(to: root.appendingPathComponent(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

final class KeychainStore: @unchecked Sendable {
    private var query: [String: Any] {
        var value: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "SunshineNavigator", kSecAttrAccount as String: "oauth"]
        if let group = Bundle.main.object(forInfoDictionaryKey: "NavigatorKeychainGroup") as? String { value[kSecAttrAccessGroup as String] = group }
        return value
    }
    func load() throws -> Session? {
        var query = query; query[kSecReturnData as String] = true; query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw NavigatorError.storage }
        return try JSONDecoder().decode(Session.self, from: data)
    }
    func save(_ session: Session) throws {
        let fields: [String: Any] = [kSecValueData as String: try JSONEncoder().encode(session), kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, fields as CFDictionary)
        if status == errSecItemNotFound {
            var item = query; fields.forEach { item[$0.key] = $0.value }
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw NavigatorError.storage }
        } else if status != errSecSuccess { throw NavigatorError.storage }
    }
    func clear() throws { let status = SecItemDelete(query as CFDictionary); guard status == errSecSuccess || status == errSecItemNotFound else { throw NavigatorError.storage } }
}

final class CommandLedger: @unchecked Sendable {
    let files: SharedFiles
    init(files: SharedFiles) { self.files = files }
    func pending(_ identity: String) async throws -> PendingCommand? {
        try await files.locked("commands") { try self.all()[identity] }
    }
    func reserve(_ command: PendingCommand) async throws {
        try await files.locked("commands") {
            var commands = try self.all()
            guard commands[command.identity] == nil else { throw NavigatorError.pending }
            commands[command.identity] = command
            try self.files.write("commands.json", commands)
        }
    }
    func clear(_ command: PendingCommand) async throws {
        try await files.locked("commands") {
            var commands = try self.all()
            guard commands[command.identity]?.key == command.key else { return }
            commands.removeValue(forKey: command.identity)
            try self.files.write("commands.json", commands)
        }
    }
    private func all() throws -> [String: PendingCommand] { try files.read("commands.json", as: [String: PendingCommand].self) ?? [:] }
}
