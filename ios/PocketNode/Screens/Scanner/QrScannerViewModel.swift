import AVFoundation
import Combine
import Foundation
import PocketNodeCore

/// Backs the QR scanner screen: drives ``QrScanning``, runs every decoded
/// payload through the shared core's address extraction and validation, and
/// hands a good address back through ``onScanned`` exactly once.
///
/// `ObservableObject`/`@Published` rather than the `@Observable` macro used
/// elsewhere in this app (see `ReceiveViewModel`): this view model needs to
/// be handed a plain reference by whatever screen presents it (Send), and
/// is exercised here with no SwiftUI view at all, so the older,
/// more explicit publishing model keeps the test surface simple.
@MainActor
final class QrScannerViewModel: ObservableObject {
    /// Camera permission state, mirrored from the scanner.
    @Published private(set) var authorization: CameraAuthorization = .notDetermined

    /// Set on a rejected payload (no address found, invalid address, wrong
    /// network) and cleared on a successful scan. `nil` means nothing to show.
    @Published var errorMessage: String?

    /// Whether the torch is currently on. Only meaningful when ``hasTorch``.
    @Published private(set) var isTorchOn = false

    /// The most recently rejected raw payload, kept so a repeat of the same
    /// payload within a second is not re-announced (see `reject(_:message:)`).
    @Published private(set) var lastRejected: String?

    private let scanner: any QrScanning
    private let preferences: any NetworkPreferences
    private let onScanned: (String) -> Void

    private var authTask: Task<Void, Never>?
    private var scanTask: Task<Void, Never>?
    private var hasScanned = false
    private var lastRejectedAt: Date?

    /// Bumped each time a scan run actually starts, so the consuming
    /// `Task` created in `startScanning()` can tell whether it is still the
    /// current one by the time it finishes. Plain `Task` values have no
    /// identity to compare, so this stands in for one.
    private var scanGeneration = 0

    init(scanner: any QrScanning, preferences: any NetworkPreferences, onScanned: @escaping (String) -> Void) {
        self.scanner = scanner
        self.preferences = preferences
        self.onScanned = onScanned
        self.authorization = scanner.authorization
    }

    var hasTorch: Bool { scanner.hasTorch }
    var previewLayer: AVCaptureVideoPreviewLayer { scanner.previewLayer }

    /// Call from `.onAppear`: requests camera permission if needed, and
    /// starts the session once authorized. Resets `hasScanned` so a scanner
    /// that is dismissed and re-presented can scan again.
    ///
    /// Guards on both `authTask` and `scanTask`, not just whichever is in
    /// flight: `scanTask` is only assigned after the permission `await`
    /// resolves, so without also checking `authTask` two `onAppear` calls
    /// landing inside that window would each spawn their own permission
    /// request and, if both resolve authorized, their own scan.
    func onAppear() {
        guard authTask == nil, scanTask == nil else { return }
        hasScanned = false
        authTask = Task { [weak self] in
            guard let self else { return }
            let status = await self.scanner.requestAuthorization()
            // `onDisappear` may have cancelled this while the permission
            // request was in flight; without this check the camera would
            // start after the view is already gone.
            guard !Task.isCancelled else { return }
            self.authTask = nil
            self.authorization = status
            guard status == .authorized else { return }
            await self.startScanning()
        }
    }

    /// Call from `.onDisappear`: cancels a still-pending permission request
    /// so the camera cannot start after the view has already gone, stops
    /// the session, and turns the torch off.
    func onDisappear() {
        authTask?.cancel()
        authTask = nil
        scanTask?.cancel()
        scanTask = nil
        scanner.stop()
        if isTorchOn {
            _ = scanner.setTorch(on: false)
            isTorchOn = false
        }
    }

    /// Only flips `isTorchOn` once the device confirms the change, so the
    /// icon never shows a torch state that isn't real (e.g. the device
    /// refused the configuration lock).
    func toggleTorch() {
        guard scanner.hasTorch else { return }
        let target = !isTorchOn
        guard scanner.setTorch(on: target) else { return }
        isTorchOn = target
    }

    /// The paste fallback, for the simulator and for anyone who would rather
    /// copy an address than scan one. Runs the same validation pipeline as a
    /// camera payload, but unlike a camera payload a deliberate paste always
    /// gets a fresh answer: it bypasses the debounce that exists for a QR
    /// code sitting in frame across repeated metadata callbacks, not for a
    /// user who retyped or re-pasted the same text on purpose. `lastRejected`
    /// and the camera path share the one debounce, so clearing it here also
    /// re-arms that path: a code that was just rejected reports again
    /// immediately if it reappears in frame right after a paste attempt.
    func submitPasted(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        handle(payload: trimmed, bypassDebounce: true)
    }

