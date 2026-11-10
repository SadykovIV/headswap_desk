package com.headswap.desk.dex

/** Owns one pairing connection and serializes cancellation against saving its result. */
internal class PairingRequest : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var connection: AutoCloseable? = null

    fun attach(value: AutoCloseable) {
        val accepted = synchronized(lock) {
            if (closed) false else {
                check(connection == null) { "Pairing request already has a connection" }
                connection = value
                true
            }
        }
        if (!accepted) { value.close(); throw InterruptedException() }
    }

    fun commit(action: () -> Unit) = synchronized(lock) {
        if (closed || Thread.currentThread().isInterrupted) throw InterruptedException()
        action()
    }

    override fun close() {
        val owned = synchronized(lock) {
            closed = true
            connection.also { connection = null }
        }
        runCatching { owned?.close() }
    }
}
