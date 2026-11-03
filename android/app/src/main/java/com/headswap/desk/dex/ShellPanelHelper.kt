package com.headswap.desk.dex

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.SurfaceControl
import androidx.annotation.Keep
import com.headswap.desk.cluster.ShellManeuverSample
import com.headswap.desk.cluster.ShellYandexGuidance
import dalvik.system.InMemoryDexClassLoader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/**
 * Authenticated app_process helper. IME policy is confined to the app's trusted virtual
 * displays; the optional physical-panel commands require an explicit --screen launch.
 */
@Keep
object ShellPanelHelper {
    private const val TAG = "HeadSwapDesk"

    @JvmStatic fun main(args: Array<String>) {
        try {
            check(Process.myUid() == 2000 && args.size in setOf(3, 5) && args[2] in setOf("ime", "ime-and-panel"))
            val uid = args[0].toInt()
            val token = args[1]
            val allowPanel = args[2] == "ime-and-panel" && DelegatedPanelAccess.usesShellBackend()
            require(uid >= 10000 && uid % 100000 >= 10000 && PhoneActivationPolicy.validToken(token))
            @Suppress("DEPRECATION")
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val pinned = if (args.size == 5) args[3].toInt().also { require(it > 1) } to
                args[4].also { require(it.toLong() > 0) } else null
            listen(uid, token, allowPanel, pinned)
            System.out.println("CLOSED")
            System.out.flush()
        } catch (e: Exception) { Log.w(TAG, "Panel helper ended: ${e.javaClass.simpleName}: ${e.message}") }
        catch (e: LinkageError) { Log.w(TAG, "Panel system API unavailable", e) }
        finally {
            // Restoration and socket cleanup have already run. Do not unload system-server
            // native libraries in an ordinary app_process VM during normal Java teardown.
            Process.killProcess(Process.myPid())
        }
    }

