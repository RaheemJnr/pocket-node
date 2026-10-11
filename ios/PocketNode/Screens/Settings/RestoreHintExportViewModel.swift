import Foundation
import Observation
import PocketNodeCore
import SwiftUI
import UniformTypeIdentifiers

/// The light client's tip, the anchor every hint file records.
struct RestoreHintTip: Equatable, Sendable {
    let height: Int64
    let hash: String
}

/// The chain-side inputs of an export, read from the sync stack.
///
/// `SyncService` conforms; tests substitute a stub so an export can be built
/// without a Room database or a running node.
@MainActor
protocol RestoreHintSources: AnyObject {
    /// The network the sync stack (and so its database) is on.
    var restoreHintNetwork: NetworkType { get }
    /// The tip, or nil while the node has not reported one.
    func restoreHintTip() async -> RestoreHintTip?
    /// `sync_progress.lightStartBlockNumber` for the wallet, or nil with no row.
    func restoreHintLightStart(walletId: String) async -> Int64?
    /// The wallet's cached transaction block numbers (hex).
    func restoreHintBlockNumbers(walletId: String) async -> [String]
}

/// The file the save sheet writes: the sealed hint text, as JSON.
struct RestoreHintDocument: FileDocument, Equatable {
    static var readableContentTypes: [UTType] { [.json] }

    let text: String
    let fileName: String

