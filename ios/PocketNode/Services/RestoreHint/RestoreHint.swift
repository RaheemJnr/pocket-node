import Foundation
import Observation
import PocketNodeCore

// The restore hint file (#559) on iOS: a thin layer over the shared
// `com.rjnr.pocketnode.data.restorehint` code (`RestoreHintCodec`,
// `RestoreHintPlanner`, `RestoreHintFormat`). The format, the HMAC and the
// start-height rule all live in the shared core, so a file exported by the
// Android app verifies here and the other way round. Nothing in this file
// changes the format; it only gathers the iOS inputs and turns the shared
// results into copy.

/// What the user reads. Mirrors Android's `restore_hint_*` strings.
enum RestoreHintCopy {
    static let exportTitle = "Export restore hint"
    static let exportExplainer = "A small file that helps a new phone restore this wallet faster. It holds no keys or addresses."
    static let exportSaved = "Restore hint saved"
    static let exportFailed = "Could not export the restore hint. Try again."
    static let exportNotSaved = "The restore hint was not saved. Try again."
    static let exportNoTip = "The node has not synced a block yet. Try again in a moment."

    static let useFile = "Use a restore hint file"
    static let pickFirst = "Optional. Add the file from your old phone before you enter your phrase or key."
    static let fileAdded = "Restore hint file added"
    static let remove = "Remove"
    static let readyTitle = "Restore hint"
    static let startRestore = "Start restore"

    static let notThisWallet = "This file does not belong to this recovery phrase."
    static let otherNetwork = "This file is for the other network."
    static let unreadable = "This file could not be read."

    /// "Restore will start at block N (the old phone synced from block M)."
    /// with the device's digit grouping.
    static func ready(startBlock: Int64, sourceCoverageStart: Int64) -> String {
        "Restore will start at block \(startBlock.formatted()) (the old phone synced from block \(sourceCoverageStart.formatted()))."
    }

    /// The reason `Face ID` and the PIN challenge show for an export.
    static let authReason = "Export restore hint"
}

/// Why a hint file was not used. Nothing from a rejected file is applied.
enum RestoreHintRejection: Equatable, Sendable {
    /// A bad MAC: another wallet's file, or an edited one.
    case notThisWallet
    /// Authentic, but made on the other network.
    case otherNetwork
    /// Not a hint, a version this build does not know, too large, or
    /// nothing in it the restored secret can use.
    case unreadable

    var message: String {
        switch self {
        case .notThisWallet: return RestoreHintCopy.notThisWallet
        case .otherNetwork: return RestoreHintCopy.otherNetwork
        case .unreadable: return RestoreHintCopy.unreadable
        }
    }
}

/// The start a verified hint asks for, as Swift values.
struct RestoreHintStart: Equatable, Sendable {
    /// The restored wallet's start (`RestoreHintPlan.startBlock`).
    let startBlock: Int64
    /// The lowest block the old phone covered, for the copy.
    let sourceCoverageStart: Int64

    /// Block 0 is applied as FULL_HISTORY, never CUSTOM 0: outside
    /// FULL_HISTORY a 0 start is replaced by the network checkpoint, which
    /// would start the restore later than the old phone covered. The same
    /// rule as `RestoreHintPlan.syncMode`.
    var usesFullHistory: Bool { startBlock == 0 }

    var syncMode: SyncMode { usesFullHistory ? .fullHistory : .custom }

    /// The custom height to apply, nil for FULL_HISTORY.
    var customHeight: Int64? { usesFullHistory ? nil : startBlock }

    var message: String {
        RestoreHintCopy.ready(startBlock: startBlock, sourceCoverageStart: sourceCoverageStart)
    }
}

/// The outcome of checking a hint file against a freshly entered secret.
enum RestoreHintVerification: Equatable, Sendable {
    case ready(RestoreHintStart)
    case rejected(RestoreHintRejection)
}

/// A private key's bytes, held by reference so a wipe reaches the one buffer
/// every holder shares. An `[UInt8]` passed by value and then wiped only
/// zeroes the callee's copy-on-write copy; this box is what lets the caller,
/// the background check and the secret builder all wipe the same bytes.
final class RestoreHintKeyBytes: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [UInt8]
    private var wiped = false

    init(_ bytes: [UInt8]) {
        storage = bytes
    }

    /// Reads the bytes, for building the Kotlin buffer.
    func withBytes<T>(_ body: ([UInt8]) -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body(storage)
    }

    var isWiped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return wiped
    }

    /// True when every byte held is zero.
    var isZeroed: Bool {
        withBytes { $0.allSatisfy { $0 == 0 } }
    }

    /// Zeroes the bytes in place. Safe to call more than once.
    func wipe() {
        lock.lock()
        defer { lock.unlock() }
        storage.wipe()
        wiped = true
    }
}

