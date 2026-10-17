// swift-tools-version: 6.0
import PackageDescription
let package = Package(
    name: "LumaCompanion",
    platforms: [.macOS(.v14)],
    products: [.executable(name: "LumaCompanion", targets: ["LumaCompanion"])],
    targets: [
        .target(name: "LumaAudioCore"),
        .target(name: "LumaPresentation"),
        .executableTarget(name: "LumaCompanion", dependencies: ["LumaAudioCore", "LumaPresentation"]),
        .testTarget(name: "LumaAudioCoreTests", dependencies: ["LumaAudioCore"]),
        .testTarget(name: "LumaPresentationTests", dependencies: ["LumaPresentation"])
    ],
    swiftLanguageModes: [.v5]
)
