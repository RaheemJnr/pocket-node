import Foundation
import PocketNodeCore

/// Bridging between Kotlin's `ByteArray` and Swift's `Data`/`[UInt8]`.
///
/// Kotlin bytes are signed, Swift's are not, so every crossing is a bit-pattern
/// reinterpretation rather than a numeric conversion.
extension KotlinByteArray {
    static func from(_ bytes: [UInt8]) -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(bytes.count))
        for (offset, byte) in bytes.enumerated() {
            array.set(index: Int32(offset), value: Int8(bitPattern: byte))
        }
        return array
    }

    static func from(_ data: Data) -> KotlinByteArray {
        from([UInt8](data))
    }

    var data: Data {
        var out = Data(capacity: Int(size))
        for index in 0..<size {
            out.append(UInt8(bitPattern: get(index: index)))
        }
        return out
    }

    /// Overwrites every byte with zero. Used on anything that carried a PIN.
    func zeroOut() {
        for index in 0..<size {
            set(index: index, value: 0)
        }
    }
}

/// Keychain account names for the six ``PocketNodeCore/PinStore`` fields.
enum PinAccount {
    static let hash = "pin.hash"
    static let salt = "pin.salt"
    static let kdfVersion = "pin.kdfVersion"
    static let failedAttempts = "pin.failedAttempts"
    static let lastFailedAt = "pin.lastFailedAt"
    static let lockoutUntil = "pin.lockoutUntil"
}

/// Keychain-backed implementation of the shared `PinStore`.
///
/// The hashing, verification and lockout schedule all live in Kotlin
/// (`data/auth/PinPolicy.kt`, #510); this supplies only the storage, the way
/// Android's `PinManager` supplies `EncryptedSharedPreferences`. Its own
/// Keychain service keeps the PIN state apart from the wallet envelope, which
/// is written under `KeychainStore.defaultService` and needs the Secure Enclave.
///
/// Items inherit `KeychainStore`'s attributes:
/// `kSecAttrAccessibleWhenPasscodeSetThisDeviceOnly` and non-synchronizable, so
/// the failure counter is not restorable from a backup or another device. That
/// matters: the counter deliberately survives an app upgrade (#370) because an
/// attacker who could reset it by reinstalling would have unlimited guesses.
///
/// ## No transaction
///
/// The shared `PinStore.update` contract asks for one atomic write, and the
/// Keychain has no multi-item transaction: each field is its own
/// `SecItemAdd`/`SecItemUpdate`. ``apply(_:)`` therefore orders the writes so
/// that any prefix of them leaves a safe state rather than an exploitable or
/// unrecoverable one. See the comments there for the four rules.
///
/// ## Read failures
///
/// The `PinStore` getters cannot signal an error, so a Keychain read that fails
/// is reported as "absent" and recorded in ``lastFailure``. These items are
/// deleted outright by the system when the device passcode is removed, so
/// unreadable and absent are the same recoverable state in practice: the user
/// sets a PIN again. A write failure is not swallowed the same way, because
/// silently not storing a PIN the user believes they set would be worse;
/// ``takeFailure()`` lets `PinService` turn one into a thrown error.
final class KeychainPinStore: NSObject, PocketNodeCore.PinStore {
    /// Separate from `KeychainStore.defaultService`: nothing here is Enclave
    /// protected, and `InstallMarker`'s wipe of the key service must not sweep
    /// the PIN away as a side effect (`AppContainer` clears this one on the
    /// same signal, deliberately).
    static let defaultService = "com.rjnr.pocketnode.pin"

    private let keychain: any KeyValueStoring

    /// The most recent Keychain failure, or nil. Not thread safe on purpose:
    /// the only owner is `PinPolicyActor`, which serialises every access.
    private(set) var lastFailure: KeychainError?

    init(keychain: any KeyValueStoring = KeychainStore(service: KeychainPinStore.defaultService)) {
        self.keychain = keychain
    }

