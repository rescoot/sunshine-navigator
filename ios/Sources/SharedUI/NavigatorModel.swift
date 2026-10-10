import SwiftUI
import MapKit

@MainActor final class NavigatorModel: ObservableObject {
    @Published var session: Session?
    @Published var scooters: [Scooter] = []
    @Published var places = PersonalPlaces.empty
    @Published var scooterPlaces: [Place] = []
    @Published var currentDestination: Place?
    @Published var browseID: Int64?
    @Published var preferredID: Int64?
    @Published var selectedPlace: Place?
    @Published var query = ""
    @Published var results: [Place] = []
    @Published var searching = false
    @Published var loading = false
    @Published var busy = false
    @Published var pending: PendingCommand?
    @Published var message: String?
    @Published var error: String?
    @Published var server = "https://sunshine.rescoot.org"
    @Published var clientID = AuthorizationRequest.defaultClient
    let api: SunshineAPI?
    let oauth: OAuth?
    let files: SharedFiles?
    private var generation = UUID()
    private var searchID = UUID()
    private var browseRequest = UUID()
    private var searchTask: Task<Void, Never>?
    private var mapSearch: MKLocalSearch?
    private let preferences = UserDefaults(suiteName: SharedFiles.group)
    private var lastImport: UUID?
    struct ImportedPlace: Codable, Sendable { let id: UUID; let text: String }

