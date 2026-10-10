import Foundation

struct Coordinate: Codable, Equatable, Sendable {
    let latitude: Double
    let longitude: Double
    var isValid: Bool { latitude.isFinite && longitude.isFinite && (-90...90).contains(latitude) && (-180...180).contains(longitude) }
}

struct Place: Codable, Equatable, Identifiable, Sendable {
    let coordinate: Coordinate
    var label: String
    var id: String { "\(coordinate.latitude),\(coordinate.longitude):\(label)" }
}

struct Account: Codable, Equatable, Sendable {
    let id: Int64
    var name: String?
    let email: String
    let avatarUrl: String?
    var displayName: String { name.flatMap { $0.isEmpty ? nil : $0 } ?? email }
}

struct Scooter: Decodable, Identifiable, Sendable {
    struct Images: Decodable, Sendable { let front: String?; let left: String?; let right: String? }
    struct Capabilities: Decodable, Sendable { let navigate: Bool? }
    let id: Int64
    let name: String
    let online: Bool?
    let state: String?
    let images: Images?
    let capabilities: Capabilities?
    let licensePlate: String?
    let licensePlateType: String?
    let licensePlateSetAt: String?
    var available: Bool { online == true && capabilities?.navigate != false }
    var artwork: URL? { [images?.right, images?.left, images?.front].compactMap { $0.flatMap(httpsURL) }.first }
    var stateLabel: String { guard let state, !state.isEmpty, state != "unknown" else { return "State unknown" }; return state.replacingOccurrences(of: "-", with: " ").capitalized }
}

struct SavedPlace: Decodable, Identifiable, Sendable {
    let id: Int64
    let latitude: Double
    let longitude: Double
    let label: String
    let isFavorite: Bool?
    var place: Place { Place(coordinate: Coordinate(latitude: latitude, longitude: longitude), label: label.isEmpty ? "Pinned location" : label) }
}
struct PersonalPlaces: Decodable, Sendable {
    var favorites: [SavedPlace]
    var recent: [SavedPlace]
    static let empty = PersonalPlaces(favorites: [], recent: [])
}
struct ScooterPlaces: Decodable, Sendable {
    struct Location: Decodable, Sendable {
        let latitude: Double; let longitude: Double; let label: String
        var place: Place { Place(coordinate: Coordinate(latitude: latitude, longitude: longitude), label: label) }
    }
    let locations: [Location]
}
struct Destination: Decodable, Sendable {
    let latitude: Double?; let longitude: Double?; let address: String?
    var place: Place? {
        guard let latitude, let longitude else { return nil }
        let point = Coordinate(latitude: latitude, longitude: longitude)
        return point.isValid ? Place(coordinate: point, label: address ?? "Pinned location") : nil
    }
}

struct Session: Codable, Equatable, Sendable {
    let id: UUID
    let server: URL
    let clientID: String
    let account: Account
    var accessToken: String
    var refreshToken: String
    var expiresAt: Date
    var scope: String
    var identity: String { "\(server.absoluteString)|\(clientID)|\(account.id)" }
}
struct PendingCommand: Codable, Equatable, Sendable {
    let key: UUID
    let identity: String
    let scooterID: Int64
    let scooterName: String
    let place: Place?
    let createdAt: Date
}
struct Receipt: Decodable, Sendable { let status: String }

enum NavigatorError: Error, LocalizedError, Equatable {
    case invalidInput, signedOut, pending, http(Int), storage, uncertain
    var errorDescription: String? {
        switch self {
        case .invalidInput: return "Enter a valid place, coordinates or supported map link."
        case .signedOut: return "Sign in to Sunshine again."
        case .pending: return "Check the pending request before sending another command."
        case .http(let code): return code == 401 ? "Sign in to Sunshine again." : "Sunshine returned HTTP \(code)."
        case .storage: return "Secure storage is unavailable."
        case .uncertain: return "Delivery is uncertain. Check the request status; it will not be sent again automatically."
        }
    }
}

func httpsURL(_ value: String) -> URL? {
    guard let url = URL(string: value), url.scheme?.lowercased() == "https", url.host != nil, url.user == nil, url.password == nil else { return nil }
    return url
}
func serverURL(_ value: String) throws -> URL {
    guard var parts = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
          parts.scheme?.lowercased() == "https", let host = parts.host, !host.isEmpty,
          parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil else { throw NavigatorError.invalidInput }
    parts.scheme = "https"; parts.host = host.lowercased()
    if parts.port == 443 { parts.port = nil }
    parts.path = parts.path.replacingOccurrences(of: #"/api/v1/?$"#, with: "", options: .regularExpression).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    if !parts.path.isEmpty { parts.path = "/" + parts.path }
    guard let url = parts.url else { throw NavigatorError.invalidInput }
    return url
}

extension JSONDecoder {
    static var sunshine: JSONDecoder { let decoder = JSONDecoder(); decoder.keyDecodingStrategy = .convertFromSnakeCase; return decoder }
}
