import Foundation
import os
import PocketNodeCore

/// Where a send has got to, as a Swift value.
///
/// A mirror of the Kotlin `SendProgress` for the same reason `SyncStatus`
/// mirrors `SyncProgress`: the Kotlin object is not `Sendable` and SwiftUI
/// wants something it can compare.
struct SendStatus: Equatable {
    var phase: SendPhase = .idle
    var message: String = ""
    var confirmations: Int = 0
    var txHash: String?

    init(
        phase: SendPhase = .idle,
        message: String = "",
        confirmations: Int = 0,
        txHash: String? = nil
    ) {
        self.phase = phase
        self.message = message
        self.confirmations = confirmations
        self.txHash = txHash
    }

    init(_ progress: SendProgress) {
        self.init(
            phase: SendPhase(progress.state),
            message: progress.statusMessage,
            confirmations: Int(progress.confirmations),
            txHash: progress.txHash
        )
    }

    /// The status sheet is up for everything but ``SendPhase/idle``.
    var isActive: Bool { phase != .idle }

    /// Only a finished send may be finished with: while a transaction is in
    /// flight the sheet offers "Hide", which puts the sheet away and leaves
    /// the poll running.
    var isSettled: Bool { phase == .confirmed || phase == .failed }
}

/// The Swift half of the shared `SendState`.
enum SendPhase: Equatable {
    case idle
    case sending
    case pending
    case proposed
    case confirmed
    case failed

    init(_ state: SendState) {
        switch state {
        case SendState.sending: self = .sending
        case SendState.pending: self = .pending
        case SendState.proposed: self = .proposed
        case SendState.confirmed: self = .confirmed
        case SendState.failed: self = .failed
        default: self = .idle
        }
    }
}

/// Why a send did not happen.
///
/// ``isCancellation`` is the case the UI must stay silent about: the user
/// dismissed a Face ID or PIN prompt, which is an answer rather than a
/// failure. Everything else carries the mapped, user-facing ``message`` plus
/// the ``detail`` the raw reason came in as, which the failure alert shows in
/// monospace so a bug report can quote it.
struct SendError: Error, Equatable {
    let message: String
    let detail: String?
    let isCancellation: Bool

    init(message: String, detail: String? = nil, isCancellation: Bool = false) {
        self.message = message
        self.detail = detail
        self.isCancellation = isCancellation
    }

    /// The user dismissed an authentication prompt.
    static let cancelled = SendError(message: "", detail: nil, isCancellation: true)
}

/// What the Send screen needs from the send layer.
///
/// `SendService` owns a live pipeline, a database and the Secure Enclave, so a
/// test that only wants to check which alert is on screen goes through this
/// instead. The same reason `SyncStatusProviding` and `WalletRecordStoring`
/// exist.
@MainActor
protocol SendServicing: AnyObject {
    /// The status sheet's contents.
    var status: SendStatus { get }

    /// The wallet's address on the selected network, nil with no wallet.
    var fromAddress: String? { get }

    /// The network the wallet is on, which the address indicator compares
    /// against.
    var network: NetworkType { get }

    /// Spendable balance in shannons, as the sync layer last read it.
    var availableShannons: Int64 { get }

    /// The form's fee line: a guess at 1 input, before a plan exists.
    func estimateFee(inputCount: Int, outputCount: Int) -> Int64

    /// The MAX pill: the largest amount a single transfer can carry right now.
    func maxSendableShannons() async -> Int64

    /// Price the transfer the review sheet is about to show. Throws whatever
    /// selection throws, which the caller surfaces instead of opening the
    /// sheet.
    func preview(from: String, recipients: [RecipientOutput]) async throws -> TransferPlan

    /// Authenticate, sign and broadcast. Nothing is built before this is
    /// called.
    func send(from: String, to: String, amount: Int64, expectedFee: Int64) async -> Result<String, SendError>

