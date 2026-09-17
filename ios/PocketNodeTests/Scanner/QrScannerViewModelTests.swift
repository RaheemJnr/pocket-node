import AVFoundation
import PocketNodeCore
import XCTest

@testable import PocketNode

@MainActor
final class QrScannerViewModelTests: XCTestCase {
    /// Pinned cross-platform address-parity fixture (#517): the all-"abandon"
    /// BIP-39 test phrase's testnet/mainnet pair, asserted byte-for-byte in
    /// `WalletCreatorTests` and the shared module's
    /// `CrossPlatformAddressParityTest`.
    private static let testnetAddress =
        "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqenlarn"
    private static let mainnetAddress =
        "ckb1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqhp5jft"

    private var scanner: FakeQrScanner!
    private var preferences: FakeNetworkPreferences!
    private var scanned: [String] = []

    // `async` on purpose: see `ReceiveViewModelTests` / `PinServiceTests`, the
    // non-async override runs task-isolated and cannot touch this
    // `@MainActor` test case's properties.
    override func setUp() async throws {
        try await super.setUp()
        scanner = FakeQrScanner()
        preferences = FakeNetworkPreferences(network: .testnet)
        scanned = []
    }

    override func tearDown() async throws {
        scanner = nil
        preferences = nil
        scanned = []
        try await super.tearDown()
    }

    private func makeViewModel() -> QrScannerViewModel {
        QrScannerViewModel(scanner: scanner, preferences: preferences) { [weak self] address in
            self?.scanned.append(address)
        }
    }

    /// Lets a `Task` started by `onAppear()` and a just-emitted payload run
    /// before assertions. `AsyncStream` delivery and `Task` scheduling both
    /// hop through the run loop, so a bare synchronous assertion right after
    /// `emit` is not guaranteed to observe its effect yet.
    private func settle() async throws {
        try await Task.sleep(for: .milliseconds(50))
    }

    // MARK: - Successful scans

    func testPlainTestnetAddressScansOnceAndStopsTheScanner() async throws {
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()

        // Both emitted before either is consumed, so they land in the
        // stream's buffer together: this is the "live stream" the second
        // delivery must survive, so it is `hasScanned`, not stream
        // liveness, that stops `onScanned` firing a second time.
        scanner.emit(Self.testnetAddress)
        scanner.emit(Self.testnetAddress)
        try await settle()

        XCTAssertEqual(scanned, [Self.testnetAddress])
        XCTAssertGreaterThanOrEqual(scanner.stopCallCount, 1)
    }

    func testUppercasePayloadScansWithTheLowercasedAddress() async throws {
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()

        scanner.emit(Self.testnetAddress.uppercased())
        try await settle()

        XCTAssertEqual(scanned, [Self.testnetAddress])
    }

    func testJoyidUriExtractsTheEmbeddedAddress() async throws {
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()

        scanner.emit("joyid://\(Self.testnetAddress)")
        try await settle()

        XCTAssertEqual(scanned, [Self.testnetAddress])
    }

    func testSubmitPastedWithAValidAddressCallsOnScanned() async throws {
        let vm = makeViewModel()

        vm.submitPasted(Self.testnetAddress)
        try await settle()

        XCTAssertEqual(scanned, [Self.testnetAddress])
        XCTAssertNil(vm.errorMessage)
    }

    func testSubmitPastedTrimsWhitespaceBeforeValidating() async throws {
        let vm = makeViewModel()

        vm.submitPasted("  \(Self.testnetAddress)  \n")
        try await settle()

        XCTAssertEqual(scanned, [Self.testnetAddress])
    }

    func testAValidPayloadRightAfterARejectedOneSucceeds() async throws {
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()

        scanner.emit("garbage")
        try await settle()
        XCTAssertNotNil(vm.errorMessage)

        scanner.emit(Self.testnetAddress)
        try await settle()

        XCTAssertEqual(scanned, [Self.testnetAddress])
        XCTAssertNil(vm.errorMessage)
    }

    // MARK: - Rejections

    func testRandomTextReportsNoAddressAndDoesNotCallBack() async throws {
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()

        scanner.emit("not-an-address")
        try await settle()

        XCTAssertEqual(vm.errorMessage, "No CKB address in this code")
        XCTAssertTrue(scanned.isEmpty)
    }

