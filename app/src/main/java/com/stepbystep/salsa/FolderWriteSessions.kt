package com.stepbystep.salsa

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Owns staged folder writes; an accepted close finishes even if its activity goes away. */
internal class FolderWriteSessions(private val directory: File) : AutoCloseable {
    interface Target {
        fun open(): OutputStream
        fun length(): Long
    }

    private enum class Phase { RECEIVING, COMMITTING, ABORTING }
    private class Entry(
        val token: String,
        val key: String,
        val leaseKey: String,
        val target: Target,
        val file: File,
        var output: FileOutputStream?,
        var phase: Phase = Phase.RECEIVING,
    )

    private val lock = Any()
    private val directoryKey = directory.canonicalPath
    private val entries = mutableMapOf<String, Entry>()
    private val targets = mutableMapOf<String, String>()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false

    init {
        check(directory.isDirectory || directory.mkdirs()) { "Temporärer Schreibspeicher nicht verfügbar." }
    }

    fun begin(key: String, target: Target): String = synchronized(lock) {
        check(!closed) { "Ordnerspeicher wurde geschlossen." }
        check(!targets.containsKey(key)) { "Für diese Datei läuft bereits ein Schreibvorgang." }
        val token = UUID.randomUUID().toString()
        val leaseKey = "$directoryKey\u0000$key"
        check(targetLeases.putIfAbsent(leaseKey, token) == null) {
            "Für diese Datei läuft bereits ein Schreibvorgang."
        }
        val file = File(directory, "saf-$token")
        try {
            entries[token] = Entry(token, key, leaseKey, target, file, FileOutputStream(file))
            targets[key] = token
            token
        } catch (error: Exception) {
            file.delete()
            targetLeases.remove(leaseKey, token)
            throw error
        }
    }

    fun append(token: String, bytes: ByteArray) {
        synchronized(lock) {
            val entry = receiving(token)
            try {
                require(bytes.size <= MAX_BLOCK_BYTES) { "Schreibblock ist zu groß." }
                entry.output!!.write(bytes)
            } catch (error: Exception) {
                cleanStaging(entry)
                remove(entry)
                throw error
            }
        }
    }

    /** Synchronous compatibility path; the state lock is released before provider I/O. */
    fun finish(token: String) {
        val entry = synchronized(lock) { claim(token) }
        commit(entry)
    }

    /** Claim before queuing: neither abort nor destruction may interrupt a destination commit. */
    fun finishAsync(token: String, completion: (Result<Unit>) -> Unit) {
        synchronized(lock) {
            val entry = claim(token)
            try {
                worker.execute {
                    val result = runCatching { commit(entry) }
                    // Never invoke an arbitrary callback under the state lock. Android adapters
                    // also guard callbacks already queued for their UI thread during destruction.
                    if (!closed) completion(result)
                }
            } catch (error: Exception) {
                cleanStaging(entry)
                remove(entry)
                throw error
            }
        }
    }

    /** Abort only staged bytes; after close has won, allow the destination to finish intact. */
    fun abort(token: String) {
        val entry = synchronized(lock) {
            entries[token]?.takeIf { it.phase == Phase.RECEIVING }?.also { it.phase = Phase.ABORTING }
        } ?: return
        cleanStaging(entry)
        synchronized(lock) { remove(entry) }
    }

    private fun receiving(token: String): Entry {
        check(!closed) { "Ordnerspeicher wurde geschlossen." }
        return entries[token]?.takeIf { it.phase == Phase.RECEIVING }
            ?: error("Schreibvorgang fehlt oder wird bereits abgeschlossen.")
    }

    private fun claim(token: String): Entry = receiving(token).also { it.phase = Phase.COMMITTING }

    private fun commit(entry: Entry) {
        try {
            entry.output!!.fd.sync()
            entry.output!!.close()
            entry.output = null
            val expected = entry.file.length()
            val copied = entry.target.open().use { output ->
                entry.file.inputStream().use { input -> input.copyTo(output, 65536) }
            }
            check(copied == expected && entry.target.length() == expected) { "Schreibprüfung fehlgeschlagen." }
        } finally {
            cleanStaging(entry)
            synchronized(lock) { remove(entry) }
        }
    }

    /** Caller holds lock; removing only the matching entry prevents stale-token interference. */
    private fun remove(entry: Entry) {
        if (entries[entry.token] !== entry) return
        entries.remove(entry.token)
        if (targets[entry.key] == entry.token) targets.remove(entry.key)
        targetLeases.remove(entry.leaseKey, entry.token)
    }

    private fun cleanStaging(entry: Entry) {
        runCatching { entry.output?.close() }
        entry.output = null
        entry.file.delete()
    }

    override fun close() {
        val staged = synchronized(lock) {
            if (closed) return
            closed = true
            val receiving = entries.values.filter { it.phase == Phase.RECEIVING }
            receiving.forEach { it.phase = Phase.ABORTING }
            // Already claimed, queued commits retain their source and may drain after close.
            worker.shutdown()
            receiving
        }
        staged.forEach { entry ->
            cleanStaging(entry)
            synchronized(lock) { remove(entry) }
        }
    }

    companion object {
        const val MAX_BLOCK_BYTES = 262144
        // A recreated activity must not race a previous activity's detached commit.
        private val targetLeases = ConcurrentHashMap<String, String>()
    }
}