    /// Re-broadcast a FAILED row's original signed bytes.
    func retry(txHash: String) async -> Result<String, SendError>

    /// Dismiss the status sheet.
    func dismissStatus()
}

/// The send path on iOS: the shared `SendPipeline`, the key step-up in front
/// of it, and the status poll behind it.
///
/// ## The order, and why it is that order
///
/// Nothing is built, priced against a key, or signed before the user has
/// confirmed the review sheet. On confirm the sequence is fixed:
///
/// 1. ``AuthGating/requireAuth(reason:)``, the app's own gate (PIN, or Face
///    ID when the user opted in). A refusal ends it here.
/// 2. `WalletKeyStore.load(reason:)`, the Secure Enclave unwrap, which is the
///    prompt the user actually sees over a send. It is second because the
///    first gate is the one the user configured and this one is the one the
///    system enforces; asking the system first would let a caller that failed
///    the app gate still raise a Face ID prompt.
/// 3. The private key is decoded into a Swift `[UInt8]`, copied into a
///    `KotlinByteArray`, and both are wiped in `defer`s that are registered
///    before the signer is ever used.
/// 4. `PrivateKeySigner` wraps the Kotlin array and is `close()`d in its own
///    `defer`, so the key is zeroed even if the broadcast throws.
///
/// The bundle's `privateKeyHex` is a Swift `String` and cannot be wiped:
/// `String` storage is copy-on-write and owned by the standard library. It is
/// therefore never stored on this object, only passed through the one
/// function below and dropped.
@MainActor
@Observable
final class SendService: SendServicing {

    private(set) var status = SendStatus()

    private let pipeline: SendPipeline
    private let poller: SendStatusPoller
    private let builder: TransactionBuilder
    private let sync: SyncService
    private let walletStore: WalletStore
    private let walletKeyStore: WalletKeyStore
    private let auth: any AuthGating
    private let preferences: UserDefaultsPreferences

    /// The scope the pipeline's post-broadcast re-register runs in. Owned for
    /// the life of this object, never one per send.
    private let scope: SharedScope

    private let logger = Logger(subsystem: "com.rjnr.pocketnode", category: "SendService")

    /// The balance as it was when the current send started, so the poller's
    /// "has it moved yet" question has something to compare against.
    ///
    /// In a box rather than a plain property because the comparison happens on
    /// a Kotlin thread. `SyncService.balanceBox` holds the other half, the
    /// latest published reading; the check is two lock-guarded loads and no
    /// actor hop at all.
    ///
    /// Android polls `refreshBalance` itself inside that loop. iOS does not
    /// need to: `SyncService` already re-reads the balance on every sync tick
    /// that moved the chain, and the change output only becomes visible when a
    /// block is synced, so there is nothing a second poke would find earlier.
    @ObservationIgnored private let balanceAtSend = BalanceBox(0)

    @ObservationIgnored private nonisolated(unsafe) var observation: Task<Void, Never>?

    /// Re-reads the balance on the poller's own cadence while a broadcast is
    /// being watched.
    ///
    /// Android does this inside its poll loop, between the status reads. iOS
    /// cannot: that loop runs on a Kotlin thread and `SyncService.refreshBalance`
    /// is main-actor isolated, so the tick lives beside the loop instead of
    /// inside it. Without it the poller's balance question could only ever be
    /// answered by whatever the sync poll happened to have read, which is the
    /// slower of the two cadences on a wallet that is already caught up.
    @ObservationIgnored private nonisolated(unsafe) var balanceTicker: Task<Void, Never>?

