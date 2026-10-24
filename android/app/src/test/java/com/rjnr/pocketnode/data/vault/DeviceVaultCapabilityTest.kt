package com.rjnr.pocketnode.data.vault

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.auth.AuthManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class DeviceVaultCapabilityTest {
    private fun auth(): AuthManager {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return AuthManager(context).apply {
            testPrefs = context.getSharedPreferences("vault-capability", Context.MODE_PRIVATE)
            testPrefs!!.edit().clear().commit()
        }
    }
    @Test fun `API26 no lock and unsupported credential auth remain separate from passphrase support`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val guard = shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
        val auth = auth(); val device = DeviceVaultKeyStore(auth)
        guard.setIsDeviceSecure(false)
        assertEquals("no-device-lock", device.unavailableReason())
        guard.setIsDeviceSecure(true)
        assertEquals("unsupported-device-authentication", device.unavailableReason())
        auth.testBiometricManager = mockk { every { canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) } returns BiometricManager.BIOMETRIC_SUCCESS }
        auth.setBiometricEnabled(true)
        assertNull(device.unavailableReason())
        assertEquals(setOf(VaultDevicePromptMode.BIOMETRIC_ONLY), device.availablePromptModes())
    }
    @Test @Config(sdk = [30]) fun `API30 supports credential CryptoObject without biometric enrollment`() {
        val auth = auth()
        auth.testBiometricManager = mockk {
            every { canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) } returns BiometricManager.BIOMETRIC_SUCCESS
            every { canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) } returns BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        }
        assertNull(DeviceVaultKeyStore(auth).unavailableReason())
        assertEquals(setOf(VaultDevicePromptMode.CREDENTIAL_ONLY), DeviceVaultKeyStore(auth).availablePromptModes())
        assertEquals(BiometricManager.Authenticators.DEVICE_CREDENTIAL, auth.getAllowedAuthenticators())
    }
}