    /// Reports the on-screen framing rect (in `previewLayer`'s own
    /// coordinate space) so the decoder's scan area matches what the
    /// overlay shows, rather than scanning the whole frame.
    ///
    /// Guards against a not-yet-laid-out preview layer: converting through
    /// a zero-bounds layer produces a degenerate rect, and handing that to
    /// `AVCaptureMetadataOutput.rectOfInterest` (outside `0...1`) throws
    /// `NSInvalidArgumentException`. The result is also clamped into
    /// `0...1` before being forwarded, since `rectOfInterest` rejects
    /// anything outside that range.
    func updateScanRect(layerRect: CGRect) {
        guard previewLayer.bounds.width > 0, previewLayer.bounds.height > 0,
              layerRect.width > 0, layerRect.height > 0 else { return }

        let converted = previewLayer.metadataOutputRectConverted(fromLayerRect: layerRect)
        let unitSquare = CGRect(x: 0, y: 0, width: 1, height: 1)
        let clamped = converted.intersection(unitSquare)
        guard !clamped.isNull, clamped.width > 0, clamped.height > 0 else { return }
        scanner.setScanRect(clamped)
    }

    private func startScanning() async {
        guard scanTask == nil else { return }
        guard let stream = await scanner.start() else {
            errorMessage = scanner.configurationError ?? "Camera could not be configured"
            return
        }
        // `onDisappear` may have cancelled `authTask` (whose body awaited
        // us) while `scanner.start()` was suspended configuring the
        // session; without this check the camera would start consuming
        // frames after the view has already gone.
        guard !Task.isCancelled else { return }

        scanGeneration += 1
        let generation = scanGeneration
        scanTask = Task { [weak self] in
            guard let self else { return }
            for await payload in stream {
                guard !Task.isCancelled else { break }
                self.handle(payload: payload)
            }
            // Only clear `scanTask` if it is still this run: a stopped and
            // immediately re-started scan spawns a newer task before this
            // one's loop notices its stream finished, and this one must not
            // null out that newer task's reference.
            if self.scanGeneration == generation {
                self.scanTask = nil
            }
        }
    }

    private func handle(payload: String, bypassDebounce: Bool = false) {
        guard !hasScanned else { return }
        if bypassDebounce {
            // A deliberate paste always gets a fresh answer rather than
            // being silently swallowed by the same-payload debounce below.
            errorMessage = nil
            lastRejected = nil
            lastRejectedAt = nil
        }

        guard let address = QrUriParserKt.extractCkbAddress(raw: payload) else {
            reject(payload, message: "No CKB address in this code")
            return
        }
        guard AddressUtils.shared.isValid(address: address) else {
            reject(payload, message: "Not a valid CKB address")
            return
        }
        let selectedNetwork = preferences.getSelectedNetwork()
        let scannedNetwork = AddressUtils.shared.getNetwork(address: address)
        guard scannedNetwork == selectedNetwork else {
            let scannedName = scannedNetwork.map(Self.displayName) ?? "a different network"
            reject(payload, message: "This is a \(scannedName) address but the wallet is on \(Self.displayName(selectedNetwork))")
            return
        }

        // `stop()` finishes the current stream; the consuming `for await`
        // loop in `startScanning()` ends on its own next turn and clears
        // `scanTask`. Any payload already buffered ahead of this one (the
        // same QR code decoded twice before either is consumed) still
        // reaches `handle`, and it is the `hasScanned` guard above, not
        // stream liveness, that must stop it calling `onScanned` again.
        hasScanned = true
        errorMessage = nil
        lastRejected = nil
        onScanned(address)
        scanner.stop()
    }

    /// Debounces repeats: the same rejected payload is not re-announced more
    /// than once per second, so a code that stays in frame across several
    /// metadata callbacks does not spam the error text.
    private func reject(_ payload: String, message: String) {
        let now = Date()
        if lastRejected == payload, let lastRejectedAt, now.timeIntervalSince(lastRejectedAt) < 1 {
            return
        }
        lastRejectedAt = now
        lastRejected = payload
        errorMessage = message
    }

    private static func displayName(_ network: NetworkType) -> String {
        network == .mainnet ? "mainnet" : "testnet"
    }
}
