import XCTest

final class NavigatorUITests: XCTestCase {
    @MainActor func testWelcomeAndConnectionSettings() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.buttons["sign-in"].waitForExistence(timeout: 15))
        app.buttons["Connection settings"].tap()
        XCTAssertTrue(app.textFields["server"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.textFields["server"].value as? String, "https://sunshine.rescoot.org")
        XCTAssertFalse(app.staticTexts["error"].exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Connection settings"; screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
