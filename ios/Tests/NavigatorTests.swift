import XCTest
#if canImport(UIKit)
import UIKit
#endif
#if canImport(NavigatorCore)
@testable import NavigatorCore
#else
@testable import Navigator
#endif

final class NavigatorTests: XCTestCase {
    #if canImport(UIKit)
    @MainActor func testBundledArtworkAndTypeface() async {
        XCTAssertNotNil(UIImage(named: "sunshine_logo"))
        XCTAssertNotNil(UIImage(named: "scooter"))
        XCTAssertNotNil(UIFont(name: "GL-Nummernschild-Eng", size: 20))
    }
    #endif
    func testCoordinateBounds() {
        XCTAssertTrue(Coordinate(latitude: 0, longitude: 0).isValid)
        XCTAssertTrue(Coordinate(latitude: 90, longitude: -180).isValid)
        XCTAssertFalse(Coordinate(latitude: .nan, longitude: 1).isValid)
        XCTAssertFalse(Coordinate(latitude: 91, longitude: 1).isValid)
        XCTAssertFalse(Coordinate(latitude: 1, longitude: 181).isValid)
    }
    func testExactGeoCoordinatesOutrankAddressAndQueryCoordinates() throws {
        let result = try PlaceInput.parse("geo:52.500218,13.270753?q=CityCube%20Berlin%2C%20Messedamm%2026")
        XCTAssertEqual(result, .place(Place(coordinate: Coordinate(latitude: 52.500218, longitude: 13.270753), label: "CityCube Berlin, Messedamm 26")))
        guard case .place(let place) = try PlaceInput.parse("geo:52.500218,13.270753?q=50,10") else { return XCTFail() }
        XCTAssertEqual(place.coordinate, Coordinate(latitude: 52.500218, longitude: 13.270753))
    }
    func testGeoPlaceholderUsesQuery() throws {
        XCTAssertEqual(try PlaceInput.parse("geo:0,0?q=Berlin%20Hbf"), .search("Berlin Hbf"))
        guard case .place(let place) = try PlaceInput.parse("geo:0,0?q=52,13(Park)") else { return XCTFail() }
        XCTAssertEqual(place.label, "Park")
        XCTAssertThrowsError(try PlaceInput.parse("geo:0,0"))
        XCTAssertThrowsError(try PlaceInput.parse("geo:999,13?q=Berlin"))
        XCTAssertThrowsError(try PlaceInput.parse("geo:999,13?q=52,13"))
    }
    func testPlainZeroIsNotGeoPlaceholder() throws {
        XCTAssertEqual(try PlaceInput.parse("0,0"), .place(Place(coordinate: Coordinate(latitude: 0, longitude: 0), label: "Pinned location")))
    }
    func testInvalidPairsAndOversizedInputsFail() {
        XCTAssertThrowsError(try PlaceInput.parse("95,13"))
        XCTAssertThrowsError(try PlaceInput.parse(String(repeating: "a", count: PlaceInput.limit + 1)))
    }
    func testViewportIsNotDestination() {
        XCTAssertThrowsError(try PlaceInput.parse("https://www.google.com/maps/@52.1,13.1,16z"))
        XCTAssertThrowsError(try PlaceInput.parse("https://www.openstreetmap.org/#map=15/52.1/13.1"))
    }
    func testExplicitGooglePinOutranksViewport() throws {
        guard case .place(let place) = try PlaceInput.parse("https://www.google.com/maps/place/Park/@50,10,16z/data=!3d52.3!4d13.4") else { return XCTFail() }
        XCTAssertEqual(place.coordinate, Coordinate(latitude: 52.3, longitude: 13.4))
        XCTAssertEqual(place.label, "Park")
    }
    func testFinalRoutePoint() throws {
        guard case .place(let place) = try PlaceInput.parse("https://www.google.com/maps/dir/A/B/data=!1d10!2d50!1d13!2d52") else { return XCTFail() }
        XCTAssertEqual(place.coordinate, Coordinate(latitude: 52, longitude: 13))
        guard case .place(let endpoint) = try PlaceInput.parse("https://www.google.com/maps/dir/50,10/52,13/@49,9,7z") else { return XCTFail() }
        XCTAssertEqual(endpoint.coordinate, Coordinate(latitude: 52, longitude: 13))
    }
    func testAppleAndOSMPins() throws {
        XCTAssertEqual(try PlaceInput.parse("https://maps.apple.com/?ll=52,13&q=Park"), .place(Place(coordinate: Coordinate(latitude: 52, longitude: 13), label: "Park")))
        guard case .place(let place) = try PlaceInput.parse("https://www.openstreetmap.org/?mlat=52&mlon=13") else { return XCTFail() }
        XCTAssertEqual(place.coordinate, Coordinate(latitude: 52, longitude: 13))
    }
    func testMapURLAllowlist() {
        for url in ["http://maps.apple.com/?ll=52,13", "https://maps.apple.com.evil.test/?ll=52,13", "https://127.0.0.1/", "https://user:secret@maps.apple.com/", "https://maps.apple.com:8443/"] { XCTAssertThrowsError(try PlaceInput.parse(url)) }
    }
    func testServerValidation() throws {
        XCTAssertEqual(try serverURL("https://example.org/api/v1").absoluteString, "https://example.org")
        for value in ["http://example.org", "https://user:pass@example.org", "https://example.org?token=secret", "https://example.org#fragment"] { XCTAssertThrowsError(try serverURL(value)) }
    }
    func testEquivalentOriginsShareAnAccountNamespace() throws {
        XCTAssertEqual(try serverURL("HTTPS://EXAMPLE.ORG:443/api/v1/"), try serverURL("https://example.org"))
    }
    func testStorageErrorDoesNotClaimTransportOutcome() {
        XCTAssertEqual(NavigatorError.storage.localizedDescription, "Secure storage is unavailable.")
    }
    func testOAuthPKCEAndState() throws {
        let request = try AuthorizationRequest(server: serverURL("https://example.org"), clientID: "public-client")
        XCTAssertEqual(request.verifier.count, 43)
        let query = URLComponents(url: request.url, resolvingAgainstBaseURL: false)!.queryItems!
        XCTAssertEqual(query.first(where: { $0.name == "code_challenge_method" })?.value, "S256")
        XCTAssertFalse(request.url.absoluteString.contains(request.verifier))
        XCTAssertEqual(try request.code(from: URL(string: "\(AuthorizationRequest.callback)?state=\(request.state)&code=abc")!), "abc")
        XCTAssertThrowsError(try request.code(from: URL(string: "\(AuthorizationRequest.callback)?state=wrong&code=abc")!))
        XCTAssertThrowsError(try request.code(from: URL(string: "https://example.org/oauth2redirect?state=\(request.state)&code=abc")!))
        XCTAssertThrowsError(try request.code(from: URL(string: "\(AuthorizationRequest.callback)?state=\(request.state)&state=\(request.state)&code=abc")!))
    }
    func testFormEncoding() { XCTAssertEqual(String(data: form(["token": "a+b&c= d"]), encoding: .utf8), "token=a%2Bb%26c%3D%20d") }
    func testScooterMetadataAndEligibility() throws {
        let scooter = try JSONDecoder.sunshine.decode(Scooter.self, from: Data(#"{"id":1,"name":"Blue","online":true,"state":"parked","model":{"model_name":"Scooter"},"images":{"right":"https://example.org/right.png"},"capabilities":{"navigate":false},"license_plate":"176 JLG","license_plate_type":"versicherungskennzeichen"}"#.utf8))
        XCTAssertFalse(scooter.available)
        XCTAssertEqual(scooter.artwork?.absoluteString, "https://example.org/right.png")
        XCTAssertEqual(scooter.licensePlateType, "versicherungskennzeichen")
    }
    func testBerlinInsuranceSeasonBoundary() {
        XCTAssertEqual(PlateSeason.year(at: PlateSeason.date("2026-02-28T22:59:59Z")!), 2025)
        XCTAssertEqual(PlateSeason.year(at: PlateSeason.date("2026-02-28T23:00:00Z")!), 2026)
    }
}

actor TestCredentials: Credentials {
    var record: Session?
    init(_ record: Session) { self.record = record }
    func current() -> Session? { record }
    func authorized(_ session: Session, refresh: Bool) throws -> Session { guard let record, record.id == session.id else { throw NavigatorError.signedOut }; return record }
}
actor TestTransport: HTTPTransport {
    var requests: [URLRequest] = []
    var beforeResponse: (@Sendable () throws -> Void)?
    func setBeforeResponse(_ action: @escaping @Sendable () throws -> Void) { beforeResponse = action }
    let status: Int
    let body: String
    let failure: Bool
    init(status: Int = 200, body: String = #"{"status":"accepted"}"#, failure: Bool = false) { self.status = status; self.body = body; self.failure = failure }
    func execute(_ request: URLRequest) throws -> (Data, HTTPURLResponse) {
        requests.append(request)
        if failure { throw URLError(.timedOut) }
        try beforeResponse?()
        return (Data(body.utf8), HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }
}

final class CommandTests: XCTestCase {
    private func fixture(_ transport: TestTransport) throws -> (SunshineAPI, CommandLedger, Session, PendingCommand) {
        let files = try SharedFiles(root: FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString))
        addTeardownBlock { try? FileManager.default.removeItem(at: files.root.deletingLastPathComponent()) }
        let session = Session(id: UUID(), server: URL(string: "https://example.org")!, clientID: "client", account: Account(id: 1, name: "Rider", email: "rider@example.org", avatarUrl: nil), accessToken: "test-access", refreshToken: "test-refresh", expiresAt: Date().addingTimeInterval(3600), scope: "read write scooter_control")
        let ledger = CommandLedger(files: files)
        let api = SunshineAPI(credentials: TestCredentials(session), ledger: ledger, transport: transport)
        let command = PendingCommand(key: UUID(), identity: session.identity, scooterID: 42, scooterName: "Blue", place: Place(coordinate: Coordinate(latitude: 52.500218, longitude: 13.270753), label: "CityCube"), createdAt: Date())
        return (api, ledger, session, command)
    }
    func testExplicitSendPreservesTargetAndRequestKey() async throws {
        let transport = TestTransport(); let (api, ledger, session, command) = try fixture(transport)
        try await api.send(command, session: session)
        let requests = await transport.requests
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(requests[0].httpMethod, "PUT")
        XCTAssertEqual(requests[0].url?.path, "/api/v1/scooters/42/destination")
        XCTAssertEqual(requests[0].value(forHTTPHeaderField: "Idempotency-Key"), command.key.uuidString.lowercased())
        let json = try JSONSerialization.jsonObject(with: requests[0].httpBody!) as! [String: Any]
        XCTAssertEqual(json["latitude"] as? Double, 52.500218)
        let pending = try await ledger.pending(session.identity); XCTAssertNil(pending)
    }
    func testAmbiguousFailurePersistsAndDoesNotResend() async throws {
        let transport = TestTransport(failure: true); let (api, ledger, session, command) = try fixture(transport)
        do { try await api.send(command, session: session); XCTFail() } catch { XCTAssertEqual(error as? NavigatorError, .uncertain) }
        let pending = try await ledger.pending(session.identity); XCTAssertEqual(pending, command)
        do { try await api.send(command, session: session); XCTFail() } catch { XCTAssertEqual(error as? NavigatorError, .pending) }
        let requests = await transport.requests; XCTAssertEqual(requests.count, 1)
    }
    func testStorageFailureAfterDispatchRemainsUncertain() async throws {
        let transport = TestTransport(); let (api, ledger, session, command) = try fixture(transport)
        let lock = ledger.files.root.appendingPathComponent("commands.lock")
        await transport.setBeforeResponse {
            try FileManager.default.removeItem(at: lock)
            try FileManager.default.createDirectory(at: lock, withIntermediateDirectories: false)
        }
        do { try await api.send(command, session: session); XCTFail() } catch { XCTAssertEqual(error as? NavigatorError, .uncertain) }
        let stored = try ledger.files.read("commands.json", as: [String: PendingCommand].self)
        XCTAssertEqual(stored?[session.identity], command)
        let requests = await transport.requests; XCTAssertEqual(requests.count, 1)
    }
    func testDefiniteRejectionClearsPending() async throws {
        let transport = TestTransport(status: 422); let (api, ledger, session, command) = try fixture(transport)
        do { try await api.send(command, session: session); XCTFail() } catch { XCTAssertEqual(error as? NavigatorError, .http(422)) }
        let pending = try await ledger.pending(session.identity); XCTAssertNil(pending)
    }
    func testProcessingIsNotSuccess() async throws {
        let transport = TestTransport(status: 202, body: #"{"status":"processing"}"#); let (api, ledger, session, command) = try fixture(transport)
        do { try await api.send(command, session: session); XCTFail() } catch { XCTAssertEqual(error as? NavigatorError, .uncertain) }
        let pending = try await ledger.pending(session.identity); XCTAssertEqual(pending, command)
    }
    func testReceiptOnlyUsesGET() async throws {
        let transport = TestTransport(body: #"{"status":"processing"}"#); let (api, ledger, session, command) = try fixture(transport)
        try await ledger.reserve(command)
        let status = try await api.receipt(command, session: session)
        XCTAssertEqual(status, "processing")
        let requests = await transport.requests
        XCTAssertEqual(requests.count, 1); XCTAssertEqual(requests[0].httpMethod, "GET")
        XCTAssertTrue(requests[0].url!.path.hasSuffix(command.key.uuidString.lowercased()))
        let pending = try await ledger.pending(session.identity); XCTAssertNotNil(pending)
    }
    func testAcceptedReceiptClearsMatchingPending() async throws {
        let transport = TestTransport(); let (api, ledger, session, command) = try fixture(transport)
        try await ledger.reserve(command); _ = try await api.receipt(command, session: session)
        let pending = try await ledger.pending(session.identity); XCTAssertNil(pending)
    }
    func testPendingSurvivesLedgerRecreationAndIsAccountScoped() async throws {
        let (_, ledger, session, command) = try fixture(TestTransport())
        try await ledger.reserve(command)
        let restored = CommandLedger(files: ledger.files)
        let pending = try await restored.pending(session.identity); XCTAssertEqual(pending, command)
        let unrelated = try await restored.pending("another-account"); XCTAssertNil(unrelated)
    }
    func testReservationIsSharedAcrossLedgerInstances() async throws {
        let (_, ledger, _, command) = try fixture(TestTransport()); try await ledger.reserve(command)
        let other = CommandLedger(files: ledger.files)
        do { try await other.reserve(command); XCTFail() } catch { XCTAssertEqual(error as? NavigatorError, .pending) }
    }
    func testDifferentAccountCannotDispatch() async throws {
        let transport = TestTransport(); let (api, _, session, command) = try fixture(transport)
        let foreign = PendingCommand(key: command.key, identity: "another-account", scooterID: 42, scooterName: "Blue", place: command.place, createdAt: Date())
        do { try await api.send(foreign, session: session); XCTFail() } catch { XCTAssertEqual(error as? NavigatorError, .invalidInput) }
        let requests = await transport.requests; XCTAssertTrue(requests.isEmpty)
    }
}