    func testMixedCasePayloadReportsNoAddressAndDoesNotCallBack() async throws {
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()

        // Same address as `testnetAddress`, with one letter uppercased.
        // Mixed case is invalid bech32, so this must be rejected the same
        // way unparseable garbage is, not silently lowercased into validity.
        let mixed = "ckt1Qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqgedakp7g0hm0cdlq298xuyqpvl4ja0cfqenlarn"
        scanner.emit(mixed)
        try await settle()

        XCTAssertEqual(vm.errorMessage, "No CKB address in this code")
        XCTAssertTrue(scanned.isEmpty)
    }

    func testMainnetAddressOnATestnetWalletNamesBothNetworks() async throws {
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()

        scanner.emit(Self.mainnetAddress)
        try await settle()

        XCTAssertEqual(vm.errorMessage, "This is a mainnet address but the wallet is on testnet")
        XCTAssertTrue(scanned.isEmpty)
    }

    func testSubmitPastedRejectsAWrongNetworkAddress() async throws {
        let vm = makeViewModel()

        vm.submitPasted(Self.mainnetAddress)
        try await settle()

        XCTAssertEqual(vm.errorMessage, "This is a mainnet address but the wallet is on testnet")
        XCTAssertTrue(scanned.isEmpty)
    }

    func testSameBadPayloadTwiceWithinASecondProducesOneError() async throws {
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()

        scanner.emit("garbage")
        try await settle()
        XCTAssertEqual(vm.errorMessage, "No CKB address in this code")

        // Simulate the UI having cleared the error, then the same payload
        // arriving again inside the debounce window: it must not re-fire.
        vm.errorMessage = nil
        scanner.emit("garbage")
        try await settle()

        XCTAssertNil(vm.errorMessage, "a repeat of the same rejected payload within one second must be debounced")
    }

    func testADifferentRejectedPayloadWithinTheDebounceWindowStillReports() async throws {
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()

        scanner.emit("garbage-one")
        try await settle()
        XCTAssertEqual(vm.lastRejected, "garbage-one")

        // A different payload arriving right after must still be reported;
        // debouncing is keyed on the exact payload, not on "any rejection
        // happened recently".
        scanner.emit("garbage-two")
        try await settle()
        XCTAssertEqual(vm.lastRejected, "garbage-two")
        XCTAssertEqual(vm.errorMessage, "No CKB address in this code")
    }

    // MARK: - Lifecycle

    func testOnAppearStartsOnceAndOnDisappearStopsAndTurnsOffTheTorch() async throws {
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()
        XCTAssertEqual(scanner.startCallCount, 1)

        vm.toggleTorch()
        XCTAssertEqual(scanner.torchCalls, [true])

        vm.onDisappear()

        XCTAssertGreaterThanOrEqual(scanner.stopCallCount, 1)
        XCTAssertEqual(scanner.torchCalls, [true, false])
    }

    func testToggleTorchDoesNotFlipWhenTheDeviceRefuses() async throws {
        scanner.torchSucceeds = false
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()

        vm.toggleTorch()

        XCTAssertFalse(vm.isTorchOn, "the icon must not claim the torch is on when the device refused it")
        XCTAssertEqual(scanner.torchCalls, [true])
    }

    func testRePresentationDeliversAPayloadTheSecondTime() async throws {
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()
        vm.onDisappear()

        vm.onAppear()
        try await settle()
        scanner.emit(Self.testnetAddress)
        try await settle()

        XCTAssertEqual(scanned, [Self.testnetAddress])
    }

    // MARK: - Scan rect

    func testZeroBoundsPreviewLayerReportsNoScanRect() async throws {
        let vm = makeViewModel()
        // `scanner.previewLayer` starts with zero bounds: never laid out,
        // exactly the state `.onAppear` would have seen before this was
        // moved to `layoutSubviews`.
        vm.updateScanRect(layerRect: CGRect(x: 32, y: 32, width: 216, height: 216))

        XCTAssertTrue(scanner.scanRects.isEmpty)
    }

