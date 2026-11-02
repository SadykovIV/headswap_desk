package com.headswap.desk.dex

import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.view.InputEvent
import android.view.Surface

/** The lease owner is shared; platform access stays separate for shell and a registered app process. */
internal interface DesktopRuntimeAccess {
    fun createDisplay(surface: Surface, width: Int, height: Int, density: Int, mode: Int): VirtualDisplay
    fun createChildDisplay(surface: Surface, width: Int, height: Int, density: Int): VirtualDisplay
    fun inject(event: InputEvent, id: Int, actionButton: Int = 0)
    fun childTaskState(id: Int, packageName: String): Int
    /** Capture backend ownership on the display thread; query Android on a separate worker. */
    fun childTaskQuery(id: Int, packageName: String): () -> Int = { childTaskState(id, packageName) }
    fun registerParentDisplay(id: Int, token: IBinder, width: Int, height: Int) {}
    fun registerChildDisplay(parentId: Int, id: Int, token: IBinder, width: Int, height: Int) {}
    fun resizeRegisteredDisplay(id: Int, token: IBinder, width: Int, height: Int) {}
    fun unregisterDisplay(id: Int, token: IBinder) {}
    fun eligible(id: Int): Boolean = false
    fun launchDesktop(mode: Int, id: Int): Unit = unsupported()
    fun launchChild(parentId: Int, id: Int, packageName: String, className: String, desktopPane: Boolean): Unit = unsupported()
    fun setPhonePanelOff(parentId: Int, off: Boolean): Unit = unsupported()
    fun primaryPanelState(): Int = unsupported()
    fun phoneUnlocked(): Boolean = unsupported()
    fun newPanelWakeLock(): DexWakeLease = unsupported()
    fun internalPanelToken(): IBinder = unsupported()
    fun setPanelPower(token: IBinder, on: Boolean): Unit = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Операция недоступна для выбранной службы рабочего стола")
}
