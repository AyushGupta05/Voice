// swift-tools-version: 6.0
import PackageDescription
let package = Package(
    name: "LumaCompanion",
    platforms: [.macOS(.v14)],
    products: [.executable(name: "LumaCompanion", targets: ["LumaCompanion"])],
    targets: [.executableTarget(name: "LumaCompanion")],
    swiftLanguageModes: [.v5]
)