    func testValidPreviewLayerBoundsForwardsTheScanRectOnce() async throws {
        let vm = makeViewModel()
        scanner.previewLayer.frame = CGRect(x: 0, y: 0, width: 280, height: 280)

        vm.updateScanRect(layerRect: CGRect(x: 32, y: 32, width: 216, height: 216))

        XCTAssertEqual(scanner.scanRects.count, 1)
    }

    // MARK: - Permission states

    func testDeniedAuthorizationReachesThePublishedStateWithoutStarting() async throws {
        scanner = FakeQrScanner(authorization: .denied)
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()

        XCTAssertEqual(vm.authorization, .denied)
        XCTAssertEqual(scanner.startCallCount, 0)
    }

    func testUnavailableAuthorizationReachesThePublishedStateWithoutStarting() async throws {
        scanner = FakeQrScanner(authorization: .unavailable)
        let vm = makeViewModel()
        vm.onAppear()
        try await settle()

        XCTAssertEqual(vm.authorization, .unavailable)
        XCTAssertEqual(scanner.startCallCount, 0)
    }
}

/// A real `AVCaptureVideoPreviewLayer` never converts through
/// `metadataOutputRectConverted(fromLayerRect:)` correctly without a live
/// capture connection (no session in these tests ever starts running), so
/// it always answers with a degenerate rect regardless of `bounds`. This
/// override does the same proportional math the method does when there is a
/// connection, so `QrScannerViewModelTests` can exercise
/// `QrScannerViewModel.updateScanRect` without a real camera.
private final class TestablePreviewLayer: AVCaptureVideoPreviewLayer {
    override func metadataOutputRectConverted(fromLayerRect layerRect: CGRect) -> CGRect {
        guard bounds.width > 0, bounds.height > 0 else { return .zero }
        return CGRect(
            x: (layerRect.minX - bounds.minX) / bounds.width,
            y: (layerRect.minY - bounds.minY) / bounds.height,
            width: layerRect.width / bounds.width,
            height: layerRect.height / bounds.height
        )
    }
}

/// Scripted `QrScanning` double: emits payloads on demand and records calls
/// so tests can assert the scanner was actually started and stopped.
///
/// `@unchecked Sendable` to satisfy the protocol (see `AVFoundationQrScanner`):
/// every method here is only ever called from this file's `@MainActor` test
/// case, never actually shared across threads.
private final class FakeQrScanner: QrScanning, @unchecked Sendable {
    var authorization: CameraAuthorization
    var hasTorch = true
    let previewLayer: AVCaptureVideoPreviewLayer = TestablePreviewLayer(session: AVCaptureSession())
    var configurationError: String?

    /// Controls what `setTorch` reports back, so a test can exercise the
    /// "device refused the lock" path in `QrScannerViewModel.toggleTorch`.
    var torchSucceeds = true

    private(set) var startCallCount = 0
    private(set) var stopCallCount = 0
    private(set) var torchCalls: [Bool] = []
    private(set) var scanRects: [CGRect] = []

    private var continuation: AsyncStream<String>.Continuation?

    init(authorization: CameraAuthorization = .authorized) {
        self.authorization = authorization
    }

    @MainActor
    func requestAuthorization() async -> CameraAuthorization {
        authorization
    }

    @MainActor
    func start() async -> AsyncStream<String>? {
        guard authorization == .authorized else { return nil }
        startCallCount += 1
        continuation?.finish()
        var newContinuation: AsyncStream<String>.Continuation!
        let stream = AsyncStream<String> { newContinuation = $0 }
        continuation = newContinuation
        return stream
    }

    func stop() {
        stopCallCount += 1
        continuation?.finish()
        continuation = nil
    }

    func setTorch(on: Bool) -> Bool {
        torchCalls.append(on)
        return torchSucceeds
    }

    func setScanRect(_ rect: CGRect) {
        scanRects.append(rect)
    }

    func emit(_ payload: String) {
        continuation?.yield(payload)
    }
}

/// In-memory `NetworkPreferences` double: no `UserDefaults` side effects
/// between tests, unlike `UserDefaultsPreferences`.
private final class FakeNetworkPreferences: NetworkPreferences {
    private var network: NetworkType

    init(network: NetworkType) {
        self.network = network
    }

    func getSelectedNetwork() -> NetworkType {
        network
    }

    func setSelectedNetwork(network: NetworkType) {
        self.network = network
    }
}
