// swift-tools-version:5.9
import PackageDescription

// The binary target is produced by external/ckb-light-client/build-ios.sh, which
// also regenerates Sources/CkbLightClient/CkbLightClient.swift from the Rust
// `ffi` module via uniffi-bindgen. The xcframework itself is not checked in.
let package = Package(
    name: "CkbLightClient",
    platforms: [
        .iOS(.v15)
    ],
    products: [
        .library(
            name: "CkbLightClient",
            targets: ["CkbLightClient"]
        )
    ],
    targets: [
        .binaryTarget(
            name: "CkbLightClientFFI",
            path: "CkbLightClientFFI.xcframework"
        ),
        .target(
            name: "CkbLightClient",
            dependencies: ["CkbLightClientFFI"]
        )
    ]
)
