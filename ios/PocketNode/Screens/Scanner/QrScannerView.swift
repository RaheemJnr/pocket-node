import AVFoundation
import SwiftUI

/// Scans a CKB address with the camera, or accepts one pasted in. Presented
/// by whatever screen needs an address (Send); this view knows
/// nothing about who is listening, it only drives `viewModel` and lets
/// `onScanned`, wired at construction, carry the result out.
struct QrScannerView: View {
    @ObservedObject var viewModel: QrScannerViewModel
    @Environment(\.colorScheme) private var colorScheme
    @State private var pastedText = ""

    private var theme: Theme { Theme.forScheme(colorScheme) }

    var body: some View {
        VStack(spacing: 20) {
            Text("Scan a CKB address")
                .font(.title2.weight(.semibold))

            content

            if let errorMessage = viewModel.errorMessage {
                Text(errorMessage)
                    .font(.footnote)
                    .foregroundStyle(theme.error)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12)
                    .accessibilityIdentifier("scanner.error")
            }

            pasteFallback

            Spacer(minLength: 0)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .onAppear { viewModel.onAppear() }
        .onDisappear { viewModel.onDisappear() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("scanner.root")
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.authorization {
        case .authorized:
            cameraCard
        case .denied, .restricted:
            deniedCard
        case .unavailable, .notDetermined:
            unavailableCard
        }
    }

    private var cameraCard: some View {
        ZStack(alignment: .bottom) {
            // The scan rect is reported from `onLayout`, not `.onAppear`:
            // `.onAppear` fires before `makeUIView`/`layoutSubviews` ever
            // sizes the preview layer, so at that point its bounds are
            // still zero and converting the framing rect through it would
            // be meaningless (and, on a re-presentation where the metadata
            // output already exists, would apply a degenerate
            // `rectOfInterest` that stops the decoder scanning anything).
            CameraPreviewRepresentable(previewLayer: viewModel.previewLayer) { bounds in
                viewModel.updateScanRect(layerRect: bounds.insetBy(dx: 32, dy: 32))
            }
            .accessibilityIdentifier("scanner.preview")

            RoundedRectangle(cornerRadius: 16)
                .stroke(Color.white.opacity(0.85), lineWidth: 3)
                .padding(32)
                .allowsHitTesting(false)

            if viewModel.hasTorch {
                torchButton
                    .padding(.bottom, 16)
            }
        }
        .frame(width: 280, height: 280)
        .background(Color.black, in: RoundedRectangle(cornerRadius: 16))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var torchButton: some View {
        Button {
            viewModel.toggleTorch()
        } label: {
            Image(systemName: viewModel.isTorchOn ? "bolt.fill" : "bolt.slash")
                .foregroundStyle(.white)
                .padding(12)
                .background(.ultraThinMaterial, in: Circle())
        }
        .accessibilityIdentifier("scanner.torch")
    }

    private var deniedCard: some View {
        VStack(spacing: 12) {
            Text("Camera access is off")
                .font(.subheadline.weight(.semibold))
            Text("Turn on camera access in Settings to scan a QR code, or paste an address below.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("Open Settings") {
                openSettings()
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("scanner.openSettings")
        }
        .padding(20)
        .frame(width: 280)
        .background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.gray.opacity(0.2)))
    }

    private var unavailableCard: some View {
        VStack(spacing: 8) {
            Text("Camera not available")
                .font(.subheadline.weight(.semibold))
            Text("Paste an address below instead.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(20)
        .frame(width: 280)
        .background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.gray.opacity(0.2)))
    }

    private var pasteFallback: some View {
        VStack(spacing: 8) {
            TextField("Paste a CKB address", text: $pastedText)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .accessibilityIdentifier("scanner.pasteField")

            Button("Use address") {
                viewModel.submitPasted(pastedText)
            }
            .buttonStyle(.borderedProminent)
            .disabled(pastedText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .frame(maxWidth: .infinity)
            .accessibilityIdentifier("scanner.useButton")
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

/// Hosts an `AVCaptureVideoPreviewLayer` in SwiftUI. Kept to layout only:
/// all camera state lives in `QrScannerViewModel` / `AVFoundationQrScanner`.
/// `onLayout` reports the view's real, laid-out bounds every time they
/// change, which is the only point at which converting a rect through the
/// preview layer's coordinate space is meaningful.
private struct CameraPreviewRepresentable: UIViewRepresentable {
    let previewLayer: AVCaptureVideoPreviewLayer
    let onLayout: (CGRect) -> Void

    func makeUIView(context: Context) -> PreviewContainerView {
        previewLayer.videoGravity = .resizeAspectFill
        let view = PreviewContainerView()
        view.previewLayer = previewLayer
        view.onLayout = onLayout
        return view
    }

    func updateUIView(_ uiView: PreviewContainerView, context: Context) {
        uiView.previewLayer = previewLayer
        uiView.onLayout = onLayout
    }
}

private final class PreviewContainerView: UIView {
    var previewLayer: AVCaptureVideoPreviewLayer? {
        didSet {
            oldValue?.removeFromSuperlayer()
            if let previewLayer {
                layer.addSublayer(previewLayer)
                previewLayer.frame = bounds
            }
        }
    }

    var onLayout: ((CGRect) -> Void)?

    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer?.frame = bounds
        onLayout?(bounds)
    }
}
