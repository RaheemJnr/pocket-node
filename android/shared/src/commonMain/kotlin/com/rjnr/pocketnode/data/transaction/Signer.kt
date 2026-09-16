package com.rjnr.pocketnode.data.transaction

import com.rjnr.pocketnode.core.crypto.Secp256k1Signer
import com.rjnr.pocketnode.data.wallet.WalletDerivation

/**
 * Produces the 65-byte recoverable secp256k1 signature a CKB witness carries.
 *
 * The seam exists so the send path can be handed the ability to sign without
 * being handed the key (M3 #5). Everything above [TransactionBuilder] already
 * passed a `ByteArray` through several frames; a Signer narrows that to one
 * object whose only capability is signing, which is what the iOS Secure
 * Enclave and a future hardware signer can also implement.
 *
 * The contract is exactly what [Secp256k1Signer.signRecoverable] returns:
 * 65 bytes, `r[32] || s[32] || v[1]`, over an already-hashed 32-byte message.
 * An implementation that hashes the message again, or that answers a DER
 * signature, produces a transaction the network rejects.
 *
 * ## What this seam does NOT cover
 *
 * [TransactionBuilder.signSweep] is still key-map based and takes no Signer.
 * A sweep spans multiple secp256k1 lock groups and signs a DIFFERENT message
 * per group, so it needs one key per group (`Map<lockArgs, ByteArray>`) rather
 * than the single signing identity this interface models. Widening it to a
 * `Map<String, Signer>` is a later change; until then, gap-limit sweeps hold
 * raw keys and wipe them themselves.
 */
interface Signer {

    /**
     * Sign [messageHash] (32 bytes, already the CKB signing message) and
     * answer `r[32] || s[32] || v[1]`.
     */
    fun signRecoverable(messageHash: ByteArray): ByteArray
}

/**
 * The signer the app ships: a raw secp256k1 private key held in memory.
 *
 * It does NOT copy the key. The caller owns the array's lifetime and is the
 * one that zeroes it (`SendViewModel` does so in a `finally`), so wrapping a
 * key in one of these does not transfer ownership. [close] exists for the
 * callers that DO own the key and want the wipe to travel with the signer.
 */
class PrivateKeySigner(private val privateKey: ByteArray) : Signer {

    override fun signRecoverable(messageHash: ByteArray): ByteArray =
        Secp256k1Signer.signRecoverable(messageHash, privateKey)

    /**
     * The lock args (`0x` + 20 bytes of blake160) this key signs for.
     *
     * Lets a caller check that the signer it holds matches the sender address
     * it is about to build for, without reaching back to the key.
     */
    fun lockArgs(): String = WalletDerivation.lockScript(
        WalletDerivation.publicKey(privateKey)
    ).args

    /**
     * Zero-fill the key.
     *
     * The signer is unusable afterwards and says so: the all-zero scalar is not
     * a valid secp256k1 private key, so [signRecoverable] throws
     * `IllegalArgumentException` rather than returning a signature over a
     * degenerate key.
     */
    fun close() {
        privateKey.fill(0)
    }
}
