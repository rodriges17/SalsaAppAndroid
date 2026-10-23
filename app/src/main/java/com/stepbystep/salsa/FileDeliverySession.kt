package com.stepbystep.salsa

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One prepared export, including retry, target copying and temporary-file ownership. */
internal class FileDeliverySession(
    private val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    enum class Phase { RECEIVING, READY, PICKING, COPYING, SAVED }
    data class Prepared(val id: String, val name: String, val mime: String, val size: Long, val file: File)
    sealed class Result {
        data object Saved : Result()
        data object Cancelled : Result()
        data class Failed(val message: String) : Result()
    }
    private class Entry(val id: String, val name: String, val file: File, var output: FileOutputStream?) {
        var phase = Phase.RECEIVING
        var mime = "application/octet-stream"
        var size = 0L
        val cancelled = AtomicBoolean(false)
        fun prepared() = Prepared(id, name, mime, size, file)
    }
    private var active: Entry? = null
    private var closed = false

    init {
        directory.mkdirs()
        // Handed-off files outlive this activity so receiving apps can still read them.
        directory.listFiles()?.filter { it.name.startsWith("handoff-") && now() - it.lastModified() > SHARE_RETENTION_MS }
            ?.forEach { it.delete() }
    }

    @Synchronized
    fun begin(name: String): Boolean {
        if (closed || active != null) return false
        val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").take(100).ifBlank { "export.zip" }
        val id = UUID.randomUUID().toString()
        val file = File(directory, "handoff-$id-$safe")
        return try {
            active = Entry(id, safe, file, FileOutputStream(file))
            true
        } catch (_: Exception) { file.delete(); false }
    }

    @Synchronized
    fun append(bytes: ByteArray): Boolean {
        val entry = active?.takeIf { !closed && it.phase == Phase.RECEIVING } ?: return false
        return try {
            require(bytes.size <= MAX_BLOCK_BYTES && entry.size + bytes.size <= MAX_EXPORT_BYTES)
            entry.output!!.write(bytes)
            entry.size += bytes.size
            true
        } catch (_: Exception) { discardEntry(entry); false }
    }

    @Synchronized
    fun prepare(mime: String): Prepared? {
        val entry = active?.takeIf { !closed && it.phase == Phase.RECEIVING } ?: return null
        return try {
            entry.output!!.fd.sync()
            entry.output!!.close()
            entry.output = null
            check(entry.file.length() == entry.size)
            entry.mime = if (mime == "application/zip") mime else "application/octet-stream"
            entry.phase = Phase.READY
            entry.prepared()
        } catch (_: Exception) { discardEntry(entry); null }
    }

    @Synchronized
    fun prepared(): Prepared? = active?.takeIf { !closed && it.phase == Phase.READY }?.prepared()

    @Synchronized
    fun requestSave(id: String): Boolean {
        val entry = active?.takeIf { !closed && it.id == id && it.phase == Phase.READY } ?: return false
        entry.phase = Phase.PICKING
        return true
    }

    @Synchronized
    fun cancelPicker(id: String): Boolean {
        val entry = active?.takeIf { !closed && it.id == id && it.phase == Phase.PICKING } ?: return false
        entry.phase = Phase.READY
        return true
    }

    /** Runs off the UI thread. No monitor is held during target/provider I/O. */
    fun save(id: String, destination: () -> OutputStream, progress: (Int) -> Unit = {}): Result {
        val entry = synchronized(this) {
            active?.takeIf { !closed && it.id == id && it.phase == Phase.PICKING }?.also { it.phase = Phase.COPYING }
        } ?: return Result.Cancelled
        val result = try {
            var copied = 0L
            var previous = -1
            fun report() {
                val percent = if (entry.size == 0L) 100 else ((copied * 100) / entry.size).toInt()
                if (previous != percent) { previous = percent; progress(percent) }
            }
            report()
            check(!entry.cancelled.get()) { "Abgebrochen" }
            destination().use { output ->
                entry.file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        check(!entry.cancelled.get()) { "Abgebrochen" }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        report()
                    }
                }
            }
            check(!entry.cancelled.get()) { "Abgebrochen" }
            check(copied == entry.size) { "Die Datei wurde nicht vollständig geschrieben." }
            Result.Saved
        } catch (error: Exception) {
            if (entry.cancelled.get()) Result.Cancelled
            else Result.Failed(error.message ?: "Zieldatei nicht schreibbar.")
        }
        return synchronized(this) {
            if (closed || active !== entry || entry.cancelled.get()) {
                entry.file.delete()
                Result.Cancelled
            } else {
                entry.phase = if (result == Result.Saved) Phase.SAVED else Phase.READY
                result
            }
        }
    }

    @Synchronized
    fun releaseSaved(id: String) {
        active?.takeIf { it.id == id && it.phase == Phase.SAVED }?.let(::discardEntry)
    }

    /** Explicitly ending the native dialog discards only its temporary export. */
    @Synchronized
    fun discard(id: String) {
        active?.takeIf { it.id == id && it.phase == Phase.READY }?.let(::discardEntry)
    }

    /** Transfer ownership to the receiving app's read grant; retain bytes beyond activity exit. */
    @Synchronized
    fun handedOff(id: String): Boolean {
        val entry = active?.takeIf { !closed && it.id == id && it.phase == Phase.READY } ?: return false
        entry.file.setLastModified(now())
        active = null
        return true
    }

    @Synchronized
    fun abortReceiving() {
        active?.takeIf { it.phase == Phase.RECEIVING }?.let(::discardEntry)
    }

    private fun discardEntry(entry: Entry) {
        entry.cancelled.set(true)
        runCatching { entry.output?.close() }
        entry.output = null
        entry.file.delete()
        if (active === entry) active = null
    }

    @Synchronized
    override fun close() {
        closed = true
        active?.let { entry ->
            entry.cancelled.set(true)
            if (entry.phase != Phase.COPYING) discardEntry(entry)
        }
        active = null
    }

    companion object {
        const val MAX_BLOCK_BYTES = 262144
        const val MAX_EXPORT_BYTES = 300L * 1024 * 1024
        const val SHARE_RETENTION_MS = 24L * 60 * 60 * 1000
    }
}