    init(text: String, fileName: String) {
        self.text = text
        self.fileName = fileName
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents,
              let text = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.text = text
        self.fileName = configuration.file.filename ?? ""
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

/// Settings > "Export restore hint" (#559).
///
/// Exporting reads the wallet's secret, because the file is MAC'd with a key
/// derived from the seed (or the private key of a raw-key wallet), so it asks
/// for the same authentication as revealing the recovery phrase: the
/// `AuthGating` re-auth gate first, then the key store's own Face ID read.
///
/// The sealed file is held here, not in the view's `@State`: the save sheet
/// can outlive the Settings screen (a lock replaces the wallet stack, which
/// tears the screen down), and a file held by the view would be lost with it.
/// Held here, the sheet comes back when Settings does. A save that ends in
/// anything but a written file says so.
///
/// A lock or a background during the prompt or the key read discards the
/// export, the same generation guard `BackupViewModel.reveal()` uses.
@MainActor
@Observable
final class RestoreHintExportViewModel {
    private(set) var isExporting = false
    /// The sealed file waiting for the save sheet. Holds no key material.
    private(set) var pendingDocument: RestoreHintDocument?
    /// The last outcome worth a line under the row.
    private(set) var message: String?

    /// Drives the save sheet: up exactly while a file is waiting.
    var isPresentingSaver: Bool { pendingDocument != nil }

    private let walletKeyStore: any WalletKeyReading
    private let walletStore: any WalletRecordStoring
    private let auth: any AuthGating
    private let preferences: UserDefaultsPreferences
    private let sources: any RestoreHintSources
    private let onKeysUnusable: @MainActor () -> Void
    private let now: () -> Int64

    @ObservationIgnored private var stopGeneration = 0

    init(
        walletKeyStore: any WalletKeyReading,
        walletStore: any WalletRecordStoring,
        auth: any AuthGating,
        preferences: UserDefaultsPreferences,
        sources: any RestoreHintSources,
        onKeysUnusable: @escaping @MainActor () -> Void = {},
        now: @escaping () -> Int64 = { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }
    ) {
        self.walletKeyStore = walletKeyStore
        self.walletStore = walletStore
        self.auth = auth
        self.preferences = preferences
        self.sources = sources
        self.onKeysUnusable = onKeysUnusable
        self.now = now
    }

    /// Authenticates, reads the secret, seals the file and holds it for the
    /// save sheet. The secret is wiped once the file is sealed.
    func export() async {
        guard !isExporting, pendingDocument == nil else { return }
        isExporting = true
        message = nil
        defer { isExporting = false }

        let generation = currentGeneration
        guard let record = walletStore.load() else {
            message = RestoreHintCopy.exportFailed
            return
        }
        let network = sources.restoreHintNetwork
        guard let tip = await sources.restoreHintTip(), tip.height > 0 else {
            message = RestoreHintCopy.exportNoTip
            return
        }

        let granted = await auth.requireAuth(reason: RestoreHintCopy.authReason)
        guard granted, generation == currentGeneration else { return }

        let bundle: WalletKeyBundle
        do {
            bundle = try await walletKeyStore.load(reason: RestoreHintCopy.authReason)
        } catch {
            if let keyError = error as? WalletKeyStoreError, keyError.provesKeysUnusable {
                onKeysUnusable()
            }
            guard generation == currentGeneration else { return }
            message = RestoreHintCopy.exportFailed
            return
        }
        guard generation == currentGeneration else { return }

        guard let material = Self.material(for: record, bundle: bundle) else {
            message = RestoreHintCopy.exportFailed
            return
        }
        let inputs = ExportInputs(
            kind: record.type == WalletCreator.typeRawKey ? RestoreHintKind.shared.RAW_KEY : RestoreHintKind.shared.MNEMONIC,
            network: network,
            lightStart: await sources.restoreHintLightStart(walletId: record.id),
            mode: preferences.getSyncModeOrNull(network: network, walletId: record.id),
            customHeight: preferences.getCustomBlockHeight(network: network, walletId: record.id)?.int64Value,
            tipHeight: tip.height,
            tipHash: tip.hash,
            blockNumbers: await sources.restoreHintBlockNumbers(walletId: record.id),
            nowMs: now()
        )
        let sealed = await Self.seal(inputs, material: material)
        // A lock that landed while the file was sealed: the user is behind the
        // lock screen now, and an export they cannot see is not one to offer.
        guard generation == currentGeneration else { return }
        guard let sealed else {
            message = RestoreHintCopy.exportFailed
            return
        }
        pendingDocument = RestoreHintDocument(
            text: sealed,
            fileName: RestoreHintPayloadBuilder.fileName(network: network)
        )
    }

    /// The save sheet wrote the file.
    func saveSucceeded() {
        pendingDocument = nil
        message = RestoreHintCopy.exportSaved
    }

    /// The save sheet ended without writing: cancelled, or failed.
    func saveDidNotComplete() {
        guard pendingDocument != nil else { return }
        pendingDocument = nil
        message = RestoreHintCopy.exportNotSaved
    }

    /// The scene went to the background. Ends an export still in flight; a
    /// file already sealed stays, since it holds no key material.
    func onBackgrounded() {
        stopGeneration += 1
    }

    // MARK: - Sealing

    /// Everything the payload is built from, as `Sendable` values, so sealing
    /// (which derives the seed: 2048 PBKDF2 rounds) runs off the main actor.
    struct ExportInputs: Sendable {
        let kind: String
        let network: NetworkType
        let lightStart: Int64?
        let mode: SyncMode?
        let customHeight: Int64?
        let tipHeight: Int64
        let tipHash: String
        let blockNumbers: [String]
        let nowMs: Int64
    }

    /// Builds and seals the file, wiping the secret whatever happens. Nil if
    /// the secret could not be built.
    nonisolated static func seal(_ inputs: ExportInputs, material: RestoreHintMaterial) async -> String? {
        guard let secret = RestoreHintVerifier.makeSecret(material) else { return nil }
        defer { secret.wipe() }
        guard secret.kind == inputs.kind else { return nil }
        let payload = RestoreHintPayloadBuilder.build(
            kind: inputs.kind,
            network: inputs.network,
            lightStart: inputs.lightStart,
            mode: inputs.mode,
            customHeight: inputs.customHeight,
            tipHeight: inputs.tipHeight,
            tipHash: inputs.tipHash,
            blockNumbers: inputs.blockNumbers,
            nowMs: inputs.nowMs
        )
        return RestoreHintCodec.shared.seal(payload: payload, secret: secret.secret)
    }

    /// The secret to seal with: the phrase for a mnemonic wallet, the private
    /// key for a raw-key one. Nil when the bundle does not have it.
    static func material(for record: WalletRecord, bundle: WalletKeyBundle) -> RestoreHintMaterial? {
        if record.type == WalletCreator.typeRawKey {
            return WalletCreator.decodePrivateKey(bundle.privateKeyHex).map { .privateKey($0) }
        }
        guard let mnemonic = bundle.mnemonic else { return nil }
        let words = WalletCreator.splitPhrase(mnemonic)
        return words.isEmpty ? nil : .mnemonic(words)
    }

    private struct Generation: Equatable {
        let stop: Int
        let lock: Int
    }

    private var currentGeneration: Generation {
        Generation(stop: stopGeneration, lock: auth.lockGeneration)
    }
}
