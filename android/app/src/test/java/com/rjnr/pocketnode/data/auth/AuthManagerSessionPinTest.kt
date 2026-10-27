package com.rjnr.pocketnode.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AuthManagerSessionPinTest {

    private lateinit var authManager: AuthManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        authManager = AuthManager(context)
    }

    @Test
    fun `getSessionPin returns null initially`() {
        assertNull(authManager.getSessionPin())
    }

    @Test
    fun `setSessionPin stores PIN and getSessionPin retrieves it`() {
        authManager.setSessionPin("123456".toCharArray())
        val pin = authManager.getSessionPin()
        assertNotNull(pin)
        assertEquals("123456", String(pin!!))
    }

    @Test
    fun `clearSession zeroes the PIN`() {
        authManager.setSessionPin("123456".toCharArray())
        authManager.clearSession()
        assertNull(authManager.getSessionPin())
    }

    @Test
    fun `hasSessionPin returns true when set`() {
        assertFalse(authManager.hasSessionPin())
        authManager.setSessionPin("123456".toCharArray())
        assertTrue(authManager.hasSessionPin())
    }

    @Test
    fun `setSessionPin replaces previous PIN`() {
        authManager.setSessionPin("123456".toCharArray())
        authManager.setSessionPin("654321".toCharArray())
        assertEquals("654321", String(authManager.getSessionPin()!!))
    }

    @Test
    fun `getSessionPin returns a copy not the original`() {
        authManager.setSessionPin("123456".toCharArray())
        val pin1 = authManager.getSessionPin()!!
        val pin2 = authManager.getSessionPin()!!
        assertFalse(pin1 === pin2) // different instances
        assertEquals(String(pin1), String(pin2)) // same content
    }
    @Test fun `clearing an empty session revokes pending first PIN authentication`() {
        val generation = authManager.sessionGeneration()
        authManager.clearSession()
        assertFalse(authManager.setSessionPinIfCurrent("123456".toCharArray(), generation))
        assertFalse(authManager.hasSessionPin())
    }

    @Test fun `current authentication publishes once and stale result cannot replace it`() {
        val generation = authManager.sessionGeneration()
        assertTrue(authManager.setSessionPinIfCurrent("123456".toCharArray(), generation))
        assertFalse(authManager.setSessionPinIfCurrent("654321".toCharArray(), generation))
        assertEquals("123456", String(authManager.getSessionPin()!!))
    }

}