/// The secret a hint is checked with, as `Sendable` values so it can be
/// handed to a background task. Built from what the user just typed in.
enum RestoreHintMaterial: Sendable {
    /// Normalised BIP-39 words. The shared code derives the seed from them.
    /// Swift strings cannot be wiped; they are released after the check.
    case mnemonic([String])
    /// A 32-byte private key, for a raw-key wallet.
    case privateKey(RestoreHintKeyBytes)

    /// Zeroes the private key's bytes. Nothing to do for words.
    func wipe() {
        if case .privateKey(let key) = self { key.wipe() }
    }
}

/// Holds a shared `RestoreHintSecret` and records that it was wiped.
///
/// The Kotlin secret throws `IllegalStateException` once wiped, and a Kotlin
/// exception crossing into Swift without `@Throws` ends the process, so its
/// wiped state cannot be probed from here. This box is what says it, and the
/// verifier only ever wipes through it.
final class RestoreHintSecretBox {
    let secret: RestoreHintSecret
    private(set) var isWiped = false

    init(_ secret: RestoreHintSecret) {
        self.secret = secret
    }

    var kind: String { secret.kind }

    func wipe() {
        secret.wipe()
        isWiped = true
    }
}

/// Verifies a hint file with the shared codec and plans it with the shared
/// planner. Pure apart from the secret it wipes.
enum RestoreHintVerifier {
    /// Checks `fileText` against `secret` for `network`, then wipes the
    /// secret, whatever the outcome.
    static func verify(
        fileText: String,
        secret: RestoreHintSecretBox,
        network: NetworkType
    ) -> RestoreHintVerification {
        defer { secret.wipe() }
        let opened = RestoreHintCodec.shared.open(fileText: fileText, secret: secret.secret, expectedNetwork: network)
        if let valid = opened as? RestoreHintOpenResultValid {
            guard let plan = RestoreHintPlanner.shared.plan(payload: valid.payload, restoringKind: secret.kind) else {
                return .rejected(.unreadable)
            }
            return .ready(RestoreHintStart(startBlock: plan.startBlock, sourceCoverageStart: plan.sourceCoverageStart))
        }
        guard let rejected = opened as? RestoreHintOpenResultRejected else {
            return .rejected(.unreadable)
        }
        // `RestoreHintError` arrives as a Kotlin class (SKIE's enum interop is
        // off), so this compares rather than switches. MALFORMED and
        // UNSUPPORTED_VERSION, and anything added later, read as unreadable.
        if rejected.error == .badMac { return .rejected(.notThisWallet) }
        if rejected.error == .wrongNetwork { return .rejected(.otherNetwork) }
        return .rejected(.unreadable)
    }

    /// Builds the secret from `material`, checks the file and wipes it, off
    /// the main actor (the phrase path runs 2048 PBKDF2 rounds).
    nonisolated static func verify(
        fileText: String,
        material: RestoreHintMaterial,
        network: NetworkType
    ) async -> RestoreHintVerification {
        guard let box = makeSecret(material) else { return .rejected(.unreadable) }
        return verify(fileText: fileText, secret: box, network: network)
    }

    /// The shared secret for `material`. The private-key buffer handed to the
    /// shared code is zeroed here; the secret keeps its own copy until wiped.
    static func makeSecret(_ material: RestoreHintMaterial) -> RestoreHintSecretBox? {
        switch material {
        case .mnemonic(let words):
            return RestoreHintSecretBox(RestoreHintSecret.companion.fromMnemonic(words: words, passphrase: ""))
        case .privateKey(let key):
            // The secret keeps its own copy, so the shared bytes are wiped as
            // soon as the Kotlin buffer is built, whatever happens next.
            defer { key.wipe() }
            guard !key.isWiped, key.withBytes({ $0.count }) == 32 else { return nil }
            let buffer = key.withBytes { KotlinByteArray.from($0) }
            defer { buffer.zeroOut() }
            return RestoreHintSecretBox(RestoreHintSecret.companion.fromPrivateKey(privateKey: buffer))
        }
    }
}

/// Reads a picked hint file's text, and nothing more. Only the text is held
/// until the import; no secret exists while the picker is open.
enum RestoreHintFile {
    /// The shared cap: the codec refuses anything longer unread.
    static var maxChars: Int { Int(RestoreHintCodec.shared.MAX_FILE_CHARS) }