    init() {
        do {
            let files = try SharedFiles()
            let oauth = OAuth(files: files)
            self.files = files; self.oauth = oauth
            api = SunshineAPI(credentials: oauth, ledger: CommandLedger(files: files))
        } catch { files = nil; oauth = nil; api = nil; self.error = error.localizedDescription }
    }
    var sendableScooters: [Scooter] {
        scooters.filter(\.available).sorted { a, b in
            if a.id == preferredID { return true }; if b.id == preferredID { return false }; return a.name.localizedStandardCompare(b.name) == .orderedAscending
        }
    }
    func activate() async {
        guard let api else { return }
        do {
            let current = try await api.current()
            if current?.id != session?.id { reset(current) }
            if let files, let imported = (try await files.locked("import") { try files.read("import.json", as: ImportedPlace.self) }), imported.id != lastImport {
                lastImport = imported.id; query = imported.text; resolve()
                if session != nil {
                    try await files.locked("import") {
                        if try files.read("import.json", as: ImportedPlace.self)?.id == imported.id { try files.remove("import.json") }
                    }
                }
            }
            if session != nil { await refresh() }
        } catch { self.error = error.localizedDescription }
    }
    func importText(_ text: String) async {
        guard text.utf8.count <= PlaceInput.limit else { error = NavigatorError.invalidInput.localizedDescription; return }
        let imported = ImportedPlace(id: UUID(), text: text)
        do { if session == nil, let files { try await files.locked("import") { try files.write("import.json", imported) } } }
        catch { self.error = error.localizedDescription; return }
        lastImport = imported.id; query = text; resolve()
    }
    private func reset(_ record: Session?) {
        generation = UUID(); session = record
        invalidateSearch(); selectedPlace = nil; query = ""; lastImport = nil
        if let record { server = record.server.absoluteString; clientID = record.clientID }
        scooters = []; places = .empty; scooterPlaces = []; currentDestination = nil; browseID = nil
        pending = nil; loading = false; busy = false; message = nil; error = nil
        preferredID = record.flatMap { preferences?.string(forKey: "scooter:" + $0.identity).flatMap(Int64.init) }
    }
    func refresh() async {
        guard let session, let api else { return }
        let stamp = generation
        loading = true; error = nil
        do {
            let pending = try await api.pending(session)
            guard generation == stamp else { return }
            self.pending = pending
            let scooters = try await api.request([Scooter].self, session: session, path: "scooters")
            guard generation == stamp else { return }
            self.scooters = scooters
            let places = try await api.request(PersonalPlaces.self, session: session, path: "saved_destinations")
            guard generation == stamp else { return }
            self.places = PersonalPlaces(favorites: places.favorites.filter { $0.place.coordinate.isValid }, recent: places.recent.filter { $0.place.coordinate.isValid })
            if !scooters.contains(where: { $0.id == browseID }) { browseID = preferredID.flatMap { id in scooters.contains(where: { $0.id == id }) ? id : nil } ?? scooters.first?.id }
            loading = false
            await loadScooterPlaces()
        } catch { if generation == stamp { self.error = error.localizedDescription; loading = false } }
    }
    func loadScooterPlaces() async {
        guard let session, let api, let id = browseID else { return }
        let stamp = generation, request = UUID(); browseRequest = request
        scooterPlaces = []; currentDestination = nil
        do {
            let places = try await api.request(ScooterPlaces.self, session: session, path: "scooters/\(id)/locations")
            guard generation == stamp, browseID == id, browseRequest == request else { return }
            scooterPlaces = places.locations.map(\.place).filter { $0.coordinate.isValid }
            let destination = try await api.request(Destination.self, session: session, path: "scooters/\(id)/destination")
            guard generation == stamp, browseID == id, browseRequest == request else { return }
            currentDestination = destination.place
        } catch { if generation == stamp && browseRequest == request { self.error = error.localizedDescription } }
    }
    func prefer(_ id: Int64?) {
        preferredID = id
        if let session { preferences?.set(id.map(String.init), forKey: "scooter:" + session.identity) }
    }
    func invalidateSearch() {
        searchID = UUID(); searchTask?.cancel(); mapSearch?.cancel()
        searching = false; results = []
    }
    func resolve() {
        invalidateSearch()
        let input = query, stamp = searchID
        searching = true; error = nil; selectedPlace = nil
        searchTask = Task {
            do {
                var parsed = try PlaceInput.parse(input)
                if case .link(let link) = parsed { parsed = try await expand(link) }
                guard stamp == searchID, !Task.isCancelled else { return }
                switch parsed {
                case .place(let place): selectedPlace = place
                case .search(let text):
                    let request = MKLocalSearch.Request(); request.naturalLanguageQuery = text
                    let search = MKLocalSearch(request: request); mapSearch = search
                    let response = try await search.start()
                    guard stamp == searchID, !Task.isCancelled else { return }
                    results = response.mapItems.map { item in
                        Place(coordinate: Coordinate(latitude: item.placemark.coordinate.latitude, longitude: item.placemark.coordinate.longitude), label: item.placemark.title ?? item.name ?? "Pinned location")
                    }.filter { $0.coordinate.isValid }
                    if results.isEmpty { error = "No places found. Try an address or coordinates." }
                case .link: throw NavigatorError.invalidInput
                }
            } catch { if stamp == searchID && !Task.isCancelled { self.error = error.localizedDescription } }
            if stamp == searchID { searching = false }
        }
    }
    private func expand(_ initial: URL) async throws -> ParsedInput {
        let network = Network(); var url = initial
        for _ in 0..<6 {
            try Task.checkCancellation()
            guard PlaceInput.supported(url) else { throw NavigatorError.invalidInput }
            var request = URLRequest(url: url); request.httpMethod = "HEAD"
            let (_, response) = try await network.execute(request)
            if (300..<400).contains(response.statusCode), let location = response.value(forHTTPHeaderField: "Location"), let next = URL(string: location, relativeTo: url)?.absoluteURL, PlaceInput.supported(next) { url = next; continue }
            let parsed = try PlaceInput.parse(url.absoluteString)
            if case .link = parsed { throw NavigatorError.invalidInput }
            return parsed
        }
        throw NavigatorError.invalidInput
    }
    func send(to scooter: Scooter, place: Place?) async {
        guard let session, let api, scooter.available, !busy, !searching, pending == nil else { return }
        let command = PendingCommand(key: UUID(), identity: session.identity, scooterID: scooter.id, scooterName: scooter.name, place: place, createdAt: Date())
        let stamp = generation
        busy = true; error = nil; message = nil
        do {
            try await api.send(command, session: session)
            if generation == stamp { message = "Sunshine accepted \(place?.label ?? "stop navigation") for \(scooter.name). Check the dashboard for receipt." }
        } catch { if generation == stamp { self.error = error.localizedDescription } }
        if generation == stamp {
            do {
                let remaining = try await api.pending(session)
                guard generation == stamp else { return }
                pending = remaining
            } catch { if generation == stamp { self.error = error.localizedDescription } }
            if generation == stamp { busy = false }
        }
    }
    func checkReceipt() async {
        guard let pending, let session, let api, !busy else { return }
        busy = true; let stamp = generation
        do {
            let status = try await api.receipt(pending, session: session)
            guard generation == stamp else { return }
            let remaining = try await api.pending(session)
            guard generation == stamp else { return }
            self.pending = remaining
            message = status == "accepted" ? "Sunshine accepted the request. Check the dashboard for receipt." : "Request status: \(status)."
            error = nil
        } catch { if generation == stamp { self.error = error.localizedDescription } }
        if generation == stamp { busy = false }
    }
    func acknowledgePending() async {
        guard let pending, let session, let api, !busy else { return }
        do { try await api.acknowledge(pending, session: session); if self.session?.id == session.id { self.pending = nil; message = "Local warning dismissed. No command was sent or cancelled." } }
        catch { self.error = error.localizedDescription }
    }
    func save(_ place: Place, label: String, existing: SavedPlace? = nil, favorite: Bool = true) async {
        guard let session, let api, place.coordinate.isValid, !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        do {
            let body = try JSONSerialization.data(withJSONObject: ["latitude": place.coordinate.latitude, "longitude": place.coordinate.longitude, "label": label, "is_favorite": favorite])
            let _: SavedPlace = try await api.request(SavedPlace.self, session: session, path: "saved_destinations" + (existing.map { "/\($0.id)" } ?? ""), method: existing == nil ? "POST" : "PATCH", body: body)
            if self.session?.id == session.id { await refresh() }
        } catch { if self.session?.id == session.id { self.error = error.localizedDescription } }
    }
    func delete(_ place: SavedPlace) async {
        guard let session, let api else { return }
        do { try await api.deleteFavorite(place.id, session: session); if self.session?.id == session.id { await refresh() } }
        catch { if self.session?.id == session.id { self.error = error.localizedDescription } }
    }
    func signedIn(_ session: Session) async { reset(session); await activate() }
    func signOut() async {
        guard let session, let oauth, !busy else { return }
        busy = true
        do { let revoked = try await oauth.signOut(session); reset(nil); if !revoked { message = "Signed out locally. Remote revocation could not be confirmed." } }
        catch { self.error = error.localizedDescription; busy = false }
    }
}
