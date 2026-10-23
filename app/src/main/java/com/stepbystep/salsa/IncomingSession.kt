package com.stepbystep.salsa

/** Owns one incoming action from reservation until dispatch completes or is cancelled. */
internal class IncomingSession<T> : AutoCloseable {
    data class Ticket<T>(val id: Long, val action: T, val dispatchNow: Boolean)

    private data class Entry<T>(val id: Long, val action: T, var dispatched: Boolean, var committed: Boolean = false)
    private var current: Entry<T>? = null
    private var shellReady = false
    private var closed = false
    private var nextId = 0L

    /** Reserve before asking the WebView whether its current screen can receive. */
    @Synchronized
    fun offer(action: T): Ticket<T>? {
        if (closed || current != null) return null
        val entry = Entry(++nextId, action, shellReady)
        current = entry
        return Ticket(entry.id, entry.action, entry.dispatched)
    }

    /** Repeated readiness callbacks never dispatch an already claimed action twice. */
    @Synchronized
    fun ready(): Ticket<T>? {
        if (closed) return null
        shellReady = true
        val entry = current?.takeIf { !it.dispatched } ?: return null
        entry.dispatched = true
        return Ticket(entry.id, entry.action, true)
    }

    /** A new document must report readiness again; this does not replay the old action. */
    @Synchronized
    fun notReady() {
        shellReady = false
    }

    @Synchronized
    fun active(id: Long): Boolean = !closed && current?.id == id

    /** Linearizes applying the prepared action against cancellation from the native UI. */
    @Synchronized
    fun claim(id: Long): Boolean {
        val entry = current?.takeIf { !closed && it.id == id && !it.committed } ?: return false
        entry.committed = true
        return true
    }

    @Synchronized
    fun finish(id: Long): Boolean {
        if (closed || current?.id != id) return false
        current = null
        return true
    }

    @Synchronized
    fun cancel(id: Long): Boolean {
        if (closed || current?.id != id || current?.committed == true) return false
        current = null
        return true
    }

    @Synchronized
    override fun close() {
        closed = true
        shellReady = false
        current = null
    }
}