    private fun listen(uid: Int, token: String, allowPanel: Boolean, expected: Pair<Int, String>?) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var owner: Pair<Int, String>? = null
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(RemotePanelWire.loopback, RemotePanelWire.port(token)), 4)
            server.soTimeout = 1000
            while (true) {
                val pinned = owner
                if (pinned == null && SystemClock.elapsedRealtime() >= deadline) return
                if (expected != null && startTicks(expected.first) != expected.second) return
                if (pinned != null && startTicks(pinned.first) != pinned.second) return
                val peer = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                peer.use { socket ->
                    socket.soTimeout = 1000
                    socket.tcpNoDelay = true
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    val id = try { authenticate(uid, token, input, output, pinned ?: expected) } catch (_: Exception) { null }
                    if (id != null) {
                        if (owner == null) {
                            System.out.println("PIN:${id.first}:${id.second}")
                            System.out.flush()
                        }
                        owner = id
                        runCatching { LegacyDensityRecovery.run(uid) }
                            .onFailure { Log.w(TAG, "Could not complete legacy DPI recovery", it) }
                        try {
                            if (serve(socket, input, output, uid, allowPanel)) return
                        } catch (_: java.io.IOException) { /* The owner can reconnect after a network change. */ }
                    }
                }
            }
        }
    }

    // Field 22 in /proc/PID/stat distinguishes a restarted app from our activation owner.
    private fun startTicks(pid: Int) = runCatching {
        File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ').getOrNull(19)
            ?.takeIf { it.toLongOrNull() != null }
    }.getOrNull()

    private fun authenticate(uid: Int, token: String, input: DataInputStream, output: DataOutputStream,
        pinned: Pair<Int, String>?): Pair<Int, String>? {
        val pid = input.readInt()
        if (pid <= 1 || pid == Process.myPid()) return null
        val owner = pid to (startTicks(pid) ?: return null)
        if (pinned != null && owner != pinned) return null
        val client = ByteArray(RemotePanelWire.NONCE_BYTES).also(input::readFully)
        val server = RemotePanelWire.nonce()
        output.write(server); output.write(RemotePanelWire.proof(token, 1, pid, client, server)); output.flush()
        val proof = ByteArray(RemotePanelWire.MAC_BYTES).also(input::readFully)
        if (!RemotePanelWire.validProof(RemotePanelWire.proof(token, 2, pid, client, server), proof)) return null
        // The HMAC identity is independently pinned to HeadSwap's current main process, never a
        // companion, :service process, different app user, or PID named solely by the client.
        val status = File("/proc/$pid/status").useLines { lines -> lines.firstOrNull { it.startsWith("Uid:") } }
        val uids = status?.substringAfter(':')?.trim()?.split(Regex("\\s+")) ?: return null
        if (uids.size != 4 || uids.any { it.toIntOrNull() != uid }) return null
        val cmdline = File("/proc/$pid/cmdline").inputStream().use { stream ->
            val bytes = ByteArray(128)
            val n = stream.read(bytes)
            if (n <= 0) "" else String(bytes, 0, n, Charsets.UTF_8).substringBefore('\u0000')
        }
        if (cmdline != ManualAccessPolicy.PACKAGE) return null
        output.writeByte(RemotePanelWire.OK); output.flush()
        return owner
    }

    private fun serve(socket: Socket, input: DataInputStream, output: DataOutputStream, ownerUid: Int, allowPanel: Boolean): Boolean {
        val panel by lazy { check(allowPanel); PhysicalPanel() }
        val guidance = Executors.newSingleThreadExecutor { Thread(it, "headswap-hud-card").apply { isDaemon = true } }
        val reading = AtomicBoolean()
        fun acknowledge(value: Int) = synchronized(output) { output.writeByte(value); output.flush() }
        fun reply(id: Int, sample: ShellManeuverSample) = synchronized(output) {
            output.writeByte(RemotePanelWire.GUIDANCE_REPLY)
            output.writeInt(id)
            ShellGuidanceWire.write(output, sample)
            output.flush()
        }
        socket.soTimeout = 350
        var deadline = 0L
        fun restore() {
            if (deadline == 0L) return
            panel.restore()
            deadline = 0L
        }
        try {
            while (true) {
                if (deadline != 0L && SystemClock.elapsedRealtime() >= deadline) restore()
                val op = try { input.read() } catch (_: SocketTimeoutException) { continue }
                if (op < 0) return false
                if (op == RemotePanelWire.END_ACTIVATION) return true
                try {
                    if (op == RemotePanelWire.READ_GUIDANCE) {
                        val id = input.readInt()
                        val displayId = input.readInt()
                        if (displayId <= 0 || !reading.compareAndSet(false, true)) {
                            reply(id, ShellManeuverSample.Unavailable)
                        } else guidance.execute {
                            try {
                                val sample = try { ShellYandexGuidance.read(displayId, ownerUid, ownerUid / 100000) }
                                catch (_: Exception) { ShellManeuverSample.Unavailable }
                                catch (_: LinkageError) { ShellManeuverSample.Unavailable }
                                reading.set(false)
                                reply(id, sample)
                            } catch (_: java.io.IOException) { /* activation ended */ }
                        }
                        continue
                    }
                    if (op == RemotePanelWire.LOCAL_IME || op == RemotePanelWire.IME_VISIBLE) {
                        val target = input.readLong()
                        val reply = try {
                            require(target in 1..Int.MAX_VALUE.toLong())
                            if (op == RemotePanelWire.LOCAL_IME) {
                                ShellDisplayIme.enable(target.toInt(), ownerUid)
                                RemotePanelWire.OK
                            } else if (ShellDisplayIme.isVisible(target.toInt(), ownerUid)) RemotePanelWire.OK
                            else RemotePanelWire.HIDDEN
                        } catch (e: Exception) {
                            Log.w(TAG, "Local IME unavailable for display $target", e); RemotePanelWire.UNAVAILABLE
                        } catch (e: LinkageError) { Log.w(TAG, "Local IME API unavailable", e); RemotePanelWire.UNAVAILABLE }
                        acknowledge(reply)
                        continue
                    }
                    check(allowPanel) { "Управление физической панелью не запрашивалось" }
                    when (op) {
                        RemotePanelWire.PREPARE -> {
                            check(deadline == 0L)
                            val physicalId = input.readLong()
                            // A vendor without panel-power support can still route its keyboard.
                            val available = try { panel.prepare(physicalId); true }
                            catch (e: Exception) { Log.w(TAG, "Panel preparation unavailable", e); false }
                            catch (e: LinkageError) { Log.w(TAG, "Panel API unavailable", e); false }
                            if (!available) {
                                acknowledge(RemotePanelWire.UNAVAILABLE); continue
                            }
                        }
                        RemotePanelWire.OFF -> {
                            check(deadline == 0L)
                            deadline = SystemClock.elapsedRealtime() + RemotePanelWire.LEASE_MS
                            panel.off()
                        }
                        RemotePanelWire.RENEW -> {
                            val now = SystemClock.elapsedRealtime()
                            check(deadline != 0L && now < deadline) { "Истёк запрос выключения" }
                            panel.requireCurrent()
                            deadline = now + RemotePanelWire.LEASE_MS
                        }
                        RemotePanelWire.RESTORE -> restore()
                        else -> error("Неизвестный запрос панели")
                    }
                    acknowledge(RemotePanelWire.OK)
                } catch (error: Exception) {
                    Log.w(TAG, "Panel request failed", error)
                    runCatching { acknowledge(RemotePanelWire.FAILED) }
                    return false
                }
            }
        } finally {
            guidance.shutdownNow()
            // Also reset on socket failure, app exit, or a hung app that stopped renewing.
            // Power follows the current logical display state, including OFF and DOZE.
            for (attempt in 0..2) {
                if (deadline == 0L) break
                try { restore() }
                catch (e: Exception) { Log.w(TAG, "Panel restore failed", e); SystemClock.sleep(100) }
            }
        }
    }

    @SuppressLint("PrivateApi", "BlockedPrivateApi")
    private class PhysicalPanel {
        private val manager = systemDisplayManager()
        private val info = Class.forName("android.hardware.display.IDisplayManager").getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
        private val surface = SurfaceControl::class.java
        private val getToken by lazy { physicalPanelTokenMethod(surface) { wrappers().loadClass("com.genymobile.scrcpy.wrappers.DisplayControl") } }
        private val power = surface.getMethod("setDisplayPowerMode", IBinder::class.java, Int::class.javaPrimitiveType)
        private var physical: Long? = null
        private var token: IBinder? = null

        fun prepare(expected: Long) {
            check(expected != -1L)
            val (_, actual) = primary()
            check(actual == expected) { "Изменилась внутренняя панель" }
            val newToken = checkNotNull(getToken.invoke(null, actual) as? IBinder) { "Нет токена внутренней панели" }
            physical = actual
            token = newToken
        }
        fun requireCurrent(): Int {
            val (state, id) = primary()
            check(id == physical && token != null) { "Изменилась внутренняя панель" }
            return state
        }
        fun off() {
            check(requireCurrent() == Display.STATE_ON) { "Внутренний дисплей не активен" }
            power.invoke(null, token, 0)
        }
        fun restore() {
            val mode = when (requireCurrent()) {
                Display.STATE_ON -> 2
                Display.STATE_OFF -> 0
                Display.STATE_DOZE -> 1
                Display.STATE_DOZE_SUSPEND -> 3
                Display.STATE_ON_SUSPEND -> 4
                else -> error("Неизвестно системное состояние внутренней панели")
            }
            power.invoke(null, token, mode)
        }
        private fun primary(): Pair<Int, Long> {
            val display = checkNotNull(info.invoke(manager, Display.DEFAULT_DISPLAY))
            val type = display.javaClass
            check(type.getField("type").getInt(display) == 1) { "Дисплей 0 не является внутренним" }
            val addr = checkNotNull(type.getField("address").get(display))
            val id = physicalPanelIdFromAddress(addr)
            return type.getField("state").getInt(display) to id
        }
        private fun wrappers(): ClassLoader {
            // CLASSPATH was set by the launcher to the installed package's base.apk.
            val path = checkNotNull(System.getenv("CLASSPATH"))
            val archive = ZipFile(path).use { zip ->
                val member = checkNotNull(zip.getEntry("assets/dex/scrcpy-server-v4.1"))
                require(member.size in 1..DexUserServicePolicy.MAX_CODE_BYTES.toLong())
                ByteArray(member.size.toInt()).also { bytes ->
                    DataInputStream(zip.getInputStream(member)).use { it.readFully(bytes); check(it.read() == -1) }
                }
            }
            val dex = DexUserServicePolicy.verifiedDex(archive)
            return InMemoryDexClassLoader(ByteBuffer.wrap(dex), ShellPanelHelper::class.java.classLoader)
        }
    }
}
