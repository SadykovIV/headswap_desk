package com.headswap.desk.dex

import android.os.Process
import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Main instrumentation process only. Blocking loopback I/O stays on the worker, never Android's UI thread. */
internal object ShellPanelClient {
    private val executor = Executors.newSingleThreadExecutor { job -> Thread(job, "headswap-panel-client").apply { isDaemon = true } }
    @Volatile private var connection: Session? = null
    private var generation = 0L
    val isConnected: Boolean get() = connection?.alive == true

    fun connect(token: String) {
        require(PhoneActivationPolicy.validToken(token)) { "Нет идентификатора активации панели" }
        val ticket = synchronized(this) { close(); generation }
        work(10_000) {
            val until = SystemClock.elapsedRealtime() + 7_000
            while (true) {
                val s = Socket()
                try {
                    s.connect(InetSocketAddress(RemotePanelWire.loopback, RemotePanelWire.port(token)), 300)
                    val session = Session(s)
                    session.authenticate(token)
                    synchronized(this) {
                        check(ticket == generation) { "Активация панели завершена" }
                        connection = session
                    }
                    session.startReader()
                    break
                } catch (error: Exception) {
                    runCatching { s.close() }
                    if (error !is java.net.ConnectException || SystemClock.elapsedRealtime() >= until) throw error
                    Thread.sleep(70)
                }
            }
        }
    }

    fun prepare(physicalId: Long) = command(RemotePanelWire.PREPARE, physicalId)
    fun power(on: Boolean) = command(if (on) RemotePanelWire.RESTORE else RemotePanelWire.OFF)
    fun refresh() = command(RemotePanelWire.RENEW)
    @Synchronized fun close() {
        generation++
        val s = connection
        connection = null
        s?.shutdown()
    }

    private fun command(op: Int, physical: Long? = null) {
        // PREPARE may cold-load the vendor system-server library. It cannot switch the panel off.
        val responseMs = if (op == RemotePanelWire.PREPARE) 10_000L else 1_000L
        work(responseMs + 500L) {
            val s = connection ?: throw IOException("Связь с управлением панелью прервана")
            try { s.call(op, physical, responseMs) }
            catch (error: Exception) { s.shutdown(); throw error }
        }
    }

    private fun work(limit: Long, body: () -> Unit) {
        val task = executor.submit(Callable { body() })
        try { task.get(limit, TimeUnit.MILLISECONDS) }
        catch (e: Exception) {
            task.cancel(true)
            close()
            throw IOException("Служба панели не подтвердила запрос: ${e.cause?.message ?: e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private class Session(private val socket: Socket) {
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())
        private val ack = ArrayBlockingQueue<Int>(1)
        @Volatile var alive = true
            private set

        fun authenticate(token: String) {
            socket.tcpNoDelay = true
            socket.soTimeout = 1_500
            val pid = Process.myPid()
            val client = RemotePanelWire.nonce()
            output.writeInt(pid); output.write(client); output.flush()
            val server = ByteArray(RemotePanelWire.NONCE_BYTES).also(input::readFully)
            val proof = ByteArray(RemotePanelWire.MAC_BYTES).also(input::readFully)
            check(RemotePanelWire.validProof(RemotePanelWire.proof(token, 1, pid, client, server), proof)) { "Неизвестная служба панели" }
            output.write(RemotePanelWire.proof(token, 2, pid, client, server)); output.flush()
            check(input.readUnsignedByte() == RemotePanelWire.OK) { "Служба панели не подтвердила процесс приложения" }
            socket.soTimeout = 0
        }
        fun startReader() {
            Thread({
                try {
                    while (alive) {
                        val result = input.readUnsignedByte()
                        check(ack.offer(result))
                    }
                } catch (_: Exception) { } finally { shutdown() }
            }, "headswap-panel-replies").apply { isDaemon = true; start() }
        }
        fun call(op: Int, physical: Long?, responseMs: Long) {
            check(alive)
            output.writeByte(op)
            if (physical != null) output.writeLong(physical)
            output.flush()
            val reply = ack.poll(responseMs, TimeUnit.MILLISECONDS)
                ?: throw IOException("Истекло время ожидания ответа от службы панели")
            check(reply == RemotePanelWire.OK) { "Служба панели отклонила операцию" }
        }
        fun shutdown() { alive = false; runCatching { socket.close() }; ack.offer(RemotePanelWire.FAILED) }
    }
}
