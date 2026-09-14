# Pocket Node for iOS

M1 skeleton: a SwiftUI shell around the embedded CKB light client (testnet) and
the shared Kotlin Multiplatform core.

## Prerequisites

- Xcode 26.x on Apple Silicon, iOS 17.0+ simulator or device
- Rust with the `aarch64-apple-ios` and `aarch64-apple-ios-sim` targets
- `brew install xcodegen`
- `android/local.properties` with `sdk.dir` set (Gradle builds the KMP framework)

## Build and run

```bash
external/ckb-light-client/build-ios.sh   # once; produces CkbLightClientFFI.xcframework
cd ios && xcodegen generate              # regenerates PocketNode.xcodeproj (not committed)
open PocketNode.xcodeproj                # then run the PocketNode scheme
```

`PocketNode.xcodeproj` is generated from `project.yml`; edit the spec, not the
project. A pre-build script phase runs
`./gradlew :shared:embedAndSignAppleFrameworkForXcode` to produce
`PocketNodeCore.framework`.

## Tests

- `xcodebuild -scheme PocketNode ... test` runs the offline unit tests.
- `xcodebuild -scheme PocketNodeNetwork ... test` runs the Node Status
  acceptance test, which starts the node and waits for a real testnet tip.
