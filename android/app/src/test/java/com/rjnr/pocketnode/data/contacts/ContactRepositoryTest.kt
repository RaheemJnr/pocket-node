package com.rjnr.pocketnode.data.contacts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.dao.ContactDao
import com.rjnr.pocketnode.data.gateway.models.NetworkType
import com.rjnr.pocketnode.data.gateway.models.Script
import com.rjnr.pocketnode.data.wallet.AddressUtils
import com.rjnr.pocketnode.data.wallet.WalletRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ContactRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var contactDao: ContactDao
    private lateinit var walletRepository: WalletRepository
    private lateinit var repo: ContactRepository
    private var fakeNow = 1_000_000L

    // Generate addresses from a known lock script — this is the same path
    // AddressUtils round-trips, so getNetwork() is guaranteed to decode
    // them. (Hardcoded fixture addresses sometimes diverge across CKB SDK
    // bech32m revisions; encoding from a Script avoids that.)
    private val sampleScript = Script(
        codeHash = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
        hashType = "type",
        args = "0x" + "aa".repeat(20),
    )
    private val sampleScript2 = Script(
        codeHash = "0x9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8",
        hashType = "type",
        args = "0x" + "bb".repeat(20),
    )
    private val testnetAddress by lazy { AddressUtils.encode(sampleScript, NetworkType.TESTNET) }
    private val mainnetAddress by lazy { AddressUtils.encode(sampleScript, NetworkType.MAINNET) }
    private val testnetAddress2 by lazy { AddressUtils.encode(sampleScript2, NetworkType.TESTNET) }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        contactDao = db.contactDao()
        walletRepository = mockk(relaxed = true)
        every { walletRepository.activeWalletIdSnapshot() } returns "wallet-1"
        repo = ContactRepository(contactDao, walletRepository).apply {
            setClockForTest { fakeNow }
        }
    }

    @After
    fun tearDown() { db.close() }

    // -- add: validation --

    @Test
    fun `add rejects empty name`() = runTest {
        val result = repo.add(name = "  ", address = testnetAddress, activeNetwork = NetworkType.TESTNET)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ContactRepository.ContactError.InvalidName)
    }

    @Test
    fun `add rejects name longer than 64 chars`() = runTest {
        val result = repo.add(name = "x".repeat(65), address = testnetAddress, activeNetwork = NetworkType.TESTNET)
        assertTrue(result.exceptionOrNull() is ContactRepository.ContactError.InvalidName)
    }

    @Test
    fun `add rejects notes longer than 256 chars`() = runTest {
        val result = repo.add(
            name = "Alice", address = testnetAddress,
            notes = "x".repeat(257), activeNetwork = NetworkType.TESTNET,
        )
        assertTrue(result.exceptionOrNull() is ContactRepository.ContactError.NotesTooLong)
    }

    @Test
    fun `add rejects unparseable address`() = runTest {
        val result = repo.add(name = "Alice", address = "not-a-ckb-address", activeNetwork = NetworkType.TESTNET)
        assertTrue(result.exceptionOrNull() is ContactRepository.ContactError.InvalidAddress)
    }

    @Test
    fun `add rejects mainnet address on testnet`() = runTest {
        val result = repo.add(name = "Alice", address = mainnetAddress, activeNetwork = NetworkType.TESTNET)
        val err = result.exceptionOrNull()
        assertTrue(err is ContactRepository.ContactError.WrongNetwork)
        err as ContactRepository.ContactError.WrongNetwork
        assertEquals(NetworkType.TESTNET, err.expected)
        assertEquals(NetworkType.MAINNET, err.actual)
    }

    @Test
    fun `add rejects duplicate address`() = runTest {
        repo.add(name = "Alice", address = testnetAddress, activeNetwork = NetworkType.TESTNET).getOrThrow()
        val second = repo.add(name = "Alice 2", address = testnetAddress, activeNetwork = NetworkType.TESTNET)
        assertTrue(second.exceptionOrNull() is ContactRepository.ContactError.DuplicateAddress)
    }

    @Test
    fun `add succeeds and writes scoped row`() = runTest {
        val result = repo.add(
            name = "Alice", address = testnetAddress,
            notes = "hot wallet", tags = listOf("friend", "frequent"),
            activeNetwork = NetworkType.TESTNET,
        )
        val saved = result.getOrThrow()
        assertEquals("wallet-1", saved.walletId)
        assertEquals("Alice", saved.name)
        assertEquals("testnet", saved.network)
        assertEquals("friend,frequent", saved.tags)
        assertEquals(0, saved.useCount)
        assertNull(saved.lastUsedAt)
    }

    @Test
    fun `add as global writes null walletId`() = runTest {
        val result = repo.add(
            name = "Alice", address = testnetAddress,
            scopedToActiveWallet = false, activeNetwork = NetworkType.TESTNET,
        )
        val saved = result.getOrThrow()
        assertNull(saved.walletId)
    }

    // -- update / delete --

    @Test
    fun `update preserves address and useCount`() = runTest {
        val original = repo.add(name = "Alice", address = testnetAddress, activeNetwork = NetworkType.TESTNET).getOrThrow()
        contactDao.markUsed(original.id, 5_000L) // simulate prior use

        fakeNow = 2_000_000L
        repo.update(id = original.id, name = "Alice (renamed)", notes = "updated", tags = null).getOrThrow()

        val fetched = contactDao.getById(original.id)!!
        assertEquals("Alice (renamed)", fetched.name)
        assertEquals("updated", fetched.notes)
        assertEquals(testnetAddress, fetched.address)
        assertEquals(1, fetched.useCount)
        assertEquals(5_000L, fetched.lastUsedAt)
        assertEquals(2_000_000L, fetched.updatedAt)
    }

    @Test
    fun `update fails on unknown id`() = runTest {
        val r = repo.update(id = "nonexistent", name = "X", notes = null, tags = null)
        assertTrue(r.exceptionOrNull() is ContactRepository.ContactError.NotFound)
    }

    @Test
    fun `delete removes the row`() = runTest {
        val saved = repo.add(name = "Alice", address = testnetAddress, activeNetwork = NetworkType.TESTNET).getOrThrow()
        repo.delete(saved.id).getOrThrow()
        assertNull(contactDao.getById(saved.id))
    }

    // -- search / recentlyUsed / markUsed --

    @Test
    fun `search empty query returns empty list`() = runTest {
        repo.add(name = "Alice", address = testnetAddress, activeNetwork = NetworkType.TESTNET)
        assertEquals(emptyList<Any>(), repo.search(""))
        assertEquals(emptyList<Any>(), repo.search("   "))
    }

    @Test
    fun `search wraps query with LIKE wildcards`() = runTest {
        repo.add(name = "Alice", address = testnetAddress, activeNetwork = NetworkType.TESTNET).getOrThrow()
        val hits = repo.search("alic")
        assertEquals(1, hits.size)
        assertEquals("Alice", hits.first().name)
    }

    @Test
    fun `markUsed silently no-ops for unsaved address`() = runTest {
        repo.markUsed("ckt1_not_saved") // should not throw
    }

    @Test
    fun `markUsed bumps counters on saved address`() = runTest {
        val saved = repo.add(name = "Alice", address = testnetAddress, activeNetwork = NetworkType.TESTNET).getOrThrow()
        fakeNow = 9_000L
        repo.markUsed(testnetAddress)
        val after = contactDao.getById(saved.id)!!
        assertEquals(1, after.useCount)
        assertEquals(9_000L, after.lastUsedAt)
    }

    @Test
    fun `recentlyUsed returns nothing without an active wallet`() = runTest {
        every { walletRepository.activeWalletIdSnapshot() } returns null
        repo.add(
            name = "Alice", address = testnetAddress,
            scopedToActiveWallet = false, activeNetwork = NetworkType.TESTNET,
        )
        assertEquals(emptyList<Any>(), repo.recentlyUsed())
    }
}
