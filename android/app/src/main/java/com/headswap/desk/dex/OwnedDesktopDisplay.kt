package com.headswap.desk.dex

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.view.Display
import android.view.Surface

/** A release acknowledgement always belongs to the process which owns the native producer. */
internal interface OwnedDesktopDisplay {
    val display: Display
    val nativeLifetime: IBinder
    val family: VdmDisplayFamily? get() = null
    fun resize(width: Int, height: Int, density: Int)
    fun release()
}

internal class LocalDesktopDisplay(private val native: VirtualDisplay,
    override val nativeLifetime: IBinder) : OwnedDesktopDisplay {
    override val display: Display get() = native.display
    override fun resize(width: Int, height: Int, density: Int) = native.resize(width, height, density)
    override fun release() = native.release()
}

/** The caller must retire its producer process, retaining consumer surfaces until native death. */
internal class NativeDisplayReleaseUnconfirmed(cause: Throwable) : IllegalStateException(
    "Служба независимого экрана не подтвердила освобождение поверхности", cause)

/** Session selection is immutable: children cannot silently escape to the phone's display group. */
internal object DesktopDisplayBackendPolicy {
    fun requiresVdm(sdk: Int, mode: Int): Boolean = sdk >= 37 && mode != DesktopModePolicy.SAMSUNG_DEX
    fun requireGroup(parentGroup: Int?, group: Int) {
        check(group > 0 && (parentGroup == null || group == parentGroup)) {
            "Android не подтвердил независимую группу рабочего стола"
        }
    }
}

internal class VdmDisplayFamily(context: Context, private val session: IVdmDesktopSession) {
    private val displays = checkNotNull(context.getSystemService(DisplayManager::class.java))
    val lifetime: IBinder = checkNotNull(session.lifetime).also {
        check(it.isBinderAlive) { "Служба независимого экрана завершена" }
    }
    private var parentGroup: Int? = null
    private var closed = false

    fun create(name: String, surface: Surface, width: Int, height: Int, density: Int,
        flags: Int, parent: Boolean): OwnedDesktopDisplay {
        check(!closed && lifetime.isBinderAlive) { "Сеанс независимого экрана завершён" }
        check(parent == (parentGroup == null)) { "Неверный порядок создания экранов" }
        var id = -1
        try {
            id = session.createDisplay(surface, name, width, height, density, flags)
            check(id > 0 && session.ownsDisplay(id)) { "Служба не подтвердила собственный экран" }
            val display = checkNotNull(displays.getDisplay(id)) { "Созданный экран недоступен" }
            check(display.isValid && display.flags and 128 != 0) { "Android отклонил доверие экрана" }
            val group = session.displayGroup(id)
            DesktopDisplayBackendPolicy.requireGroup(parentGroup, group)
            // The ordinary producer process cannot use hidden DisplayInfo APIs. The
            // authenticated native owner reports its actual group; main verifies it again.
            check(DesktopDisplayIsolation.independent(display.flags, group)) {
                "Android не подтвердил независимую блокировку экрана"
            }
            if (parent) parentGroup = group
            return RemoteDisplay(id, parent)
        } catch (error: Throwable) {
            // Unknown create outcome is not an ACK. Keep the host's producer-death link
            // intact; terminating the producer makes the native host close and exit.
            if (id <= 0 && lifetime.isBinderAlive) throw NativeDisplayReleaseUnconfirmed(error)
            try {
                if (parent) close()
                else if (lifetime.isBinderAlive) session.releaseDisplay(id)
            } catch (cleanup: Throwable) {
                cleanup.addSuppressed(error)
                throw NativeDisplayReleaseUnconfirmed(cleanup)
            }
            throw error
        }
    }

    fun close() {
        if (closed) return
        if (lifetime.isBinderAlive) session.close()
        closed = true // Only native close ACK or actual native process death reaches this line.
    }

    private inner class RemoteDisplay(private val id: Int, private val parent: Boolean) : OwnedDesktopDisplay {
        private var released = false
        override val family: VdmDisplayFamily get() = this@VdmDisplayFamily
        override val nativeLifetime: IBinder get() = lifetime
        override val display: Display get() = checkNotNull(displays.getDisplay(id)) { "Экран уже закрыт" }
        override fun resize(width: Int, height: Int, density: Int) {
            check(!released && !closed && lifetime.isBinderAlive && session.ownsDisplay(id)) { "Экран уже закрыт" }
            session.resizeDisplay(id, width, height, density)
            DesktopDisplayBackendPolicy.requireGroup(parentGroup, session.displayGroup(id))
        }
        override fun release() {
            if (released) return
            if (parent) close()
            else if (!closed && lifetime.isBinderAlive) session.releaseDisplay(id)
            released = true
        }
    }
}
