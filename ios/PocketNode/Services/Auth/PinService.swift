import Foundation
import PocketNodeCore

/// Why a PIN operation could not be carried out. A wrong PIN is not an error,
/// it is `verify` returning false.
enum PinServiceError: Error, Equatable {
    /// Not exactly ``PinService/pinLength`` ASCII digits. Rejected before any
    /// hashing, so a malformed entry never costs an Argon2id pass.
    case invalidFormat
    /// The Keychain refused a write, so the PIN was not stored. Never swallowed:
    /// a user who believes they set a PIN and did not is worse off than one who
    /// sees the failure.
    case storeUnavailable(OSStatus)
}

/// The Argon2id cost, as a `Sendable` Swift value.
///
/// The Kotlin `Argon2id.Params` is a bridged Objective-C object and cannot cross
/// into an actor, so the cost travels as plain integers and the Kotlin value is
/// built on the far side.
struct Argon2Cost: Equatable, Sendable {
    let iterations: Int32
    let memoryKib: Int32
    let parallelism: Int32
    let tagLength: Int32

    /// The OWASP ASVS 4.0.3 baseline the shared policy defaults to: t=3,
    /// m=64 MiB, p=4, 32-byte tag. Read from the Kotlin companion rather than
    /// retyped, so the two platforms cannot drift into deriving different
    /// hashes from the same PIN.
    @MainActor
    static let production = Argon2Cost(
        iterations: PinPolicy.companion.ARGON2_ITERATIONS,
        memoryKib: PinPolicy.companion.ARGON2_MEMORY_KIB,
        parallelism: PinPolicy.companion.ARGON2_PARALLELISM,
        tagLength: PinPolicy.companion.HASH_OUTPUT_BYTES
    )
}

/// A snapshot of the policy's failure state, cheap to carry across the actor
/// boundary in one hop rather than five.
struct PinState: Equatable, Sendable {
    /// Read from the store rather than from `PinPolicy.hasPin()`, which cannot
    /// tell an unreadable store from an empty one: it reports both as no PIN.
    var presence: PinPresence
    var remainingAttempts: Int32
    var isLockedOut: Bool
    var lockoutRemainingMs: Int64
    var isPermanentlyLocked: Bool

    /// True only when a PIN is known to be stored. An unreadable store is not
    /// "no PIN", so callers deciding whether to gate must use ``presence``.
    var hasPin: Bool { presence == .present }
}