    /// Returns and clears ``lastFailure``, so a caller can scope it to one
    /// operation.
    func takeFailure() -> KeychainError? {
        defer { lastFailure = nil }
        return lastFailure
    }

    func clearFailure() {
        lastFailure = nil
    }

    /// Whether a PIN hash is present, without reading it out.
    ///
    /// `static` and synchronous so `PinService` can seed its published
    /// `hasPin` at init and the lock gate does not flash unlocked content on
    /// the first frame while an async read lands.
    static func hasStoredPin(keychain: any KeyValueStoring = KeychainStore(service: KeychainPinStore.defaultService)) -> Bool {
        (try? keychain.contains(account: PinAccount.hash)) ?? false
    }

    // MARK: - PinStore reads

    func getPinHash() -> String? {
        guard let data = read(PinAccount.hash) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func getSalt() -> KotlinByteArray? {
        guard let data = read(PinAccount.salt) else { return nil }
        return KotlinByteArray.from(data)
    }

    func getKdfVersion() -> KotlinInt? {
        guard let value = readInt32(PinAccount.kdfVersion) else { return nil }
        return KotlinInt(int: value)
    }

    /// Never nil: the shared contract says an unset counter reads as 0.
    func getFailedAttempts() -> Int32 {
        readInt32(PinAccount.failedAttempts) ?? 0
    }

    func getLastFailedAt() -> KotlinLong? {
        guard let value = readInt64(PinAccount.lastFailedAt) else { return nil }
        return KotlinLong(longLong: value)
    }

    func getLockoutUntil() -> KotlinLong? {
        guard let value = readInt64(PinAccount.lockoutUntil) else { return nil }
        return KotlinLong(longLong: value)
    }

    // MARK: - PinStore write

    func update(block: (any PocketNodeCore.PinStoreEditor) -> Void) {
        let edit = CollectingEditor()
        block(edit)
        apply(edit)
    }

    /// The shared store's "this one has to survive a kill" variant.
    ///
    /// Android needs it because `SharedPreferences.apply()` is asynchronous and
    /// a failure record could be lost to a process death; `commit()` is its
    /// durable form. The Keychain has no such split: `SecItemAdd` and
    /// `SecItemUpdate` have already hit the keychain database when they return,
    /// so every ``update(block:)`` is durable and this forwards to it.
    ///
    /// Declared explicitly rather than inherited: the Kotlin interface gives it
    /// a default body, and Kotlin default bodies are not visible through the
    /// Objective-C bridge, so a Swift conformer has to spell it out.
    func updateDurable(block: (any PocketNodeCore.PinStoreEditor) -> Void) {
        update(block: block)
    }

    /// Writes the fields one edit touched, in an order chosen so that every
    /// prefix of the sequence is a state the app can live with:
    ///
    /// 1. **A hash being removed goes first.** `removePin` clears the hash, the
    ///    salt and the KDF version together; a crash that had dropped the salt
    ///    but kept the hash would leave a PIN that can never verify again,
    ///    because the policy would mint a fresh salt and every entry would
    ///    mismatch straight into the escalating lockout.
    /// 2. **A lockout being applied goes before the counter.** The lockout is
    ///    the only field that actually gates `verify`; writing it first means an
    ///    interrupted failure record still stops the guessing.
    /// 3. **A lockout being cleared goes after the counter.** Same field, other
    ///    direction: the loosening write lands last, so an interruption leaves
    ///    the user waiting out a stale lockout rather than handing an attacker
    ///    free attempts.
    /// 4. **A hash being set goes last.** The new credential is only activated
    ///    once its salt, KDF version and cleared counter are all in place, so a
    ///    fresh PIN can never inherit the previous one's lockout.
    private func apply(_ edit: CollectingEditor) {
        let removingHash = edit.pendingPinHash.map { $0 == nil } ?? false
        let applyingLockout = edit.pendingLockoutUntil.map { $0 != nil } ?? false

        if removingHash {
            write(PinAccount.hash, nil)
        }
        if applyingLockout, let lockout = edit.pendingLockoutUntil {
            write(PinAccount.lockoutUntil, lockout.map(Self.encode))
        }
        if let salt = edit.pendingSalt {
            write(PinAccount.salt, salt)
        }
        if let kdfVersion = edit.pendingKdfVersion {
            write(PinAccount.kdfVersion, kdfVersion.map(Self.encode))
        }
        if let attempts = edit.pendingFailedAttempts {
            write(PinAccount.failedAttempts, attempts.map(Self.encode))
        }
        if let lastFailedAt = edit.pendingLastFailedAt {
            write(PinAccount.lastFailedAt, lastFailedAt.map(Self.encode))
        }
        if !applyingLockout, let lockout = edit.pendingLockoutUntil {
            write(PinAccount.lockoutUntil, lockout.map(Self.encode))
        }
        if !removingHash, let hash = edit.pendingPinHash, let hash {
            write(PinAccount.hash, Data(hash.utf8))
        }
    }

    // MARK: - Encoding
    //
    // Integers are big endian so a stored value is stable and inspectable
    // regardless of the host; strings are UTF-8; the salt is raw bytes.

    private static func encode(_ value: Int32) -> Data {
        withUnsafeBytes(of: value.bigEndian) { Data($0) }
    }

    private static func encode(_ value: Int64) -> Data {
        withUnsafeBytes(of: value.bigEndian) { Data($0) }
    }

    private func read(_ account: String) -> Data? {
        do {
            return try keychain.get(account: account)
        } catch let error as KeychainError {
            lastFailure = error
            return nil
        } catch {
            lastFailure = KeychainError(status: errSecInternalError)
            return nil
        }
    }

    private func readInt32(_ account: String) -> Int32? {
        guard let data = read(account), data.count == MemoryLayout<Int32>.size else { return nil }
        return Int32(bigEndian: data.withUnsafeBytes { $0.loadUnaligned(as: Int32.self) })
    }

    private func readInt64(_ account: String) -> Int64? {
        guard let data = read(account), data.count == MemoryLayout<Int64>.size else { return nil }
        return Int64(bigEndian: data.withUnsafeBytes { $0.loadUnaligned(as: Int64.self) })
    }

    private func write(_ account: String, _ data: Data?) {
        do {
            if let data {
                try keychain.set(data, account: account)
            } else {
                try keychain.delete(account: account)
            }
        } catch let error as KeychainError {
            lastFailure = error
        } catch {
            lastFailure = KeychainError(status: errSecInternalError)
        }
    }
}

/// Collects one `PinStore.update` block's field changes.
///
/// Every field is a double optional: the outer level records whether the block
/// touched the field at all, the inner one is the value, where nil removes it.
/// Property names are prefixed because Swift will not let a stored property and
/// a method share a base name, and the method names are fixed by the Kotlin
/// protocol.
private final class CollectingEditor: NSObject, PocketNodeCore.PinStoreEditor {
    private(set) var pendingPinHash: String??
    private(set) var pendingSalt: Data??
    private(set) var pendingKdfVersion: Int32??
    private(set) var pendingFailedAttempts: Int32??
    private(set) var pendingLastFailedAt: Int64??
    private(set) var pendingLockoutUntil: Int64??

    func pinHash(v: String?) {
        pendingPinHash = .some(v)
    }

    func salt(v: KotlinByteArray?) {
        pendingSalt = .some(v?.data)
    }

    func kdfVersion(v: KotlinInt?) {
        pendingKdfVersion = .some(v?.int32Value)
    }

    func failedAttempts(v: KotlinInt?) {
        pendingFailedAttempts = .some(v?.int32Value)
    }

    func lastFailedAt(v: KotlinLong?) {
        pendingLastFailedAt = .some(v?.int64Value)
    }

    func lockoutUntil(v: KotlinLong?) {
        pendingLockoutUntil = .some(v?.int64Value)
    }
}
