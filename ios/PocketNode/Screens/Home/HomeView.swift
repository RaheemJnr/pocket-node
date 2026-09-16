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

    /// Whether the sync-mode sheet is up. Both the "choose" button on a wallet
    /// that has never synced and the "Change" link on one that has present it.
    @State private var isChoosingSyncMode = false

    var body: some View {
        VStack(spacing: 16) {
            walletCard

            if model.showsSync {
                syncCard
            }

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
        .sheet(isPresented: $isChoosingSyncMode) {
            SyncModeSheet(
                theme: theme,
                selected: model.syncMode,
                errorMessage: model.syncError
            ) { mode, height in
                await model.chooseSyncMode(mode, customBlockHeight: height)
            }
        }
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

    // MARK: - Sync

    /// Android's Home sync section, narrowed to one wallet: either an invitation
    /// to choose how far back to look, or the progress of the choice already
    /// made. The numbers and the wording are the same ones `HomeScreen.kt`
    /// shows, so a user moving between the two apps reads the same thing.
    private var syncCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Sync")
                    .font(.headline)

                Spacer(minLength: 0)

                if !model.needsSyncMode {
                    Button("Change") { isChoosingSyncMode = true }
                        .font(.footnote)
                        .accessibilityIdentifier("home.syncChangeButton")
                }
            }

            if model.needsSyncMode {
                chooseSyncPrompt
            } else if !model.hasSyncReading {
                waitingForFirstReading
            } else if model.syncStatus.isSyncing {
                syncProgress
            } else {
                syncedChip
            }

            if let error = model.syncError {
                syncError(error)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(theme.surface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(theme.primary.opacity(0.35), lineWidth: 1)
        )
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("home.syncCard")
    }

    private var chooseSyncPrompt: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Your phone checks the blockchain itself. Tell it how far back to look and it will start.")
                .font(.footnote)
                .foregroundStyle(.secondary)

            Button {
                isChoosingSyncMode = true
            } label: {
                Text("Choose how far back to sync")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("home.syncChooseButton")
        }
    }

    /// Between choosing a mode and the first poll landing.
    ///
    /// Two different states wear this face and they are worth telling apart. A
    /// registered wallet is waiting on peers, which resolves itself. An
    /// unregistered one means activation did not get as far as handing the
    /// light client the script, and something went wrong; the error row below
    /// carries the Retry.
    @ViewBuilder
    private var waitingForFirstReading: some View {
        if model.isRegistered {
            HStack(spacing: 8) {
                ProgressView()
                    .controlSize(.small)
                Text("Starting up, looking for peers")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .accessibilityIdentifier("home.syncStarting")
        } else {
            Text("Not registered yet")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("home.syncNotRegistered")
        }
    }

    private func syncError(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(message)
                .font(.footnote)
                .foregroundStyle(theme.error)
                .accessibilityIdentifier("home.syncError")

            Button("Retry") { model.retrySync() }
                .font(.footnote)
                .accessibilityIdentifier("home.syncRetryButton")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 4)
    }

    private var syncProgress: some View {
        let status = model.syncStatus
        return VStack(alignment: .leading, spacing: 8) {
            Text("Catching up from \(status.syncedToBlock.formatted()) to \(status.tipBlockNumber.formatted())")
                .font(.footnote)
                .accessibilityIdentifier("home.syncCatchingUp")

            ProgressView(value: status.fraction)
                .tint(theme.primary)

            HStack {
                Text("\(Int(status.percentage.rounded()))%")
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("home.syncPercentage")

                Spacer(minLength: 0)

                if !status.etaDisplay.isEmpty {
                    Text(status.etaDisplay)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("home.syncEta")
                }
            }
        }
    }

    private var syncedChip: some View {
        HStack(spacing: 6) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(theme.primary)
            Text("Synced")
                .font(.subheadline)
        }
        .accessibilityIdentifier("home.syncSynced")
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