/// Owns the Kotlin `PinPolicy` and every call into it.
///
/// An actor for two reasons. Argon2id at production cost is roughly 150 ms in a
/// release build and over a second in a debug one, which has no business on the
/// main actor; and `PinPolicy` is a read-modify-write over six Keychain items,
/// so two concurrent verifies must not interleave and lose a failure count.
///
/// Everything crossing the boundary is `Sendable`: PINs travel as `[UInt8]`,
/// results as `Bool` and ``PinState``. The Kotlin objects never leave.
actor PinPolicyActor {
    private let store: KeychainPinStore
    private let policy: PinPolicy

    /// - Parameters:
    ///   - keychain: storage for the six policy fields. Tests pass a throwaway
    ///     service so they never touch the installed app's PIN.
    ///   - cost: Argon2id parameters. Tests lower these; production must not.
    ///   - clock: epoch milliseconds, matching Android's
    ///     `System.currentTimeMillis`. Injectable so the lockout schedule can be
    ///     driven without waiting out real time.
    init(
        keychain: any KeyValueStoring = KeychainStore(service: KeychainPinStore.defaultService),
        cost: Argon2Cost,
        clock: @escaping @Sendable () -> Int64 = PinPolicyActor.systemClock
    ) {
        let store = KeychainPinStore(keychain: keychain)
        self.store = store
        self.policy = PinPolicy(
            store: store,
            entropy: SecureRandomEntropySource(),
            clock: { KotlinLong(longLong: clock()) },
            logger: NSLogLogger(),
            argon2Params: Argon2id.Params(
                iterations: cost.iterations,
                memoryKib: cost.memoryKib,
                parallelism: cost.parallelism,
                tagLength: cost.tagLength
            )
        )
    }

    /// Wall-clock epoch milliseconds. Same epoch as Android's
    /// `System.currentTimeMillis`, so a lockout stamp means the same thing on
    /// both platforms. It is wall clock, not monotonic, on purpose: the stamp
    /// has to survive a process restart, which a monotonic clock does not.
    static let systemClock: @Sendable () -> Int64 = {
        Int64((Date().timeIntervalSince1970 * 1000).rounded())
    }

    func setPin(digits: [UInt8]) throws {
        let bytes = KotlinByteArray.from(digits)
        defer { bytes.zeroOut() }
        store.clearFailure()
        policy.setPin(pinBytes: bytes)
        if let failure = store.takeFailure() {
            throw PinServiceError.storeUnavailable(failure.status)
        }
    }

    /// Verifies `digits`, refusing to even hash them if the store cannot record
    /// the outcome.
    ///
    /// - Throws: ``PinServiceError/storeUnavailable(_:)`` when the store cannot
    ///   be read or written well enough to make the attempt meaningful. Three
    ///   checks run before a single byte is hashed, and every one of them fails
    ///   closed:
    ///
    ///   1. **Presence.** An unreadable store must not reach the policy, which
    ///      would read the missing hash as "no PIN is set" and answer false.
    ///   2. **Writability.** A store that takes reads but refuses writes would
    ///      let every failed attempt go unrecorded, so the counter would never
    ///      reach a lockout and the guessing would be unlimited.
    ///   3. **Readability of the hash and salt.** A transient failure on the
    ///      salt alone is the worst of the three: the policy would mint a
    ///      replacement and destroy the only salt the stored hash matches.
    ///
    ///   A refused write after the comparison is surfaced the same way, even
    ///   for a PIN that matched, because an attempt that was not recorded is
    ///   not an attempt.
    func verify(digits: [UInt8]) throws -> Bool {
        switch store.presence() {
        case .unknown:
            throw PinServiceError.storeUnavailable(errSecInteractionNotAllowed)
        case .absent:
            // Nothing to verify against. Not an error, just false, and no
            // hashing and no attempt spent.
            return false
        case .present:
            break
        }

        if let failure = store.probeWrite() {
            throw PinServiceError.storeUnavailable(failure.status)
        }
        if let failure = store.probeCriticalReads() {
            throw PinServiceError.storeUnavailable(failure.status)
        }

        let bytes = KotlinByteArray.from(digits)
        defer { bytes.zeroOut() }
        store.clearFailure()
        let matched = policy.verify(pinBytes: bytes)

        if let failure = store.takeFailure() {
            // The probe passed and a real write still failed, so the attempt
            // may not have been counted. Refusing is the only safe answer,
            // even for a PIN that matched.
            throw PinServiceError.storeUnavailable(failure.status)
        }
        return matched
    }

    /// Clears the PIN.
    ///
    /// - Throws: ``PinServiceError/storeUnavailable(_:)`` if any of the deletes
    ///   was refused. A PIN that is still stored must not be reported as gone:
    ///   the caller would drop the lock in front of it.
    func removePin() throws {
        store.clearFailure()
        policy.removePin()
        if let failure = store.takeFailure() {
            throw PinServiceError.storeUnavailable(failure.status)
        }
    }

    func snapshot() -> PinState {
        PinState(
            presence: store.presence(),
            remainingAttempts: policy.getRemainingAttempts(),
            isLockedOut: policy.isLockedOut(),
            lockoutRemainingMs: policy.getLockoutRemainingMs(),
            isPermanentlyLocked: policy.isPermanentlyLocked()
        )
    }
}

/// The app PIN, mirroring Android's `PinManager` surface.
///
/// The hashing and the lockout schedule are the shared Kotlin `PinPolicy`, so
/// the same PIN produces the same Argon2id hash and the same escalation
/// (30 s at 5 failures, then 1 min, 5 min, 30 min, 1 h, permanent at 10) on
/// both platforms. What lives here is the iOS half: the `String` entry points
/// the UI calls, the format check, the published state SwiftUI observes, and
/// keeping Argon2id off the main actor.
@MainActor
@Observable
final class PinService {
    /// Read from the shared policy so the pad and the validator cannot disagree
    /// with the hashing code about how long a PIN is.
    static let pinLength = Int(PinPolicy.companion.PIN_LENGTH)

    /// Failures allowed before the first lockout. Same source as the schedule
    /// that enforces it, so the "N attempts remaining" copy cannot drift.
    static let maxAttempts = Int(PinPolicy.companion.MAX_ATTEMPTS)

    /// Latest known failure state. Refreshed after every operation, and by
    /// ``refresh()`` while a lockout countdown is on screen.
    private(set) var state: PinState

    private let policy: PinPolicyActor

    /// Convenience accessors so views do not reach through `state`.
    ///
    /// ``pinPresence`` is the one gates must use: ``hasPin`` is false for an
    /// unreadable store as well as an empty one.
    var pinPresence: PinPresence { state.presence }
    var hasPin: Bool { state.hasPin }
    var remainingAttempts: Int { Int(state.remainingAttempts) }
    var isLockedOut: Bool { state.isLockedOut }
    var lockoutRemainingMs: Int64 { state.lockoutRemainingMs }
    var isPermanentlyLocked: Bool { state.isPermanentlyLocked }

    /// Whole seconds left on the current lockout, rounded up so the label never
    /// shows 0 while the user is still locked out.
    var lockoutRemainingSeconds: Int {
        Int((state.lockoutRemainingMs + 999) / 1000)
    }

