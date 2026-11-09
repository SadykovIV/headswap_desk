package com.headswap.desk.dex

import android.annotation.TargetApi
import android.companion.AssociationInfo
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** All native calls run in their real shell owner. No cross-UID display callback tokens escape. */
class VdmHostCore private constructor(base: Context, private val clientUid: Int, private val fatal: () -> Unit) {
    init {
        check(Build.VERSION.SDK_INT >= 37) { "The independent VDM host requires Android 17" }
    }
    private val lock = Any()
    private val context = ShellContext(base)
    private val user = Process.myUid() / 100000
    private val lockFile = RandomAccessFile(File("/data/local/tmp/headswap-desk-vdm-$user.lock"), "rw")
    private val fileLease: FileLock = try { checkNotNull(lockFile.channel.tryLock()) { "Another HeadSwap Desktop VDM host is alive" } }
        catch (error: Throwable) { lockFile.close(); throw error }
    private var associationId = -1
    private var active: Session? = null
    private var closed = false
    private val retiring = AtomicBoolean()

    init {
        check(Process.myUid() == VdmHostPolicy.SHELL_UID)
        try { associationId = ensureAssociation() }
        catch (error: Throwable) { fileLease.release(); lockFile.close(); throw error }
    }

    fun openSession(producer: IBinder, producerPid: Int, closeCoreOnClose: Boolean = false): IVdmDesktopSession = boundary {
        requireCaller()
        require(producerPid > 0 && producer.isBinderAlive) { "Producer is unavailable" }
        synchronized(lock) {
            check(!closed && active == null) { "Another VDM producer is active" }
            val created = Session(producer, closeCoreOnClose)
            active = created
            try { producer.linkToDeath(created.producerDeath, 0) }
            catch (error: Throwable) { active = null; created.finishNative(); throw error }
            created
        }
    }
    fun ownsDisplay(id: Int): Boolean {
        requireCaller()
        return synchronized(lock) { active?.slots?.containsKey(id) == true }
    }
    fun close(): Unit = boundary {
        synchronized(lock) {
            if (closed) return@boundary
            active?.finishNative() // Failure must not be converted to a successful release ACK.
            active = null
            closed = true
            try { removeOwnAssociation() }
            finally { fileLease.release(); lockFile.close() }
        }
    }
    private fun requireCaller() {
        val uid = Binder.getCallingUid()
        if (uid != clientUid && uid != VdmHostPolicy.SHELL_UID) throw SecurityException("Foreign VDM caller")
    }
    private fun retireOwner(): Nothing {
        // The death callback must not wait forever for a native call holding our lock.
        if (retiring.compareAndSet(false, true)) {
            Thread({ Thread.sleep(2_000); Process.killProcess(Process.myPid()) }, "headswap-vdm-retire").apply { isDaemon = true; start() }
        }
        try { close() } finally { fatal(); Process.killProcess(Process.myPid()) }
        throw IllegalStateException("VDM owner retired")
    }
    private inline fun <T> asOwner(action: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        try { return boundary(action) } finally { Binder.restoreCallingIdentity(identity) }
    }
    private inline fun <T> boundary(action: () -> T): T {
        try { return action() }
        catch (error: RuntimeException) { throw error }
        catch (error: Exception) {
            // Binder.execTransact catches RemoteException/RuntimeException, not checked reflection
            // errors. Never leak InvocationTargetException through Kotlin's unchecked signature.
            throw IllegalStateException("VDM platform operation failed (${error.javaClass.simpleName})")
        }
    }
    private inner class Session(private val producer: IBinder, private val closeCoreOnClose: Boolean) : IVdmDesktopSession.Stub() {
        val slots = LinkedHashMap<Int, Slot>()
        private val lifetime = Binder()
        private val device: Any = try { asOwner { newDevice() } } catch (_: Throwable) { retireOwner() }
        private var ended = false
        private var group = -1
        private var parentId = -1
        val producerDeath = IBinder.DeathRecipient { retireOwner() }
        private fun requireLive() {
            requireCaller()
            check(!ended && !closed && active === this) { "VDM session has ended" }
        }
        override fun createDisplay(surface: Surface, name: String, width: Int, height: Int, density: Int, flags: Int): Int {
            // AIDL creates a host-owned wrapper; an in-process Shizuku call passes its producer's
            // original object. Keep exactly one clone per native slot and retire only our input.
            val parcelInput = !closeCoreOnClose && Binder.getCallingPid() != Process.myPid()
            try { return createNativeDisplay(surface, name, width, height, density, flags) }
            finally { if (parcelInput) surface.release() }
        }
        // Every entry is gated by the enclosing host's Android 17 constructor check.
        @TargetApi(37)
        private fun createNativeDisplay(surface: Surface, name: String, width: Int, height: Int, density: Int, flags: Int): Int = synchronized(lock) {
            requireLive()
            VdmHostPolicy.requireGeometry(width, height, density)
            require(surface.isValid && DesktopImePolicy.isSupportedName(name)) { "Unknown HeadSwap display name" }
            check(slots.size < VdmHostPolicy.MAX_DISPLAYS) { "VDM display limit reached" }
            // Local Shizuku calls do not parcel the input. Keep our own Surface reference in both paths.
            val retained = cloneSurface(surface)
            var native: VirtualDisplay? = null
            try {
                native = asOwner {
                    val config = VirtualDisplayConfig.Builder(name, width, height, density)
                        .setSurface(retained).setFlags(VdmHostPolicy.displayFlags(flags)).build()
                    device.javaClass.getMethod("createVirtualDisplay", VirtualDisplayConfig::class.java,
                        Executor::class.java, VirtualDisplay.Callback::class.java).invoke(device, config, null, null) as VirtualDisplay
                }
                val id = native.display.displayId
                val actualGroup = groupOf(native.display)
                check(id > 0 && actualGroup > 0 && (group < 0 || actualGroup == group)) { "VDM display is not independently grouped" }
                check(!slots.containsKey(id))
                group = actualGroup
                if (parentId < 0) parentId = id
                slots[id] = Slot(native, retained)
                id
            } catch (error: Throwable) {
                if (native != null) {
                    try { asOwner { native.release() } }
                    catch (_: Throwable) { retireOwner() }
                    retained.release()
                } else {
                    // The framework may have allocated a display before throwing. Only retiring
                    // its real owner can prove all unknown native resources are gone.
                    retireOwner()
                }
                if (error is RuntimeException) throw error
                throw IllegalStateException("VDM display creation failed (${error.javaClass.simpleName})")
            }
        }
        override fun resizeDisplay(displayId: Int, width: Int, height: Int, density: Int): Unit = synchronized(lock) {
            requireLive(); VdmHostPolicy.requireGeometry(width, height, density)
            val slot = checkNotNull(slots[displayId]) { "Unknown VDM display" }
            asOwner { slot.display.resize(width, height, density) }
        }
        override fun releaseDisplay(displayId: Int): Unit = synchronized(lock) {
            requireCaller()
            if (ended) return@synchronized
            requireLive()
            if (displayId == parentId) {
                finishNative()
                active = null
                if (closeCoreOnClose) this@VdmHostCore.close()
            } else slots[displayId]?.let { slot ->
                asOwner { slot.display.release() }
                slots.remove(displayId)
                slot.surface.release()
            }
            Unit
        }
        override fun displayGroup(displayId: Int): Int = synchronized(lock) {
            requireCaller()
            if (ended || active !== this) -1 else slots[displayId]?.let { groupOf(it.display.display) } ?: -1
        }
        override fun ownsDisplay(displayId: Int): Boolean = synchronized(lock) {
            requireCaller(); !ended && active === this && slots.containsKey(displayId)
        }
        override fun getLifetime(): IBinder { requireCaller(); return lifetime }
        override fun close(): Unit = synchronized(lock) {
            requireCaller()
            if (ended) return@synchronized
            finishNative()
            if (active === this) active = null
            if (closeCoreOnClose) this@VdmHostCore.close()
        }
        fun finishNative() {
            if (ended) return
            // Children first, parent last. Remove entries only after the native release returns.
            for (id in slots.keys.toList().asReversed()) {
                val slot = checkNotNull(slots[id])
                asOwner { slot.display.release() }
                slots.remove(id)
                slot.surface.release()
            }
            asOwner { device.javaClass.getMethod("close").invoke(device) }
            ended = true
            producer.unlinkToDeath(producerDeath, 0)
        }
    }
    private data class Slot(val display: VirtualDisplay, val surface: Surface)
    private fun newDevice(): Any {
        val builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        builderClass.getMethod("setLockState", Int::class.javaPrimitiveType).invoke(builder, 1)
        builderClass.getMethod("setName", String::class.java).invoke(builder, VdmHostPolicy.DISPLAY_NAME)
        val params = builderClass.getMethod("build").invoke(builder)
        val api = Class.forName("android.companion.virtual.VirtualDeviceManager")
        val iface = Class.forName("android.companion.virtual.IVirtualDeviceManager")
        val remote = Class.forName("android.companion.virtual.IVirtualDeviceManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, service("virtualdevice"))
        val manager = api.getConstructor(iface, Context::class.java).newInstance(remote, context)
        return checkNotNull(api.getMethod("createVirtualDevice", Int::class.javaPrimitiveType, params.javaClass)
            .invoke(manager, associationId, params))
    }
    private fun associations(): List<AssociationInfo> {
        val remote = Class.forName("android.companion.ICompanionDeviceManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, service("companiondevice"))
        @Suppress("UNCHECKED_CAST")
        return remote.javaClass.getMethod("getAllAssociationsForUser", Int::class.javaPrimitiveType).invoke(remote, user) as List<AssociationInfo>
    }
    private fun ownAssociation(): AssociationInfo? {
        val pkg = AssociationInfo::class.java.getMethod("getPackageName")
        val matching = associations().filter { VdmHostPolicy.ownAssociation(pkg.invoke(it) as String?, it.deviceProfile, it.deviceMacAddress?.toString()) }
        check(matching.size <= 1) { "Ambiguous HeadSwap Desktop association" }
        return matching.singleOrNull()
    }
    private fun ensureAssociation(): Int {
        ownAssociation()?.let { return it.id }
        // Generated aconfig class names are not stable client APIs. Both service parser branches
        // parse this deliberately invalid MAC before any creation or role grant can occur.
        val before = associationFingerprint()
        val probe = runProbeCommand(VdmHostPolicy.parserProbeCommand(user))
        check(before == associationFingerprint()) { "Companion associations changed during the parser probe" }
        val named = checkNotNull(VdmHostPolicy.parserFromProbe(probe.first, probe.second)) { "Unsupported companion command parser" }
        check(runCommand(VdmHostPolicy.associateCommand(user, named)) == 0) { "CDM rejected the association command" }
        val until = SystemClock.elapsedRealtime() + 5_000
        do {
            ownAssociation()?.let { return it.id }
            Thread.sleep(60)
        } while (SystemClock.elapsedRealtime() < until)
        error("Android did not create the HeadSwap Desktop companion association")
    }
    private fun removeOwnAssociation() {
        val own = ownAssociation() ?: return
        check(own.id == associationId) { "Association owner changed" }
        // The Binder API takes the exact ID; do not use package-wide or role-wide cleanup.
        val remote = Class.forName("android.companion.ICompanionDeviceManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, service("companiondevice"))
        asOwner { remote.javaClass.getMethod("disassociate", Int::class.javaPrimitiveType).invoke(remote, own.id) }
    }
    private fun runCommand(command: List<String>): Int {
        val process = ProcessBuilder(command).redirectOutput(File("/dev/null")).redirectError(File("/dev/null")).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); error("CDM command timed out") }
        return process.exitValue()
    }
    private fun associationFingerprint(): List<String> = associations().sortedBy { it.id }.map { it.toString() }
    private fun runProbeCommand(command: List<String>): Pair<Int, String> {
        val output = File.createTempFile("headswap-desk-cdm-", ".txt", File("/data/local/tmp"))
        var process: java.lang.Process? = null
        try {
            process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start()
            check(process.waitFor(2, TimeUnit.SECONDS)) { "CDM parser probe timed out" }
            check(output.length() in 1..16_384) { "Unexpected CDM parser response" }
            // Only a deliberately invalid argument and stack frames; never log the response.
            return process.exitValue() to output.readText(Charsets.UTF_8)
        } finally {
            if (process?.isAlive == true) process.destroyForcibly()
            output.delete()
        }
    }
    private fun groupOf(display: Display): Int = boundary {
        val infoType = Class.forName("android.view.DisplayInfo")
        val info = infoType.getConstructor().newInstance()
        check(Display::class.java.getMethod("getDisplayInfo", infoType).invoke(display, info) == true)
        infoType.getField("displayGroupId").getInt(info)
    }
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = VdmHostPolicy.SHELL_PACKAGE
        override fun getOpPackageName() = VdmHostPolicy.SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(Process.myUid()).setPackageName(packageName).build()
    }
    companion object {
        internal fun create(context: Context, clientUid: Int, fatal: () -> Unit): VdmHostCore = VdmHostCore(context, clientUid, fatal)
        @JvmStatic fun openLocalSession(context: Context, producerLifetime: IBinder, producerPid: Int, clientUid: Int): IVdmDesktopSession {
            val core = VdmHostCore(context, clientUid) { Process.killProcess(Process.myPid()) }
            try { return core.openSession(producerLifetime, producerPid, closeCoreOnClose = true) }
            catch (error: Throwable) { runCatching { core.close() }; throw error }
        }
        private fun cloneSurface(source: Surface): Surface {
            val parcel = Parcel.obtain()
            try { source.writeToParcel(parcel, 0); parcel.setDataPosition(0); return Surface.CREATOR.createFromParcel(parcel) }
            finally { parcel.recycle() }
        }
        internal fun service(name: String): IBinder = checkNotNull(Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, name) as IBinder?)
    }
}