    /// The file's UTF-8 text, or nil when it cannot be read, is not UTF-8, or
    /// is larger than any real hint could be.
    static func read(url: URL) -> String? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        // A UTF-8 character is at most four bytes, so anything past this many
        // bytes is over the character cap too.
        if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > maxChars * 4 {
            return nil
        }
        guard let data = try? Data(contentsOf: url), data.count <= maxChars * 4 else { return nil }
        // UTF-16 code units, which is what Kotlin's `String.length` counts, so
        // this cap and the shared codec's agree on every file.
        guard let text = String(data: data, encoding: .utf8), text.utf16.count <= maxChars else { return nil }
        return text
    }
}

/// Builds the export payload for iOS's one wallet. Pure.
///
/// iOS has no sub-accounts or gap-limit discovery yet, so the payload carries
/// the main account (index 0) only and an empty discovery block, the shape an
/// Android wallet with no sub-accounts exports.
enum RestoreHintPayloadBuilder {
    /// - Parameters:
    ///   - lightStart: `sync_progress.lightStartBlockNumber`, or nil with no row.
    ///     Every registration rewrites it to the block it resumed from, so it is
    ///     never used alone.
    ///   - mode: the wallet's saved sync mode, or nil when it never chose one.
    ///   - blockNumbers: the wallet's cached transaction block numbers (hex).
    static func build(
        kind: String,
        network: NetworkType,
        lightStart: Int64?,
        mode: SyncMode?,
        customHeight: Int64?,
        tipHeight: Int64,
        tipHash: String,
        blockNumbers: [String],
        nowMs: Int64
    ) -> RestoreHintPayload {
        let coverage = coverageStart(
            lightStart: lightStart,
            mode: mode,
            customHeight: customHeight,
            tipHeight: tipHeight,
            network: network
        )
        let firstActivity = earliestActivity(blockNumbers).map {
            KotlinLong(longLong: RestoreHintFormat.shared.coarsen(height: $0))
        }
        let account = RestoreHintAccount(
            index: 0,
            coverageStart: RestoreHintFormat.shared.coarsen(height: coverage),
            firstActivity: firstActivity,
            syncMode: (mode ?? .theNewWallet).name
        )
        return RestoreHintPayload(
            v: RestoreHintFormat.shared.VERSION,
            network: network.name,
            createdAtMs: nowMs,
            tipHeight: tipHeight,
            tipHash: tipHash,
            kind: kind,
            accounts: [account],
            discovery: RestoreHintDiscovery(found: [], highestScanned: 0)
        )
    }

    /// `min(lightStart, the start the saved mode implies)`, FULL_HISTORY being
    /// 0, the same rule as Android's exporter. A wallet with no saved mode
    /// falls back to 0, as Android's root account does. Every step can only
    /// move the start earlier.
    static func coverageStart(
        lightStart: Int64?,
        mode: SyncMode?,
        customHeight: Int64?,
        tipHeight: Int64,
        network: NetworkType
    ) -> Int64 {
        let modeStart: Int64
        if let mode {
            modeStart = max(
                0,
                historicalStartBlock(
                    syncMode: mode,
                    customHeight: customHeight.map { KotlinLong(longLong: $0) },
                    tipHeight: tipHeight,
                    network: network
                )
            )
        } else {
            modeStart = 0
        }
        return max(0, min(lightStart ?? Int64.max, modeStart))
    }

    /// The lowest positive block among `blockNumbers` (hex, `0x` optional).
    static func earliestActivity(_ blockNumbers: [String]) -> Int64? {
        blockNumbers
            .compactMap { raw -> Int64? in
                let digits = raw.hasPrefix("0x") ? String(raw.dropFirst(2)) : raw
                return Int64(digits, radix: 16)
            }
            .filter { $0 > 0 }
            .min()
    }

    /// The file name the save sheet suggests, e.g. `pocket-node-restore-hint-testnet.json`.
    static func fileName(network: NetworkType) -> String {
        RestoreHintFormat.shared.fileName(network: network.name)
    }
}

/// A verified (or rejected) hint, waiting for the wallet's first sync choice.
struct PendingRestoreHint: Equatable, Sendable {
    /// The wallet the hint was verified for; Home applies it to no other.
    let walletId: String
    let verification: RestoreHintVerification
}

/// Carries a hint's outcome from the import screen to Home's sync choice.
///
/// Only the outcome travels: a start height and the block the old phone
/// covered, or a rejection. No secret and no file text. Memory only; a
/// process that dies before the choice simply drops it, and the user picks a
/// sync mode the ordinary way.
@MainActor
@Observable
final class RestoreHintHandoff {
    private(set) var pending: PendingRestoreHint?

    func deliver(_ hint: PendingRestoreHint) {
        pending = hint
    }

    func clear() {
        pending = nil
    }

    /// The pending hint, only if it was verified for `walletId`.
    func pending(for walletId: String?) -> PendingRestoreHint? {
        guard let pending, pending.walletId == walletId else { return nil }
        return pending
    }
}
