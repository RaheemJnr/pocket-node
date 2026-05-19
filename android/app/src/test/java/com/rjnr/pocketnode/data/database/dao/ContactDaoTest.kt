package com.rjnr.pocketnode.data.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.rjnr.pocketnode.data.database.AppDatabase
import com.rjnr.pocketnode.data.database.entity.ContactEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ContactDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ContactDao

    private fun contact(
        id: String = UUID.randomUUID().toString(),
        walletId: String? = "wallet-1",
        name: String,
        address: String,
        network: String = "testnet",
        useCount: Int = 0,
        lastUsedAt: Long? = null,
        now: Long = 1_000_000L,
    ) = ContactEntity(
        id = id,
        walletId = walletId,
        name = name,
        address = address,
        network = network,
        notes = null,
        tags = null,
        createdAt = now,
        updatedAt = now,
        lastUsedAt = lastUsedAt,
        useCount = useCount,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.contactDao()
    }

    @After
    fun tearDown() { db.close() }

    @Test
    fun `insert and getById round-trips`() = runTest {
        val c = contact(name = "Alice", address = "ckt1_alice")
        dao.insert(c)
        val fetched = dao.getById(c.id)
        assertNotNull(fetched)
        assertEquals("Alice", fetched!!.name)
        assertEquals("ckt1_alice", fetched.address)
    }

    @Test
    fun `getByAddress returns the first matching row`() = runTest {
        dao.insert(contact(name = "Alice", address = "ckt1_alice"))
        val found = dao.getByAddress("ckt1_alice")
        assertNotNull(found)
        assertEquals("Alice", found!!.name)
    }

    @Test
    fun `getByAddress returns null for unknown address`() = runTest {
        assertNull(dao.getByAddress("ckt1_missing"))
    }

    @Test
    fun `observeAll returns wallet-scoped and global contacts sorted by name`() = runTest {
        dao.insert(contact(walletId = "wallet-1", name = "bob", address = "ckt1_bob"))
        dao.insert(contact(walletId = null, name = "alice", address = "ckt1_alice")) // global
        dao.insert(contact(walletId = "wallet-1", name = "Carol", address = "ckt1_carol"))
        // Contact for a different wallet — must NOT appear in wallet-1's list.
        dao.insert(contact(walletId = "wallet-2", name = "eve", address = "ckt1_eve"))

        val list = dao.observeAll("wallet-1").first()
        assertEquals(listOf("alice", "bob", "Carol"), list.map { it.name })
    }

    @Test
    fun `search matches by name OR address with LIKE wildcards`() = runTest {
        dao.insert(contact(name = "Alice", address = "ckt1_alice"))
        dao.insert(contact(name = "Alicia", address = "ckt1_alicia"))
        dao.insert(contact(name = "Bob", address = "ckt1_bob"))

        // The DAO does NOT add wildcards itself — repository is in charge.
        val nameHit = dao.search("wallet-1", "%Ali%")
        assertEquals(2, nameHit.size)

        val addrHit = dao.search("wallet-1", "%bob%")
        assertEquals(1, addrHit.size)
        assertEquals("Bob", addrHit.first().name)
    }

    @Test
    fun `recentlyUsed sorts by useCount desc and excludes unused`() = runTest {
        dao.insert(contact(name = "Cold", address = "ckt1_cold", useCount = 0))
        dao.insert(contact(name = "Warm", address = "ckt1_warm", useCount = 2, lastUsedAt = 200L))
        dao.insert(contact(name = "Hot", address = "ckt1_hot", useCount = 5, lastUsedAt = 100L))

        val recent = dao.recentlyUsed("wallet-1")
        assertEquals(listOf("Hot", "Warm"), recent.map { it.name })
    }

    @Test
    fun `markUsed bumps count and timestamp atomically`() = runTest {
        val c = contact(name = "Alice", address = "ckt1_alice", useCount = 0, lastUsedAt = null)
        dao.insert(c)

        dao.markUsed(c.id, now = 5_000L)
        var after = dao.getById(c.id)!!
        assertEquals(1, after.useCount)
        assertEquals(5_000L, after.lastUsedAt)

        dao.markUsed(c.id, now = 6_000L)
        after = dao.getById(c.id)!!
        assertEquals(2, after.useCount)
        assertEquals(6_000L, after.lastUsedAt)
    }

    @Test
    fun `update writes new fields without touching id`() = runTest {
        val c = contact(name = "Alice", address = "ckt1_alice")
        dao.insert(c)
        dao.update(c.copy(name = "Alice (new)", notes = "moved"))

        val fetched = dao.getById(c.id)!!
        assertEquals("Alice (new)", fetched.name)
        assertEquals("moved", fetched.notes)
    }

    @Test
    fun `delete removes the row`() = runTest {
        val c = contact(name = "Alice", address = "ckt1_alice")
        dao.insert(c)
        dao.delete(c)
        assertNull(dao.getById(c.id))
    }
}