    init(
        keychain: any KeyValueStoring = KeychainStore(service: KeychainPinStore.defaultService),
        cost: Argon2Cost = .production,
        clock: @escaping @Sendable () -> Int64 = PinPolicyActor.systemClock
    ) {
        self.policy = PinPolicyActor(keychain: keychain, cost: cost, clock: clock)
        // Seeded synchronously from a non-prompting Keychain lookup so the lock
        // gate knows on the first frame whether to gate, instead of flashing
        // the wallet while an async read lands.
        self.state = PinState(
            presence: KeychainPinStore.pinPresence(keychain: keychain),
            remainingAttempts: PinPolicy.companion.MAX_ATTEMPTS,
            isLockedOut: false,
            lockoutRemainingMs: 0,
            isPermanentlyLocked: false
        )
    }

    /// Re-reads the failure state. Cheap (six Keychain reads, no KDF), so the
    /// lockout countdown can call it on a timer.
    func refresh() async {
        state = await policy.snapshot()
    }

    /// Stores `pin`, clearing any previous failure state.
    ///
    /// - Throws: ``PinServiceError/invalidFormat`` before any hashing if `pin`
    ///   is not exactly ``pinLength`` ASCII digits, or
    ///   ``PinServiceError/storeUnavailable(_:)`` if the Keychain refused.
    ///
    /// A refused write is not left half applied. The hash is the last field
    /// written (see `KeychainPinStore.apply`), so a failure means either
    /// nothing landed or a hash landed beside a field that did not, such as its
    /// salt. Both are cleaned up by removing the PIN outright; if that cleanup
    /// is refused too, the refreshed ``pinPresence`` still reports the hash
    /// that is really there, so the app locks rather than believing it has no
    /// PIN.
    func setPin(_ pin: String) async throws {
        guard var digits = Self.asciiDigits(pin) else { throw PinServiceError.invalidFormat }
        defer { Self.zero(&digits) }
        do {
            try await policy.setPin(digits: digits)
        } catch {
            // Best effort: if this is refused too the hash is still there, and
            // the refresh below is what reports that honestly.
            try? await policy.removePin()
            await refresh()
            throw error
        }
        await refresh()
    }

    /// True if `pin` matches. A mismatch records a failure and may start the
    /// next lockout; a match clears the counter. Malformed input returns false
    /// without hashing and without counting as an attempt, so a UI bug cannot
    /// lock a user out.
    ///
    /// - Throws: ``PinServiceError/storeUnavailable(_:)`` when the store cannot
    ///   record the attempt. Nothing is hashed in that case, so a broken store
    ///   buys an attacker no guesses at all.
    func verify(_ pin: String) async throws -> Bool {
        guard var digits = Self.asciiDigits(pin) else { return false }
        defer { Self.zero(&digits) }
        do {
            let result = try await policy.verify(digits: digits)
            await refresh()
            return result
        } catch {
            await refresh()
            throw error
        }
    }

    /// Clears the PIN, its salt, its KDF version and all failure state.
    ///
    /// - Throws: ``PinServiceError/storeUnavailable(_:)`` if a delete was
    ///   refused, in which case ``pinPresence`` still reports the PIN that is
    ///   really there. The published state is refreshed either way, so a caller
    ///   that ignores the error still sees the truth.
    func removePin() async throws {
        do {
            try await policy.removePin()
        } catch {
            await refresh()
            throw error
        }
        await refresh()
    }

    // `PinPolicy.resetFailedAttempts` (the #370 post-upgrade reset) is
    // deliberately not surfaced yet: iOS has no upgrade path to run it on
    // before the first App Store build, and its Kotlin signature is still
    // moving. M2's onboarding issue picks it up.

    /// `pin` as its ASCII bytes, or nil unless it is exactly ``pinLength``
    /// characters and every one of them is `0`...`9`.
    ///
    /// Rejecting here rather than in the view keeps the rule in one place: the
    /// shared policy would throw on a malformed PIN, and a throw out of Kotlin
    /// arrives as a fatal error on iOS rather than something catchable.
    static func asciiDigits(_ pin: String) -> [UInt8]? {
        guard pin.count == pinLength else { return nil }
        var digits = [UInt8]()
        digits.reserveCapacity(pinLength)
        for character in pin.unicodeScalars {
            guard character.value >= 48, character.value <= 57 else { return nil }
            digits.append(UInt8(character.value))
        }
        guard digits.count == pinLength else { return nil }
        return digits
    }

    /// Overwrites a digit buffer in place.
    ///
    /// Scrubs what this code owns. It cannot scrub the `String` the view passed
    /// in: Swift strings are immutable and may already have been copied by the
    /// time they arrive, so the plaintext PIN can still sit in a released heap
    /// buffer until it is reused. Six digits of entropy in a transient
    /// allocation is a smaller exposure than the alternative, which would be
    /// threading a mutable byte buffer through SwiftUI bindings.
    static func zero(_ digits: inout [UInt8]) {
        digits.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return }
            memset_s(base, raw.count, 0, raw.count)
        }
    }
}
