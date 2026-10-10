// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "NavigatorCore",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [.library(name: "NavigatorCore", targets: ["NavigatorCore"])],
    targets: [
        .target(name: "NavigatorCore", path: "Sources/Core"),
        .testTarget(name: "NavigatorCoreTests", dependencies: ["NavigatorCore"], path: "Tests")
    ]
)
