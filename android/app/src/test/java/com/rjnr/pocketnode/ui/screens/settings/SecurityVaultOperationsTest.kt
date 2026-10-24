package com.rjnr.pocketnode.ui.screens.settings

import com.rjnr.pocketnode.data.wallet.*
import com.rjnr.pocketnode.data.vault.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SecurityVaultOperationsTest {
    @Test fun `known passphrase changes all wraps and cancellation preserves old credentials`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.phrase(); assertTrue(f.coordinator.persistNew(f.activity, "a", f.material) is VaultAccessResult.Success)
            val operations = SecurityVaultOperations(f.coordinator, f.store, f.db.walletDao())
            f.phrase(null); assertTrue(operations.change(f.activity).message.contains("Cancelled"))
            assertTrue(f.store.verifyPassphrase(f.pass.toCharArray()))
            f.phrase(); f.phrase("new independent wallet passphrase")
            val result = operations.change(f.activity)
            assertEquals("Passphrase updated", result.wallets.single().message)
            assertFalse(f.store.verifyPassphrase(f.pass.toCharArray()))
            assertTrue(f.store.verifyPassphrase("new independent wallet passphrase".toCharArray()))
        }
    }
    @Test fun `forgotten reset cancelled before credentials preserves active profile`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.phrase(); f.coordinator.persistNew(f.activity, "a", f.material)
            val original = f.store.get("a")
            val result = SecurityVaultOperations(f.coordinator, f.store, f.db.walletDao()).reset(f.activity, null)
            assertTrue(result.message.contains("Cancelled")); assertArrayEquals(WalletEnvelopeCodec.encode(original!!), WalletEnvelopeCodec.encode(f.store.get("a")!!))
        }
    }
    @Test fun `explicit device enrollment without device lock preserves phrase access`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.phrase(); f.coordinator.persistNew(f.activity, "a", f.material)
            val before = f.store.get("a")
            f.phrase()
            val result = SecurityVaultOperations(f.coordinator, f.store, f.db.walletDao()).enroll(f.activity, "a", VaultDevicePromptMode.CREDENTIAL_ONLY)
            assertTrue(result.message.contains("did not complete")); assertArrayEquals(WalletEnvelopeCodec.encode(before!!), WalletEnvelopeCodec.encode(f.store.get("a")!!))
            f.phrase(); assertTrue(f.coordinator.read(f.activity, "a", "Unlock", "Phrase", VaultReadPreference.PASSPHRASE) is VaultAccessResult.Success)
        }
    }
    @Test fun `partial reset reports each wallet and preserves unrecovered bytes and rotation block`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.wallet("b"); f.phrase(); f.coordinator.persistNew(f.activity, "a", f.material)
            f.phrase(); f.coordinator.persistNew(f.activity, "b", f.material)
            val a = f.store.get("a")!!
            f.store.updateDeviceWrap("a", a.generation, DeviceKeyWrap("test-a", ByteArray(48), ByteArray(12)))
            val originalB = WalletEnvelopeCodec.encode(f.store.get("b")!!)
            val device = object : VaultDeviceAuthentication {
                override fun unavailableReason(): String? = null
                override fun availablePromptModes() = setOf(VaultDevicePromptMode.BIOMETRIC_ONLY)
                override suspend fun wrap(activity: androidx.fragment.app.FragmentActivity, envelope: WalletEnvelope, dataKey: ByteArray, mode: VaultDevicePromptMode): DeviceVaultResult<DeviceKeyWrap> = DeviceVaultResult.Cancelled
                override suspend fun unwrap(activity: androidx.fragment.app.FragmentActivity, envelope: WalletEnvelope, title: String, subtitle: String, mode: VaultDevicePromptMode): DeviceVaultResult<ByteArray> =
                    DeviceVaultResult.Success(f.crypto.unwrapDataKey(envelope, envelope.walletId, f.pass.toCharArray()))
            }
            val coordinator = WalletVaultCoordinator(f.db, f.store, f.crypto, WalletSecretValidator(MnemonicManager()), f.prompt, device,
                WalletLegacyCleanup(f.context, f.backup, f.db, f.keyManager, f.legacy))
            val operations = SecurityVaultOperations(coordinator, f.store, f.db.walletDao())
            f.choices += true; f.phrase("a replacement independent phrase")
            val result = operations.reset(f.activity, null)
            assertEquals("Passphrase updated", result.wallets.first { it.walletId == "a" }.message)
            assertTrue(result.wallets.first { it.walletId == "b" }.message.contains("Not reset"))
            assertArrayEquals(originalB, WalletEnvelopeCodec.encode(f.store.get("b")!!))
            assertTrue(operations.walletNames().first { it.walletId == "b" }.message.contains("Previous passphrase"))
            f.phrase("a replacement independent phrase"); f.phrase("yet another independent phrase")
            assertTrue(operations.change(f.activity).message.contains("recover those wallets first"))
            assertArrayEquals(originalB, WalletEnvelopeCodec.encode(f.store.get("b")!!))
        }
    }
    @Test fun `dashboard PIN creation makes no weaker backup and unlock preference persists before decrypt`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.phrase(); f.coordinator.persistNew(f.activity, "a", f.material)
            val before = WalletEnvelopeCodec.encode(f.store.get("a")!!)
            val vm = SecuritySettingsViewModel(androidx.lifecycle.SavedStateHandle(), f.auth, f.pin, f.backup,
                f.db.walletDao(), f.preference, SecurityVaultOperations(f.coordinator, f.store, f.db.walletDao()))
            vm.onPinCreated()
            vm.selectUnlockPreference(VaultReadPreference.PASSPHRASE)
            assertEquals(VaultReadPreference.PASSPHRASE, WalletUnlockPreference(f.context).get())
            kotlinx.coroutines.withTimeout(10_000) { while (vm.uiState.value.vaultWallets.isEmpty()) kotlinx.coroutines.delay(10) }
            assertFalse(f.backup.hasAnyBackups()); assertArrayEquals(before, WalletEnvelopeCodec.encode(f.store.get("a")!!))
        }
    }

    @Test fun `malformed protection metadata does not hide authentication choices`() = walletOperationTest {
        WalletVaultIntegrationFixture().use { f ->
            f.wallet(); f.phrase(); f.coordinator.persistNew(f.activity, "a", f.material)
            val row = f.db.walletVaultDao().profile()!!
            f.db.walletVaultDao().updateProfile(row.copy(envelope = byteArrayOf(1)))
            val status = SecurityVaultOperations(f.coordinator, f.store, f.db.walletDao()).walletNames()
            assertEquals("a", status.single().walletId)
            assertTrue(status.single().message.contains("unavailable"))
        }
    }

}
