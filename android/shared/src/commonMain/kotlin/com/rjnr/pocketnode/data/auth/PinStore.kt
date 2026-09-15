package com.rjnr.pocketnode.data.auth

/**
 * Persistence seam for [PinPolicy].
 *
 * The PIN hash, its salt, its KDF version and the three failure-tracking fields
 * are the whole of the policy's state. `commonMain` cannot reach a keystore, so
 * each platform supplies the storage: Android backs this with the existing
 * `EncryptedSharedPreferences` (`ckb_pin_prefs`), iOS with the keychain.
 *
 * Implementations MUST survive an app upgrade / overwrite install. The failure
 * counter deliberately persists across upgrades (#370): if reinstalling cleared
 * it, an attacker could sideload a build to reset the count and keep
 * brute-forcing. The recovery path when attempts run out is "reset and restore
 * from seed", not a counter reset.
 *
 * A missing field reads back as `null` (or `0` for [getFailedAttempts]); a
 * `null` passed to an [Editor] setter removes the field.
 */
interface PinStore {

    /** The stored PIN hash as lowercase hex, or null if no PIN is set. */
    fun getPinHash(): String?

    /** The stored salt, or null if none has been generated yet. */
    fun getSalt(): ByteArray?

    /** The KDF version the stored hash was derived with, or null if never written. */
    fun getKdfVersion(): Int?

    /** Cumulative failed attempts since the last success or `setPin`. Never null; 0 when unset. */
    fun getFailedAttempts(): Int

    /** Epoch-millis of the most recent failure, or null if there has been none. */
    fun getLastFailedAt(): Long?

    /** Epoch-millis the current lockout ends, or null if there is no lockout. */
    fun getLockoutUntil(): Long?

    /**
     * Applies [block] as a single write.
     *
     * Every field the block touches must land together: a partial write that
     * recorded a lockout without its attempt count (or the reverse) would let a
     * crash mid-verify either strand the user or hand an attacker free guesses.
     */
    fun update(block: Editor.() -> Unit)

    /** Collects the field changes of one [PinStore.update]. `null` removes the field. */
    interface Editor {
        fun pinHash(v: String?)
        fun salt(v: ByteArray?)
        fun kdfVersion(v: Int?)
        fun failedAttempts(v: Int?)
        fun lastFailedAt(v: Long?)
        fun lockoutUntil(v: Long?)
    }
}

/**
 * Clears the attempt counter, the last-failure stamp and any active lockout,
 * leaving the PIN itself alone.
 *
 * Shared by every caller that restores a clean slate (a successful verify, a
 * fresh `setPin`, the #370 post-upgrade reset) so the three fields cannot drift
 * apart. It is a top-level extension rather than a [PinPolicy] method because a
 * platform adapter may need to apply the same reset through a more durable
 * write than [PinStore.update] gives it.
 */
fun PinStore.Editor.clearFailureState() {
    failedAttempts(0)
    lastFailedAt(null)
    lockoutUntil(null)
}
