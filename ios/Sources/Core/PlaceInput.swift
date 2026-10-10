import Foundation

enum ParsedInput: Equatable {
    case place(Place), search(String), link(URL)
}

enum PlaceInput {
    static let limit = 16_384
    private static let pairPattern = #"^\s*([-+]?\d+(?:\.\d+)?)\s*,\s*([-+]?\d+(?:\.\d+)?)(?:\s*\(([^)]*)\))?\s*$"#
    static func matches(_ pattern: String, _ value: String) -> [[String]] {
        guard let expression = try? NSRegularExpression(pattern: pattern) else { return [] }
        let string = value as NSString
        return expression.matches(in: value, range: NSRange(location: 0, length: string.length)).map { match in
            (0..<match.numberOfRanges).map { match.range(at: $0).location == NSNotFound ? "" : string.substring(with: match.range(at: $0)) }
        }
    }
    static func pair(_ value: String) -> Coordinate? {
        guard let values = matches(pairPattern, value).first, let lat = Double(values[1]), let lon = Double(values[2]) else { return nil }
        let point = Coordinate(latitude: lat, longitude: lon)
        return point.isValid ? point : nil
    }
    static func supported(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "https", url.user == nil, url.password == nil, url.port == nil || url.port == 443,
              let host = url.host?.lowercased() else { return false }
        if ["maps.apple.com", "maps.app.goo.gl", "openstreetmap.org", "www.openstreetmap.org"].contains(host) { return true }
        if host == "goo.gl" { return url.path.hasPrefix("/maps/") }
        let google = matches(#"^(?:www\.|maps\.)?google\.(?:com|de|fr|es|it|nl|at|ch|co\.uk|ca|com\.au)$"#, host).isEmpty == false
        return google && (host.hasPrefix("maps.") || url.path == "/maps" || url.path.hasPrefix("/maps/"))
    }
    static func parse(_ raw: String) throws -> ParsedInput {
        guard !raw.isEmpty, raw.utf8.count <= limit else { throw NavigatorError.invalidInput }
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let link = matches(#"(?:https?://|geo:)[^\s<>]+"#, text).first?.first { text = link }
        if let point = pair(text) { return .place(Place(coordinate: point, label: matches(pairPattern, text).first?[3].nonempty ?? "Pinned location")) }
        if !matches(pairPattern, text).isEmpty { throw NavigatorError.invalidInput }
        if text.hasPrefix("geo:") {
            let pieces = String(text.dropFirst(4)).components(separatedBy: "?")
            guard let explicit = pair(pieces[0].components(separatedBy: ";")[0]) else { throw NavigatorError.invalidInput }
            let query = pieces.count > 1 ? URLComponents(string: "https://geo.invalid/?" + pieces[1])?.queryItems?.first(where: { $0.name == "q" })?.value : nil
            let validExplicit: Coordinate? = explicit.latitude == 0 && explicit.longitude == 0 ? nil : explicit
            let queryPoint = query.flatMap(pair).flatMap { $0.latitude == 0 && $0.longitude == 0 ? nil : $0 }
            let name = query.flatMap { label($0) }
            if let point = validExplicit ?? queryPoint { return .place(Place(coordinate: point, label: name ?? "Pinned location")) }
            if let name { return .search(name) }
            throw NavigatorError.invalidInput
        }
        guard text.contains("://") else { return .search(text) }
        guard let url = URL(string: text), supported(url), let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { throw NavigatorError.invalidInput }
        let query = Dictionary(components.queryItems?.compactMap { item in item.value.map { (item.name, $0) } } ?? [], uniquingKeysWith: { first, _ in first })
        let segments = url.path.components(separatedBy: "/").filter { !$0.isEmpty }
        for key in ["destination", "query", "q", "daddr"] {
            if let value = query[key], let point = pair(value) { return .place(Place(coordinate: point, label: label(value) ?? "Pinned location")) }
        }
        if let data = segments.first(where: { $0.hasPrefix("data=") }) ?? query["data"] {
            let route = segments.contains("dir") ? matches(#"!1d([-+]?\d+(?:\.\d+)?)!2d([-+]?\d+(?:\.\d+)?)(?=!|$)"#, data).last : nil
            let pin = matches(#"!3d([-+]?\d+(?:\.\d+)?)!4d([-+]?\d+(?:\.\d+)?)(?=!|$)"#, data).last
            if let values = route ?? pin, let point = pair(route == nil ? "\(values[1]),\(values[2])" : "\(values[2]),\(values[1])") {
                return .place(Place(coordinate: point, label: placeName(segments, query) ?? "Pinned location"))
            }
        }
        if url.host == "maps.apple.com", let value = query["coordinate"] ?? query["ll"], let point = pair(value) {
            return .place(Place(coordinate: point, label: query["q"].flatMap(label) ?? query["name"] ?? "Pinned location"))
        }
        if url.host?.hasSuffix("openstreetmap.org") == true, let lat = query["mlat"], let lon = query["mlon"], let point = pair("\(lat),\(lon)") {
            return .place(Place(coordinate: point, label: "Pinned location"))
        }
        if let index = segments.firstIndex(of: "place"), index + 1 < segments.count, let point = pair(segments[index + 1]) {
            return .place(Place(coordinate: point, label: "Pinned location"))
        }
        if let index = segments.firstIndex(of: "dir"), let endpoint = segments.dropFirst(index + 1).last(where: { !$0.hasPrefix("@") && !$0.hasPrefix("data=") }), let point = pair(endpoint) {
            return .place(Place(coordinate: point, label: "Pinned location"))
        }
        if let name = placeName(segments, query) { return .search(name) }
        if url.host == "maps.app.goo.gl" || url.host == "goo.gl" { return .link(url) }
        throw NavigatorError.invalidInput
    }
    private static func label(_ value: String) -> String? {
        if let match = matches(pairPattern, value).first { return match[3].nonempty }
        return value.nonempty
    }
    private static func placeName(_ path: [String], _ query: [String: String]) -> String? {
        if let index = path.firstIndex(of: "place"), index + 1 < path.count, pair(path[index + 1]) == nil { return path[index + 1].replacingOccurrences(of: "+", with: " ").nonempty }
        for key in ["destination", "query", "q", "daddr"] { if let value = query[key], let name = label(value) { return name } }
        if let index = path.firstIndex(of: "dir"), let end = path.dropFirst(index + 1).last(where: { !$0.hasPrefix("@") && !$0.hasPrefix("data=") }), pair(end) == nil { return end.replacingOccurrences(of: "+", with: " ").nonempty }
        return nil
    }
}

private extension String {
    var nonempty: String? { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self }
}
