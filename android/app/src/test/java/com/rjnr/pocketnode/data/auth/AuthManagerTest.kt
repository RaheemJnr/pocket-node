package com.rjnr.pocketnode.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.biometric.BiometricManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AuthManagerTest {

    private lateinit var authManager: AuthManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        authManager = AuthManager(context)
        authManager.testPrefs = context.getSharedPreferences("test_auth", Context.MODE_PRIVATE)
        authManager.testPrefs!!.edit().clear().commit()
    }

    @Test
    fun `isBiometricEnabled returns false by default`() {
        assertFalse(authManager.isBiometricEnabled())
    }

    @Test
    fun `setBiometricEnabled true then isBiometricEnabled returns true`() {
        authManager.setBiometricEnabled(true)
        assertTrue(authManager.isBiometricEnabled())
    }

    @Test
    fun `setBiometricEnabled false after true returns false`() {
        authManager.setBiometricEnabled(true)
        authManager.setBiometricEnabled(false)
        assertFalse(authManager.isBiometricEnabled())
    }

    @Test
    fun `isBiometricAvailable returns valid status on emulator`() {
        val status = authManager.isBiometricAvailable()
        assertTrue(
            status == AuthManager.BiometricStatus.NO_HARDWARE ||
                status == AuthManager.BiometricStatus.UNAVAILABLE
        )
    }

    @Test
    fun `isBiometricEnrolled returns false on emulator`() {
        assertFalse(authManager.isBiometricEnrolled())
    }
    @Test @Config(sdk = [26]) fun `credential only CryptoObject mode is rejected on API26`() {
        assertNull(authManager.allowedCipherAuthenticators(AuthManager.CipherPromptMode.CREDENTIAL_ONLY))
        assertEquals(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG,
            authManager.allowedCipherAuthenticators(AuthManager.CipherPromptMode.BIOMETRIC_ONLY))
    }
    @Test @Config(sdk = [30]) fun `explicit credential prompt never includes biometric authenticators`() {
        authManager.testBiometricManager = mockk {
            every { canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) } returns BiometricManager.BIOMETRIC_SUCCESS
        }
        authManager.setBiometricEnabled(true)
        assertEquals(androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            authManager.allowedCipherAuthenticators(AuthManager.CipherPromptMode.CREDENTIAL_ONLY))
        assertEquals(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG,
            authManager.allowedCipherAuthenticators(AuthManager.CipherPromptMode.BIOMETRIC_ONLY))
        assertEquals(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            authManager.allowedCipherAuthenticators(AuthManager.CipherPromptMode.DEFAULT))
    }

}
