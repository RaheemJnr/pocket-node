import SwiftUI

/// The wallet shell's first screen: which wallet is open, where to receive
/// into, and the nag to back up a phrase that has never been written down.
///
/// Self-contained like `BackupView` and `ReceiveView`: state comes through
/// `model`, and the two closures are the only way out. `RootView` is what
/// knows those lead to `Route.receive` and `Route.backup`.
struct HomeView: View {
    let model: HomeViewModel
    let theme: Theme
    let onReceive: () -> Void
    let onBackUp: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            walletCard

            if model.needsBackup {
                backupBanner
            }

            receiveButton

            Spacer(minLength: 0)
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(theme.background)
        // Covers both the first draw and returning from a pushed screen, so a
        // backup finished on `BackupView` clears the banner on the way back.
        .onAppear { model.refresh() }
        // `children: .contain` first: an identifier on a plain container is
        // pushed down onto every element inside it, which would rename the
        // buttons and labels below to "home.root" and leave nothing
        // addressable. Making it a container element keeps both.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("home.root")
    }

    // MARK: - Wallet

    private var walletCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(model.walletName.isEmpty ? "Wallet" : model.walletName)
                .font(.headline)
                .accessibilityIdentifier("home.walletName")

            Text(model.address.isEmpty ? "No address yet" : model.shortAddress)
                .font(.footnote.monospaced())
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("home.address")

            Text("Balance and activity arrive in the next milestone. The embedded light client is already running underneath.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(theme.primary.opacity(0.35), lineWidth: 1)
        )
    }

    // MARK: - Backup nag

    /// Android shows the same standing reminder on Home until the phrase has
    /// been verified (`HomeScreen.kt`), because a wallet that can receive but
    /// cannot be restored is the one state a user cannot undo later.
    private var backupBanner: some View {
        Button(action: onBackUp) {
            HStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.title3)
                    .foregroundStyle(.orange)

                VStack(alignment: .leading, spacing: 4) {
                    Text("Back up your wallet")
                        .font(.headline)
                    Text("Your recovery phrase is the only way to restore this wallet. Write it down before you receive funds.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                }

                Spacer(minLength: 0)

                Image(systemName: "chevron.right")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
        }
        .buttonStyle(.plain)
        .background(Color.orange.opacity(0.14), in: RoundedRectangle(cornerRadius: 16))
        .accessibilityIdentifier("home.backupBanner")
    }

    // MARK: - Receive

    private var receiveButton: some View {
        Button(action: onReceive) {
            Label("Receive", systemImage: "qrcode")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .accessibilityIdentifier("home.receive")
    }
}