    init(
        sync: SyncService,
        walletStore: WalletStore,
        walletKeyStore: WalletKeyStore,
        auth: any AuthGating,
        preferences: UserDefaultsPreferences
    ) {
        self.sync = sync
        self.walletStore = walletStore
        self.walletKeyStore = walletKeyStore
        self.auth = auth
        self.preferences = preferences

        let runtime = SharedRuntime.shared
        let log = NSLogLogger()
        self.scope = SharedScope(context: runtime.defaultContext)
        self.builder = TransactionBuilder(networkValidator: NetworkValidator(), logger: log)
        // Every collaborator comes off `SyncService`: one database, one read
        // path, one coordinator. See its `ledger` declaration.
        self.pipeline = SendPipeline(
            lightClient: sync.api,
            transactionBuilder: builder,
            ledger: sync.ledger,
            pendingBroadcasts: sync.pendingBroadcasts,
            transactions: sync.transactions,
            syncEngine: sync.engine,
            syncCoordinator: sync.coordinator,
            uiPreferences: preferences,
            json: runtime.json,
            logger: log,
            clock: SystemClock.shared,
            queryContext: runtime.defaultContext
        )

        // Two lock-guarded loads, run on the Kotlin poll thread: the balance
        // the sync layer last published against the one this send started
        // from. `Sendable` because both boxes are. It only reports; the
        // re-reading is ``balanceTicker``'s job, because `refreshBalance` is
        // main-actor isolated and this closure is not.
        let latest = sync.balanceBox
        let baseline = balanceAtSend
        self.poller = SendStatusPoller(
            ledger: sync.ledger,
            balanceChangedSinceStart: {
                KotlinBoolean(bool: latest.get() != baseline.get())
            },
            logger: log,
            clock: SystemClock.shared,
            scopeContext: runtime.defaultContext
        )

        observe()
    }

    deinit {
        observation?.cancel()
        balanceTicker?.cancel()
    }

    // MARK: - Wallet identity

    var network: NetworkType { preferences.getSelectedNetwork() }

    var fromAddress: String? {
        guard let record = walletStore.load() else { return nil }
        return network == NetworkType.mainnet ? record.mainnetAddress : record.testnetAddress
    }

    var availableShannons: Int64 { sync.balance.shannons }

    // MARK: - Pricing

    func estimateFee(inputCount: Int, outputCount: Int) -> Int64 {
        builder.estimateTransferFee(inputCount: Int32(inputCount), outputCount: Int32(outputCount))
    }

    func maxSendableShannons() async -> Int64 {
        guard let from = fromAddress, let context = makeContext() else { return 0 }
        do {
            let largest = try await pipeline.maxSendable(ctx: context, fromAddress: from)
            return largest.int64Value
        } catch {
            // The same fallback Android takes when the cell fetch fails: the
            // displayed balance minus a one-input estimate. It can overshoot
            // on a fragmented wallet, which the send then refuses with a
            // message; quoting nothing at all would be worse.
            logger.error("maxSendable failed: \(error.localizedDescription, privacy: .public)")
            let fee = estimateFee(inputCount: 1, outputCount: 1)
            return Swift.max(availableShannons - fee, 0)
        }
    }

    func preview(from: String, recipients: [RecipientOutput]) async throws -> TransferPlan {
        guard let context = makeContext() else { throw SendError(message: "Wallet not initialized") }
        return try await pipeline.previewTransfer(
            ctx: context,
            fromAddress: from,
            recipients: recipients
        )
    }

    // MARK: - Send

