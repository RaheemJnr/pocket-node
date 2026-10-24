package com.rjnr.pocketnode.data.vault

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.core.log.NoopLogger
import com.rjnr.pocketnode.data.crypto.KeyBackupManager
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class WalletLegacyCleanupTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun `checked cleanup removes only requested wallet preferences and all its weak backup copies`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("ckb_wallet_keys_a", 0)
        prefs.edit().putString("private_key", "disposable public fixture").commit()
        val sibling = context.getSharedPreferences("ckb_wallet_keys_b", 0)
        sibling.edit().putString("private_key", "disposable sibling").commit()
        File(folder.root, "a.enc").writeText("legacy source")
        File(folder.root, "a.enc.tmp").writeText("interrupted legacy rewrite")
        val other = File(folder.root, "b.enc").apply { writeText("sibling") }
        val cleanup = WalletLegacyCleanup(context, KeyBackupManager(folder.root, NoopLogger))
        assertTrue(cleanup.clean("a"))
        assertFalse(File(folder.root, "a.enc").exists()); assertFalse(File(folder.root, "a.enc.tmp").exists())
        assertFalse(File(context.filesDir.parentFile, "shared_prefs/ckb_wallet_keys_a.xml").exists())
        assertEquals("sibling", other.readText()); assertTrue(sibling.contains("private_key"))
        assertTrue(cleanup.clean("a"))
    }
    @Test fun `failed deletion remains retryable and never reports cleanup complete`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val blocked = File(folder.root, "a.enc").apply { mkdir() }
        File(blocked, "child").writeText("cannot remove nonempty directory")
        val cleanup = WalletLegacyCleanup(context, KeyBackupManager(folder.root, NoopLogger))
        assertFalse(cleanup.clean("a")); assertTrue(blocked.exists())
        File(blocked, "child").delete(); blocked.delete()
        assertTrue(cleanup.clean("a"))
    }
}
