package com.rjnr.pocketnode.data.wallet

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.WeakHashMap

/**
 * Installation lifetime barrier. Publish the marker inside a Room transaction; every secret
 * commit checks this barrier inside its transaction. Synchronous preference/backup operations
 * share [files], so publication also waits for their current writer. Lock order: Room, then files.
 * No lock is held across authentication. A completed reset keeps this process closed: an old
 * prompt/coroutine must never become a writer again. Only a fresh startup can reopen storage.
 */
class WalletStorageBoundary(context: Context) {
    enum class Status { NONE, PENDING, COMPLETE, INVALID }
    private class State(val marker: AtomicFile) {
        val monitor = Any()
        val initial = read(marker)
        var closed = initial != Status.NONE
    }
    private val state = synchronized(states) {
        states.getOrPut(context.applicationContext) {
            State(AtomicFile(File(context.noBackupFilesDir, "wallet_reset")))
        }
    }

    internal val fileLock: Any get() = state.monitor

    fun status(): Status = synchronized(state.monitor) { read(state.marker) }
    fun isClosed(): Boolean = synchronized(state.monitor) { state.closed || read(state.marker) != Status.NONE }
    fun requireOpen() { check(!isClosed()) { "Local reset must finish before wallet storage can be used." } }

    fun <T> files(block: () -> T): T = synchronized(state.monitor) { block() }
    fun <T> readOrWriteFiles(block: () -> T): T = files { requireOpen(); block() }

    /** Must be called under the same Room transaction boundary as wallet commits. */
    fun beginReset() = files {
        state.closed = true
        write(Status.PENDING)
    }

    fun completeReset() = files {
        check(state.closed && read(state.marker) == Status.PENDING)
        write(Status.COMPLETE)
    }

    /** Caller verifies all secret stores and PIN are absent before this fresh-process transition. */
    fun finishCompletedStartup() = files {
        check(state.initial == Status.COMPLETE && read(state.marker) == Status.COMPLETE)
        state.marker.delete()
        check(read(state.marker) == Status.NONE) { "Reset completion could not be recorded." }
        state.closed = false
    }

    fun canFinishAtStartup(): Boolean = files { state.initial == Status.COMPLETE && read(state.marker) == Status.COMPLETE }

    private fun write(status: Status) {
        val stream = state.marker.startWrite()
        try {
            stream.write(status.name.toByteArray(Charsets.US_ASCII))
            stream.fd.sync()
            state.marker.finishWrite(stream)
            check(read(state.marker) == status) { "Reset state could not be persisted." }
        } catch (failure: Throwable) {
            state.marker.failWrite(stream)
            throw failure
        }
    }

    companion object {
        private val states = WeakHashMap<Context, State>()
        private fun read(file: AtomicFile): Status {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return Status.NONE
            return try {
                when (file.openRead().use { it.readBytes().toString(Charsets.US_ASCII) }) {
                    "PENDING" -> Status.PENDING
                    "COMPLETE" -> Status.COMPLETE
                    else -> Status.INVALID
                }
            } catch (_: Exception) { Status.INVALID }
        }
    }
}
