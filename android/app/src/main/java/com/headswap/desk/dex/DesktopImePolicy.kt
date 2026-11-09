package com.headswap.desk.dex

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.view.WindowInsets
import android.view.WindowManager

/** Display names also form part of Android 13/14's persisted per-display settings key.
 * Use one name per simultaneous viewport, not an increasing session or Android display ID. */
internal object DesktopImePolicy {
    private val tileNames = listOf("HeadSwap navigator widget A", "HeadSwap navigator widget B")
    private const val fullscreenName = "HeadSwap fullscreen app"
    private val names = tileNames + fullscreenName + listOf(
        DesktopModePolicy.SAMSUNG_DEX, DesktopModePolicy.ANDROID_DESKTOP, DesktopModePolicy.DRIVEDECK
    ).map { "HeadSwap ${DesktopModePolicy.label(it)}" }

    fun childName(fullscreen: Boolean, used: Collection<String>): String =
        if (fullscreen) fullscreenName else tileNames.first { it !in used }

    fun isSupportedName(name: String): Boolean = name in names

    fun backTarget(parentId: Int, childIme: Map<Int, Boolean?>): Int {
        require(parentId > 0 && childIme.keys.all { it > 0 && it != parentId })
        if (childIme.values.any { it == null }) return parentId
        return childIme.entries.singleOrNull { it.value == true }?.key ?: parentId
    }

    fun requireOwned(id: Int, type: Int, flags: Int, actualUid: Int, expectedUid: Int, name: String?) {
        require(id > 0 && type == 5 /* Display.TYPE_VIRTUAL */ &&
            flags and DexUserServicePolicy.DISPLAY_TRUSTED != 0 && expectedUid > 0 &&
            actualUid == expectedUid && name in names) { "Ожидался собственный виртуальный экран HeadSwap" }
    }
}

/** Shared by the shell and the activated input gateway. Only LOCAL is writable, and only on
 * named, trusted virtual displays owned by the expected UID. Never accepts display 0. */
@SuppressLint("PrivateApi", "BlockedPrivateApi")
internal object ShellDisplayIme {
    const val PERMISSION = "android.permission.INTERNAL_SYSTEM_WINDOW"
    private const val LOCAL = 0 // WindowManager.DISPLAY_IME_POLICY_LOCAL
    private val displayManager by lazy { systemDisplayManager() }
    private val displayInfo by lazy {
        Class.forName("android.hardware.display.IDisplayManager").getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
    }
    private val wmType by lazy { Class.forName("android.view.IWindowManager") }
    private val windowManager by lazy {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window")
        checkNotNull(Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder))
    }
    private val getPolicy by lazy { wmType.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType) }
    private val setPolicy by lazy { wmType.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }

    // Resolve the private APIs inside the activated process without reading or touching any display.
    private val activationApi by lazy {
        try {
            displayManager; displayInfo; windowManager; getPolicy; setPolicy
            true
        } catch (_: Exception) { false }
        catch (_: LinkageError) { false }
    }

    fun availableInActivation(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED && activationApi

    fun requireOwned(id: Int, ownerUid: Int) {
        require(id > 0)
        val info = checkNotNull(displayInfo.invoke(displayManager, id)) { "Виртуальный дисплей закрыт" }
        val cls = info.javaClass
        DesktopImePolicy.requireOwned(id, cls.getField("type").getInt(info), cls.getField("flags").getInt(info),
            cls.getField("ownerUid").getInt(info), ownerUid, cls.getField("name").get(info) as? String)
    }

    fun enable(id: Int, ownerUid: Int) {
        requireOwned(id, ownerUid)
        if (getPolicy.invoke(windowManager, id) != LOCAL) {
            setPolicy.invoke(windowManager, id, LOCAL)
            check(getPolicy.invoke(windowManager, id) == LOCAL) { "Android не применил клавиатуру к дисплею $id" }
        }
    }

    private val insetsType by lazy { Class.forName("android.view.InsetsState") }
    private val queryInsets by lazy {
        if (Build.VERSION.SDK_INT == 33) wmType.getMethod("getWindowInsets",
            WindowManager.LayoutParams::class.java, Int::class.javaPrimitiveType, insetsType)
        else wmType.getMethod("getWindowInsets", Int::class.javaPrimitiveType, IBinder::class.java, insetsType)
    }

    /** The same per-display state as WindowMetrics, without constructing a UI Context in app_process.
     * getImeDisplayId() only predicts where an IME could appear, even if there is no keyboard. */
    fun isVisible(id: Int, ownerUid: Int): Boolean {
        requireOwned(id, ownerUid)
        val state = insetsType.getConstructor().newInstance()
        if (Build.VERSION.SDK_INT == 33) {
            queryInsets.invoke(windowManager, WindowManager.LayoutParams(), id, state)
            return insetsType.getMethod("getSourceOrDefaultVisibility", Int::class.javaPrimitiveType)
                .invoke(state, 19 /* InsetsState.ITYPE_IME on Android 13 */) == true
        }
        queryInsets.invoke(windowManager, id, null, state)
        val size = insetsType.getMethod("sourceSize").invoke(state) as Int
        val sourceAt = insetsType.getMethod("sourceAt", Int::class.javaPrimitiveType)
        return (0 until size).any {
            val source = sourceAt.invoke(state, it)
            source.javaClass.getMethod("getType").invoke(source) == WindowInsets.Type.ime() &&
                source.javaClass.getMethod("isVisible").invoke(source) == true
        }
    }
}
