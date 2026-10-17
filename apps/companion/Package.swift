// swift-tools-version: 6.0
import PackageDescription
let package = Package(
    name: "LumaCompanion",
    platforms: [.macOS(.v14)],
    products: [.executable(name: "LumaCompanion", targets: ["LumaCompanion"])],
    targets: [
        .target(name: "LumaAudioCore"),
        .executableTarget(name: "LumaCompanion", dependencies: ["LumaAudioCore"]),
        .testTarget(name: "LumaAudioCoreTests", dependencies: ["LumaAudioCore"])
    ],
    swiftLanguageModes: [.v5]
)
