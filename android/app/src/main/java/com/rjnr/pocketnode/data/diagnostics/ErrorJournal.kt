package com.rjnr.pocketnode.data.diagnostics

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent in-app error journal (Alex, Telegram 2026-07).
 *
 * Release builds strip android.util.Log via proguard, so when a user hits a
 * real failure (a rejected send, most importantly) there is NOTHING in their
 * exported logs — the one moment we need diagnostics is the one moment they
 * don't exist. This journal records app-level failures to a small file in
 * filesDir, independent of logcat, and Node Status renders it with a
 * copy-all action so a user can paste the exact reason into a bug report.
 *
 * Privacy: callers must not record addresses, amounts, or key material.
 * Transaction hashes and outpoints are public chain data and are the
 * diagnostic payload we need.
 */
@Singleton
class ErrorJournal @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Entry(val atMs: Long, val tag: String, val message: String)

    private val lock = Any()
    private val file: File by lazy { File(context.filesDir, FILE_NAME) }

    @Volatile
    private var cache: MutableList<Entry>? = null

    fun record(tag: String, message: String) {
        runCatching {
            synchronized(lock) {
                val entries = load()
                entries.add(Entry(System.currentTimeMillis(), tag, message.take(MAX_MESSAGE_LEN)))
                while (entries.size > MAX_ENTRIES) entries.removeAt(0)
                persist(entries)
            }
        }.onFailure { Log.w(TAG, "journal record failed", it) }
    }

    fun entries(): List<Entry> = runCatching {
        synchronized(lock) { load().toList() }
    }.getOrDefault(emptyList())

    fun clear() {
        runCatching {
            synchronized(lock) {
                cache = mutableListOf()
                file.delete()
            }
        }
    }

    /** Human-pastable dump for the Node Status copy-all action. */
    fun dump(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return entries().joinToString("\n") { e ->
            "[${fmt.format(Date(e.atMs))}] ${e.tag}: ${e.message}"
        }
    }

    private fun load(): MutableList<Entry> {
        cache?.let { return it }
        val loaded = mutableListOf<Entry>()
        runCatching {
            if (file.exists()) {
                file.readLines().forEach { line ->
                    val parts = line.split('\t', limit = 3)
                    if (parts.size == 3) {
                        val at = parts[0].toLongOrNull() ?: return@forEach
                        loaded.add(Entry(at, parts[1], parts[2]))
                    }
                }
            }
        }
        cache = loaded
        return loaded
    }

    private fun persist(entries: MutableList<Entry>) {
        cache = entries
        // Tab-separated, newline-per-entry; message newlines flattened so the
        // format survives round-trips.
        file.writeText(
            entries.joinToString("\n") { e ->
                "${e.atMs}\t${e.tag}\t${e.message.replace('\n', ' ').replace('\t', ' ')}"
            }
        )
    }

    companion object {
        private const val TAG = "ErrorJournal"
        private const val FILE_NAME = "error_journal.log"
        internal const val MAX_ENTRIES = 50
        internal const val MAX_MESSAGE_LEN = 2_000
    }
}