    func send(
        from: String,
        to: String,
        amount: Int64,
        expectedFee: Int64
    ) async -> Result<String, SendError> {
        guard let context = makeContext() else {
            return .failure(SendError(message: "Wallet not initialized"))
        }
        guard await auth.requireAuth(reason: Self.authReason) else {
            return .failure(.cancelled)
        }

        poller.markBuilding()
        balanceAtSend.set(availableShannons)

        // The bundle is confined to this scope on purpose. It carries the
        // private key hex AND the mnemonic, both Swift `String`s, whose
        // copy-on-write storage the standard library owns and nothing here can
        // wipe. Holding it across the broadcast would keep the seed phrase of
        // the wallet alive on the stack for as long as a network call takes,
        // for no reason: the decoded bytes are the only part the send needs.
        var decoded: [UInt8]?
        do {
            let bundle = try await walletKeyStore.load(reason: Self.keyReason)
            decoded = WalletCreator.decodePrivateKey(bundle.privateKeyHex)
        } catch WalletKeyStoreError.authenticationCancelled {
            poller.reset()
            return .failure(.cancelled)
        } catch {
            poller.markFailed()
            return .failure(Self.keyFailure(error))
        }

        guard var keyBytes = decoded else {
            poller.markFailed()
            return .failure(
                SendError(
                    message: "Could not read this wallet's keys.",
                    detail: "stored key material is not a 32-byte secp256k1 scalar"
                )
            )
        }
        // `decoded` is released FIRST, and that is the whole point. It and
        // `keyBytes` are two references to one buffer, and a buffer with two
        // references is not uniquely referenced, so `wipe()`'s
        // `withUnsafeMutableBytes` would copy it, zero the copy, and leave the
        // real key sitting in `decoded` until the function returned. Dropping
        // this reference restores uniqueness so the wipe reaches the bytes
        // that matter. ``moveKeyBytes(_:)`` then copies into the Kotlin array
        // and zeroes the Swift side in one step.
        decoded = nil
        let privateKey = Self.moveKeyBytes(&keyBytes)
        let signer = PrivateKeySigner(privateKey: privateKey)
        // Registered before the key is used, so an error, a cancellation or a
        // throw out of the bridge all still wipe it. `close()` zeroes the
        // Kotlin array; `zeroOut()` is belt and braces for the case where the
        // signer is ever changed to copy.
        defer {
            signer.close()
            privateKey.zeroOut()
        }

        poller.markBroadcasting()
        do {
            let hash = try await pipeline.prepareAndSendOrThrow(
                ctx: context,
                fromAddress: from,
                toAddress: to,
                amountShannons: amount,
                signer: signer,
                expectedFeeShannons: KotlinLong(longLong: expectedFee)
            )
            poller.start(txHash: hash)
            return .success(hash)
        } catch {
            poller.markFailed()
            return .failure(Self.sendFailure(error))
        }
    }

    func retry(txHash: String) async -> Result<String, SendError> {
        guard let context = makeContext() else {
            return .failure(SendError(message: "Wallet not initialized"))
        }
        poller.markBroadcasting()
        balanceAtSend.set(availableShannons)
        do {
            let hash = try await pipeline.retryBroadcastOrThrow(ctx: context, txHash: txHash)
            poller.start(txHash: hash)
            return .success(hash)
        } catch {
            poller.markFailed()
            return .failure(Self.sendFailure(error))
        }
    }

    func dismissStatus() {
        poller.reset()
    }

    /// Release the poll and the scope the pipeline launches into.
    ///
    /// Nothing calls it today for the same reason `SyncService.shutdown()` is
    /// unused: iOS reclaims the process rather than unwinding it. It exists so
    /// that whatever owns a shutdown hook later has one call to make.
    func shutdown() {
        observation?.cancel()
        stopBalanceTicker(finalRead: false)
        poller.close()
        scope.close()
    }

    // MARK: - Internals

    /// Mirrors the Kotlin poller's flow onto this object's published status,
    /// and keeps ``balanceTicker`` running for exactly as long as there is a
    /// transaction being watched.
    private func observe() {
        let flow = poller.state
        observation = Task { @MainActor [weak self] in
            for await progress in flow {
                guard self != nil else { return }
                let next = SendStatus(progress)
                self?.status = next
                if next.txHash != nil, !next.isSettled {
                    self?.startBalanceTicker()
                } else {
                    self?.stopBalanceTicker(finalRead: next.isSettled)
                }
            }
        }
    }

