import SwiftUI
import UIKit

/// Shows the active wallet's receive address for the selected network: QR
/// code, monospaced wrapping address, copy (with haptic), share, and — when
/// the wallet is a not-yet-backed-up mnemonic wallet — a nudge to back it up
/// first.
///
/// Self-contained like `BackupView`: every dependency comes through
/// `viewModel`, whose `onBackUp` closure is the only way this view reaches
/// outward. A parent screen wires that closure to `BackupView`'s navigation
/// without this view knowing `RootView` or `AppContainer` exist.
struct ReceiveView: View {
    let viewModel: ReceiveViewModel

    @State private var didCopy = false
    @State private var qrImage: UIImage?

    var body: some View {
        VStack(spacing: 20) {
            Text("Receive CKB")
                .font(.title2.weight(.semibold))

            qrCard

            Text(viewModel.networkHeading)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("receive.networkHeading")

            Text(viewModel.address.isEmpty ? "Loading..." : viewModel.address)
                .font(.footnote.monospaced())
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
                .padding(.horizontal, 12)
                .accessibilityIdentifier("receive.address")

            copyButton

            if !viewModel.address.isEmpty {
                ShareLink(item: viewModel.address) {
                    Label("Share", systemImage: "square.and.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("receive.share")
            }

            Text("Share this address to receive CKB tokens")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            Spacer(minLength: 0)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .task(id: viewModel.address) {
            qrImage = QrCodeGenerator.image(for: QrCodeGenerator.payload(for: viewModel.address))
        }
        .onAppear {
            viewModel.refresh()
        }
        .alert(
            "Protect your wallet",
            isPresented: Binding(
                get: { viewModel.showBackupPrompt },
                set: { presented in if !presented { viewModel.dismissBackupPrompt() } }
            )
        ) {
            Button("Back up now") { viewModel.backUpNow() }
            Button("Not now", role: .cancel) { viewModel.dismissBackupPrompt() }
        } message: {
            Text(viewModel.backupPromptMessage)
        }
        // See `HomeView`: without this the identifier would be pushed down
        // onto the copy button, the address and the QR code, replacing theirs.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("receive.root")
    }

    private var qrCard: some View {
        Group {
            if let qrImage {
                Image(uiImage: qrImage)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 220, height: 220)
                    .accessibilityLabel("QR code")
            } else if viewModel.address.isEmpty {
                // No wallet record yet: there is nothing to generate a code
                // for, so say that rather than spinning forever waiting on a
                // QR image that will never arrive.
                Text("No wallet yet")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(width: 220, height: 220)
                    .accessibilityIdentifier("receive.noWallet")
            } else {
                ProgressView()
                    .frame(width: 220, height: 220)
            }
        }
        .padding(16)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.gray.opacity(0.2)))
        .accessibilityIdentifier("receive.qr")
    }

    private var copyButton: some View {
        Button {
            copyAddress()
        } label: {
            Label(didCopy ? "Copied" : "Copy Address", systemImage: didCopy ? "checkmark" : "doc.on.doc")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .disabled(viewModel.address.isEmpty)
        .accessibilityIdentifier("receive.copy")
    }

    private func copyAddress() {
        guard !viewModel.address.isEmpty else { return }
        UIPasteboard.general.string = viewModel.address
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        didCopy = true
        Task {
            try? await Task.sleep(for: .seconds(2))
            didCopy = false
        }
    }
}
