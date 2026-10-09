@preconcurrency import AVFoundation
import Foundation

/// Camera permission state for QR scanning. `unavailable` covers the
/// simulator and any other device with no capture device at all, distinct
/// from `denied`, which is a real device where the user said no.
enum CameraAuthorization: Equatable {
    case notDetermined
    case authorized
    case denied
    case restricted
    case unavailable
}

/// Seam over the camera so `QrScannerViewModel` can be tested without
/// `AVFoundation`. `AVFoundationQrScanner` is the real implementation;
/// `QrScannerViewModelTests` uses a fake.
///
/// `Sendable`: `QrScannerViewModel` is `@MainActor` and holds this as
/// `any QrScanning`, so the type has to be safe to hand across that
/// boundary. See `AVFoundationQrScanner`'s `@unchecked Sendable` below for
/// what actually makes that true property by property.
protocol QrScanning: AnyObject, Sendable {
    /// Current permission state. Reflects the last call to
    /// ``requestAuthorization()``, or the system state at init time.
    var authorization: CameraAuthorization { get }

    /// True when the active device has a torch (always false on the
    /// simulator, and on some devices/lenses).
    var hasTorch: Bool { get }

    /// The layer a `UIViewRepresentable` hosts to show the live camera feed.
    var previewLayer: AVCaptureVideoPreviewLayer { get }

    /// Set when the session could not be configured (no capture device, or
    /// the input/output could not be added), so the view model can surface
    /// a real message instead of silently starting a session that will
    /// never yield anything. Written on `sessionQueue` inside
    /// `configureSessionIfNeeded`; read on the main actor by the view
    /// model, but only after `start()`'s `withCheckedContinuation` has
    /// resumed, and that resume is itself the last thing
    /// `configureSessionIfNeeded` does before returning, so the read always
    /// sees the write that produced it.
    var configurationError: String? { get }

    /// Requests camera permission if not already determined, updating and
    /// returning ``authorization``. Safe to call again once already decided.
    ///
    /// `@MainActor`: this is the only place `authorization` is written, so
    /// confining it to the main actor confines every write to it, matching
    /// every read (`start()` and the view model, both only ever called from
    /// the main actor by contract). `AVCaptureDevice.requestAccess` can
    /// still be awaited from here; the isolation just guarantees control
    /// comes back to the main actor afterward instead of some arbitrary
    /// cooperative-pool thread.
    @discardableResult
    @MainActor
    func requestAuthorization() async -> CameraAuthorization

    /// Starts the capture session and returns a fresh stream of this run's
    /// decoded payloads, or `nil` if `authorization` is not `.authorized`,
    /// or the session could not be configured (see ``configurationError``).
    /// Each call finishes whatever stream a previous `start()` returned
    /// first, so a scanner that is stopped and started again (the screen
    /// dismissed and re-presented) hands back a live stream rather than one
    /// that is already `.terminated`: `AsyncStream` has no "restart", once
    /// an iterator over it ends that value is done for good.
    ///
    /// `@MainActor async`: configuring the session (wiring the device input
    /// and the metadata output) suspends rather than blocking the calling
    /// thread, so a call landing right behind a still-queued `stop()` (the
    /// screen dismissed and immediately re-presented) does not freeze the
    /// main thread waiting for `sessionQueue` to catch up.
    @MainActor
    func start() async -> AsyncStream<String>?

    /// Stops the capture session and finishes the current payload stream.
    /// Safe to call when not running.
    func stop()

    /// Turns the torch on or off. Returns whether it actually changed:
    /// `false` if ``hasTorch`` is false or the device refused the lock, so
    /// callers don't show a torch state that isn't real.
    @discardableResult
    func setTorch(on: Bool) -> Bool

    /// Restricts the QR decode search to `rect`, already converted to the
    /// metadata output's normalized coordinate space (see
    /// `AVCaptureVideoPreviewLayer.metadataOutputRectConverted(fromLayerRect:)`).
    /// A no-op until the session has been configured; scanning still works
    /// without ever calling this, it is purely a scan-area optimization so
    /// the decoder's search area matches the on-screen framing overlay.
    func setScanRect(_ rect: CGRect)
}

