package com.headswap.desk.dex

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.headswap.desk.cluster.ShellManeuverSample
import com.headswap.desk.embed.EmbeddedSessionRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Main instrumentation process only. Blocking loopback I/O stays on the worker, never Android's UI thread. */
internal object ShellPanelClient {
    private val executor = Executors.newSingleThreadExecutor { job -> Thread(job, "headswap-panel-client").apply { isDaemon = true } }
    @Volatile private var connection: Session? = null
    private var generation = 0L
    val isConnected: Boolean get() = connection?.alive == true
    // Identity changes on every successful authentication, including a reconnect within one heartbeat.
    internal val imeConnection: Any? get() = connection?.takeIf { it.alive }

    fun connect(token: String) {
        require(PhoneActivationPolicy.validToken(token)) { "Нет идентификатора активации панели" }
        val ticket = synchronized(this) { close(); generation }
        work(10_000) {
            val until = SystemClock.elapsedRealtime() + 7_000
            while (true) {
                try { open(token, ticket); break }
                catch (e: java.net.ConnectException) {
                    if (SystemClock.elapsedRealtime() >= until) throw e
                    Thread.sleep(70)
                }
            }
        }
    }

    /** Called on the activation heartbeat, including when the phone returns from standby. */
    fun reconnect(token: String) {
        if (isConnected) return
        val ticket = synchronized(this) { generation }
        try { work(2_500) { if (!isConnected) open(token, ticket) } }
        catch (_: IOException) { /* Retry on the next heartbeat of this activation. */ }
    }

    private fun open(token: String, ticket: Long) {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(RemotePanelWire.loopback, RemotePanelWire.port(token)), 300)
            val session = Session(socket)
            session.authenticate(token)
            synchronized(this) {
                check(ticket == generation) { "Активация панели завершена" }
                connection = session
            }
            session.startReader {
                if (synchronized(this) { generation == ticket && connection === session }) {
                    Log.w("HeadSwapDesk", "Display helper disconnected; restoring the activation channel")
                    executor.execute {
                        if (synchronized(this) { generation == ticket && connection === session }) {
                            try { open(token, ticket) }
                            catch (_: Exception) { /* The activation heartbeat will retry. */ }
                        }
                    }
                }
            }
        } catch (e: Exception) { socket.close(); throw e }
    }

    fun prepare(physicalId: Long) {
        check(command(RemotePanelWire.PREPARE, physicalId) == RemotePanelWire.OK) { "Служба не поддерживает управление панелью" }
    }
    fun power(on: Boolean) = command(if (on) RemotePanelWire.RESTORE else RemotePanelWire.OFF)
    fun refresh() = command(RemotePanelWire.RENEW)
    fun enableLocalIme(displayId: Int): Boolean = imeRequest(RemotePanelWire.LOCAL_IME, displayId) == RemotePanelWire.OK
    fun imeVisible(displayId: Int): Boolean? = when (imeRequest(RemotePanelWire.IME_VISIBLE, displayId)) {
        RemotePanelWire.OK -> true
        RemotePanelWire.HIDDEN -> false
        else -> null
    }

    fun readGuidance(displayId: Int): ShellManeuverSample =
        EmbeddedSessionRegistry.readDelegatedGuidance(displayId) {
            connection?.takeIf { it.alive }?.readGuidance(displayId) ?: ShellManeuverSample.Unavailable
        }

    private fun imeRequest(op: Int, displayId: Int): Int {
        require(displayId > 0)
        if (!isConnected) return RemotePanelWire.UNAVAILABLE
        return try { command(op, displayId.toLong()) }
        catch (e: IOException) { Log.w("HeadSwapDesk", "Display IME helper unavailable", e); RemotePanelWire.UNAVAILABLE }
    }
    @Synchronized fun close() {
        generation++
        val s = connection
        connection = null
        s?.endActivation()
    }

    private fun command(op: Int, target: Long? = null): Int {
        // PREPARE may cold-load the vendor system-server library. It cannot switch the panel off.
        val responseMs = when (op) {
            RemotePanelWire.PREPARE -> 10_000L
            RemotePanelWire.LOCAL_IME -> 2_000L
            else -> 1_000L
        }
        return work(responseMs + 500L) {
            val s = connection ?: throw IOException("Связь с управлением панелью прервана")
            try { s.call(op, target, responseMs) }
            catch (error: Exception) { s.shutdown(); throw error }
        }
    }

    private fun <T> work(limit: Long, body: () -> T): T {
        val task = executor.submit(Callable { body() })
        try { return task.get(limit, TimeUnit.MILLISECONDS) }
        catch (e: Exception) {
            task.cancel(true)
            throw IOException("Служба панели не подтвердила запрос: ${e.cause?.message ?: e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private class Session(private val socket: Socket) {
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())
        private val ack = ArrayBlockingQueue<Int>(1)
        private val nextQuery = AtomicInteger()
        private val guidance = ConcurrentHashMap<Int, CompletableFuture<ShellManeuverSample>>()
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
        fun startReader(onClosed: () -> Unit) {
            Thread({
                try {
                    while (alive) {
                        val result = input.readUnsignedByte()
                        if (result == RemotePanelWire.GUIDANCE_REPLY) {
                            val id = input.readInt()
                            val sample = ShellGuidanceWire.read(input)
                            guidance.remove(id)?.complete(sample)
                        } else check(ack.offer(result))
                    }
                } catch (_: Exception) { } finally { shutdown(); onClosed() }
            }, "headswap-panel-replies").apply { isDaemon = true; start() }
        }
        fun readGuidance(displayId: Int): ShellManeuverSample {
            val id = nextQuery.incrementAndGet()
            val result = CompletableFuture<ShellManeuverSample>()
            guidance[id] = result
            try {
                synchronized(output) {
                    if (!alive) return ShellManeuverSample.Unavailable
                    output.writeByte(RemotePanelWire.READ_GUIDANCE)
                    output.writeInt(id)
                    output.writeInt(displayId)
                    output.flush()
                }
                return result.get(950, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) { /* missing card or ended activation */ }
            finally { guidance.remove(id) }
            return ShellManeuverSample.Unavailable
        }
        fun call(op: Int, target: Long?, responseMs: Long): Int {
            check(alive)
            synchronized(output) {
                output.writeByte(op)
                if (target != null) output.writeLong(target)
                output.flush()
            }
            val reply = ack.poll(responseMs, TimeUnit.MILLISECONDS)
                ?: throw IOException("Истекло время ожидания ответа от службы панели")
            check(reply == RemotePanelWire.OK || reply == RemotePanelWire.UNAVAILABLE &&
                op in setOf(RemotePanelWire.PREPARE, RemotePanelWire.LOCAL_IME, RemotePanelWire.IME_VISIBLE) ||
                op == RemotePanelWire.IME_VISIBLE && reply == RemotePanelWire.HIDDEN) { "Служба панели отклонила операцию" }
            return reply
        }
        fun endActivation() {
            try {
                synchronized(output) {
                    if (alive) { output.writeByte(RemotePanelWire.END_ACTIVATION); output.flush() }
                }
            } catch (_: IOException) { /* peer already lost */ }
            finally { shutdown() }
        }
        fun shutdown() {
            alive = false
            runCatching { socket.close() }
            guidance.values.forEach { it.complete(ShellManeuverSample.Unavailable) }
            ack.offer(RemotePanelWire.FAILED)
        }
    }
}