    private func startBalanceTicker() {
        guard balanceTicker == nil else { return }
        balanceTicker = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                self?.sync.refreshBalance()
                try? await Task.sleep(for: .seconds(Self.balanceTickSeconds))
            }
        }
    }

    /// - Parameter finalRead: ask for one more balance after stopping, which
    ///   is what Android does when its poll loop ends. The block carrying the
    ///   change output is usually the one that just confirmed the send, so the
    ///   read right after the outcome is the one most likely to move it.
    private func stopBalanceTicker(finalRead: Bool) {
        balanceTicker?.cancel()
        balanceTicker = nil
        if finalRead { sync.refreshBalance() }
    }

    /// The poller's own cadence, so the two stay in step.
    private static let balanceTickSeconds = 3

    /// The wallet state one send is pinned to.
    ///
    /// Built per call rather than cached: the wallet and the network can
    /// change between two sends, and the whole point of `SendContext` is that
    /// one send never reads either of them live.
    private func makeContext() -> SendContext? {
        guard let record = walletStore.load(), let address = fromAddress else { return nil }
        let net = network
        guard let script = AddressUtils.shared.parseAddress(address: address) else { return nil }
        let flag = sync.syncingFlag
        return SendContext(
            network: net,
            walletId: record.id,
            activeScript: script,
            isSyncing: { KotlinBoolean(bool: flag.get()) },
            scope: scope.scope
        )
    }

    /// The prompt above the app's own gate.
    static let authReason = "Verify your identity to send CKB"

    /// The prompt the Secure Enclave shows when it unwraps the wallet key.
    static let keyReason = "Unlock your wallet to sign this transaction"

    /// Copies `bytes` into a `KotlinByteArray` and zeroes the Swift buffer.
    ///
    /// The zeroing only reaches the real bytes when `bytes` is uniquely
    /// referenced: `Array.withUnsafeMutableBytes` triggers copy-on-write
    /// otherwise, so `memset_s` would scrub a fresh copy while the key lived
    /// on wherever the other reference was. That is a caller's obligation, not
    /// something this function can check, which is why the one call site drops
    /// its other reference immediately before calling in. `internal` so
    /// `SendServiceKeyHandlingTests` can exercise both halves without a
    /// Keychain, and `nonisolated` because it touches nothing on this object,
    /// the same reason `WalletCreator`'s derivation helpers are.
    nonisolated static func moveKeyBytes(_ bytes: inout [UInt8]) -> KotlinByteArray {
        let copied = KotlinByteArray.from(bytes)
        bytes.wipe()
        return copied
    }

    /// A key-store failure, in words that say what the user can do.
    private static func keyFailure(_ error: Error) -> SendError {
        let detail = String(describing: error)
        switch error {
        case WalletKeyStoreError.authenticationFailed:
            return SendError(message: "Authentication failed: the wallet key was not unlocked.", detail: detail)
        case WalletKeyStoreError.notFound:
            return SendError(message: "Could not read this wallet's keys.", detail: detail)
        case WalletKeyStoreError.keyInvalidated:
            return SendError(
                message: "Could not read this wallet's keys. Restore it from your recovery phrase.",
                detail: detail
            )
        default:
            return SendError(message: "Could not read this wallet's keys.", detail: detail)
        }
    }

    /// A pipeline failure, through the shared mapping so both platforms say
    /// the same thing about the same rejection.
    private static func sendFailure(_ error: Error) -> SendError {
        let raw = (error as NSError).kotlinMessage ?? error.localizedDescription
        return SendError(message: SendCopyKt.mapSendErrorMessage(message: raw), detail: raw)
    }
}

extension NSError {
    /// The Kotlin exception message behind a bridged `NSError`.
    ///
    /// Kotlin/Native wraps a thrown `Throwable` in an `NSError` whose
    /// `KotlinException` user-info value is the original. `localizedDescription`
    /// on one of these reads "The operation couldn't be completed", which is
    /// exactly the string the shared error mapping cannot do anything with.
    var kotlinMessage: String? {
        guard let throwable = userInfo["KotlinException"] as? KotlinThrowable else { return nil }
        return throwable.message
    }
}