/// `AVCaptureSession` + `AVCaptureMetadataOutput` restricted to `.qr`. No ML
/// Kit, no third-party scanning library, `AVCaptureMetadataOutput` decodes
/// QR codes natively.
///
/// Free of SwiftUI on purpose: `QrScannerView` hosts ``previewLayer`` through
/// a `UIViewRepresentable`, and `QrScannerViewModel` drives the stream
/// returned by ``start()``.
///
/// Session configuration and `startRunning`/`stopRunning` must never run on
/// the main thread (Apple's guidance, both can block), so every session
/// operation, including the `AVCaptureMetadataOutputObjectsDelegate`
/// callback, happens on `sessionQueue`, a dedicated serial queue. `start()`
/// bridges `configureSessionIfNeeded`'s result back with
/// `withCheckedContinuation` so it can report a configuration failure
/// (`configurationError`) before returning, without ever blocking the
/// calling thread while `sessionQueue` gets to it, and `startRunning`/
/// `stopRunning` stay `async` for the same reason.
///
/// `@unchecked Sendable`, property by property (the same reasoning
/// `UniffiLightClientApi` documents for the identical shape of problem):
/// - `authorization`: written only inside `@MainActor requestAuthorization()`
///   (see the protocol); read from `start()` and by the view model, both of
///   which are only ever called from the main actor by the same contract.
/// - `continuation`, `metadataOutput`, `session`: mutated and read only
///   from closures dispatched onto `sessionQueue`, which serializes them;
///   the delegate callback below runs on that same queue.
/// - `configurationError`: written on `sessionQueue`, inside
///   `configureSessionIfNeeded`. Read on the main actor by the view model,
///   but not concurrently with that write: the write is the last thing
///   `configureSessionIfNeeded` does, and only after it returns does
///   `start()`'s `withCheckedContinuation` resume, which is what lets the
///   main-actor read happen at all. The write always precedes the read
///   that can observe it.
/// - `previewLayer`: a `let` set once in `init` and never reassigned.
final class AVFoundationQrScanner: NSObject, QrScanning, @unchecked Sendable {
    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.rjnr.pocketnode.qrscanner.session")
    private var continuation: AsyncStream<String>.Continuation?
    private var metadataOutput: AVCaptureMetadataOutput?

    let previewLayer: AVCaptureVideoPreviewLayer

    private(set) var authorization: CameraAuthorization
    private(set) var configurationError: String?

    override init() {
        self.previewLayer = AVCaptureVideoPreviewLayer(session: session)
        self.authorization = Self.currentAuthorization()
        super.init()
    }

    var hasTorch: Bool {
        AVCaptureDevice.default(for: .video)?.hasTorch ?? false
    }

    @MainActor
    @discardableResult
    func requestAuthorization() async -> CameraAuthorization {
        guard AVCaptureDevice.default(for: .video) != nil else {
            authorization = .unavailable
            return authorization
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            authorization = .authorized
        case .denied:
            authorization = .denied
        case .restricted:
            authorization = .restricted
        case .notDetermined:
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            authorization = granted ? .authorized : .denied
        @unknown default:
            authorization = .denied
        }
        return authorization
    }

    @MainActor
    func start() async -> AsyncStream<String>? {
        guard authorization == .authorized else { return nil }

        let configured = await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            sessionQueue.async { [weak self] in
                continuation.resume(returning: self?.configureSessionIfNeeded() ?? false)
            }
        }
        guard configured else { return nil }

        var rawContinuation: AsyncStream<String>.Continuation!
        let stream = AsyncStream<String> { rawContinuation = $0 }
        // Re-bound as a `let`: the closure below runs concurrently on
        // `sessionQueue`, and a captured `var` is unsafe there even though
        // the stream initializer has already returned by this point.
        let newContinuation = rawContinuation
        sessionQueue.async { [weak self] in
            self?.continuation?.finish()
            self?.continuation = newContinuation
            guard let session = self?.session, !session.isRunning else { return }
            session.startRunning()
        }
        return stream
    }

    func stop() {
        sessionQueue.async { [weak self] in
            self?.continuation?.finish()
            self?.continuation = nil
            guard let session = self?.session, session.isRunning else { return }
            session.stopRunning()
        }
    }

    @discardableResult
    func setTorch(on: Bool) -> Bool {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else { return false }
        do {
            try device.lockForConfiguration()
        } catch {
            return false
        }
        device.torchMode = on ? .on : .off
        device.unlockForConfiguration()
        return true
    }

    func setScanRect(_ rect: CGRect) {
        sessionQueue.async { [weak self] in
            self?.metadataOutput?.rectOfInterest = rect
        }
    }

    /// Adds the video input and the QR-only metadata output once. Runs on
    /// `sessionQueue` (via `start()`'s `sync` call). Returns whether the
    /// session ended up configured; `false` leaves `configurationError` set
    /// to a message the view model can surface, instead of silently
    /// starting an empty session that would never yield anything. Safe to
    /// call more than once: `session.inputs.isEmpty` guards against
    /// re-adding on a second `start()`.
    @discardableResult
    private func configureSessionIfNeeded() -> Bool {
        if !session.inputs.isEmpty {
            configurationError = nil
            return true
        }
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            configurationError = "Camera could not be configured"
            return false
        }

        session.beginConfiguration()
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            configurationError = "Camera could not be configured"
            return false
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: sessionQueue)
        output.metadataObjectTypes = [.qr]
        session.commitConfiguration()

        metadataOutput = output
        configurationError = nil
        return true
    }

    private static func currentAuthorization() -> CameraAuthorization {
        guard AVCaptureDevice.default(for: .video) != nil else { return .unavailable }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return .authorized
        case .denied: return .denied
        case .restricted: return .restricted
        case .notDetermined: return .notDetermined
        @unknown default: return .denied
        }
    }
}

extension AVFoundationQrScanner: AVCaptureMetadataOutputObjectsDelegate {
    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        for object in metadataObjects {
            guard let readable = object as? AVMetadataMachineReadableCodeObject,
                  readable.type == .qr,
                  let value = readable.stringValue else { continue }
            continuation?.yield(value)
        }
    }
}
