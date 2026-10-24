package com.rjnr.pocketnode.data.vault

import com.rjnr.pocketnode.data.crypto.KeyMaterial
import com.rjnr.pocketnode.data.database.entity.KeyMaterialEntity
import com.rjnr.pocketnode.data.database.entity.WalletEntity
import com.rjnr.pocketnode.data.database.entity.WalletVaultEntity
import java.security.MessageDigest

/** Internal issuance only; authenticated witnesses live solely within one foreground operation. */
class LegacyBackupProof internal constructor(
    internal val wallet: WalletEntity,
    internal val original: WalletVaultEntity?,
    internal val source: KeyMaterialEntity?,
    internal val legacySources: Map<String, Pair<KeyMaterialEntity?, KeyMaterialEntity?>>,
    internal val globalPreferences: List<String?>,
    internal var copies: Map<String, List<Copy?>>,
) : AutoCloseable {
    internal class Copy(val fingerprint: String, val material: KeyMaterial)
    internal var authenticatedSources: Map<KeyMaterialEntity, KeyMaterial> = emptyMap()
    internal var acknowledgedInaccessibleSources: Set<LegacySourceAcknowledgment> = emptySet()
    internal var preferenceSources: Map<String, List<String?>> = emptyMap()
    internal var active = true
        private set
    override fun close() { active = false; copies = emptyMap(); authenticatedSources = emptyMap(); acknowledgedInaccessibleSources = emptySet() }
    override fun toString() = "LegacyBackupProof([redacted])"
    internal companion object {
        fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun sameEnvelope(a: WalletVaultEntity?, b: WalletVaultEntity?): Boolean =
            if (a == null || b == null) a == null && b == null else
                a.walletId == b.walletId && a.generation == b.generation && a.profileGeneration == b.profileGeneration &&
                    a.envelope.contentEquals(b.envelope) && a.cleanupPending == b.cleanupPending
    }
}

/** Only the coordinator binds an authenticated proof to a committed, read-back-verified generation. */
class BoundLegacyBackupProof internal constructor(
    internal val proof: LegacyBackupProof,
    internal val committed: WalletVaultEntity,
) {
    override fun toString() = "BoundLegacyBackupProof([redacted])"
}

/** Fresh source authorization, explicitly authenticated or acknowledged inaccessible, bound to activation. */
class BoundLegacySourceProof internal constructor(
    internal val source: KeyMaterialEntity,
    internal val committed: WalletVaultEntity,
    internal val authority: LegacySourceAuthority,
) {
    override fun toString() = "BoundLegacySourceProof([redacted])"
}

/** Issued only by a foreground route after classified permanent key loss and explicit native consent. */
internal class LegacySourceAcknowledgment(
    val wallet: WalletEntity,
    val source: KeyMaterialEntity,
    val original: WalletVaultEntity?,
) {
    override fun toString() = "LegacySourceAcknowledgment([redacted])"
}
internal enum class LegacySourceAuthority { AUTHENTICATED, ACKNOWLEDGED_INACCESSIBLE }
