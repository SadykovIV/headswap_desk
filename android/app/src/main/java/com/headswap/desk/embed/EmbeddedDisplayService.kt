package com.headswap.desk.embed

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.view.Surface
import com.headswap.desk.dex.DesktopModePolicy

/** Narrow, signature-protected bridge to the existing session, never a privilege bootstrap. */
class EmbeddedDisplayService : Service() {
    private inline fun <T> authorized(block: (Int) -> T): T {
        val uid = Binder.getCallingUid()
        val expected = packageManager.getApplicationInfo(DesktopModePolicy.DRIVEDECK_PACKAGE, 0).uid
        if (uid != expected || packageManager.checkSignatures(uid, Process.myUid()) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("Только адаптированный DriveDeck может использовать встроенные окна")
        }
        val identity = Binder.clearCallingIdentity()
        try { return block(uid) } finally { Binder.restoreCallingIdentity(identity) }
    }

    private val gateway = object : IEmbeddedDisplayGateway.Stub() {
        override fun version(): Int = authorized { 1 }
        override fun capabilities(): Int = authorized { 31 } // Child 2x, multitouch, producer lifetime, FullHD, parent render config.

        override fun desktopRenderConfig(parentDisplayId: Int): IntArray = authorized {
            EmbeddedSessionRegistry.renderConfig(parentDisplayId, ownDesktop = false)
        }

        override fun create(surface: Surface, ownerToken: IBinder, parentDisplayId: Int,
            width: Int, height: Int, density: Int): Int {
            try { return authorized { uid ->
                EmbeddedSessionRegistry.create(uid, surface, ownerToken, parentDisplayId, width, height, density)
            } } finally { surface.release() } // The shell received its own parcelled Surface reference.
        }

        override fun resize(handle: Int, width: Int, height: Int, density: Int) = authorized { uid ->
            EmbeddedSessionRegistry.resize(uid, handle, width, height, density)
        }

        override fun launch(handle: Int, packageName: String): Boolean = authorized { uid ->
            require(packageName !in setOf(this@EmbeddedDisplayService.packageName,
                DesktopModePolicy.DRIVEDECK_PACKAGE, "com.android.settings")) { "Приложение нельзя встроить" }
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
            val candidates = packageManager.queryIntentActivities(launcher, 0)
                .map { it.activityInfo }.filter { it.packageName == packageName && it.exported && it.enabled && it.applicationInfo.enabled }
            val preferred = packageManager.getLaunchIntentForPackage(packageName)?.component
            val selected = candidates.firstOrNull { it.name == preferred?.className }
                ?: candidates.firstOrNull() ?: throw IllegalArgumentException("У приложения нет доступного главного экрана")
            val component = android.content.ComponentName(selected.packageName, selected.name)
            EmbeddedGatewayPolicy.requireComponent(component.packageName, component.className)
            val activity = packageManager.getActivityInfo(component, 0)
            require(activity.exported && activity.enabled && activity.applicationInfo.enabled) { "Приложение недоступно" }
            require(activity.permission == null || packageManager.checkPermission(activity.permission,
                this@EmbeddedDisplayService.packageName) == PackageManager.PERMISSION_GRANTED) { "Нет разрешения на запуск приложения" }
            EmbeddedSessionRegistry.launch(uid, handle, component.packageName, component.className)
            true
        }

        override fun touch(handle: Int, action: Int, x: Float, y: Float) = authorized { uid ->
            EmbeddedSessionRegistry.touch(uid, handle, action, x, y)
        }
        override fun touchPoints(handle: Int, action: Int, actionIndex: Int, pointerIds: IntArray,
            xs: FloatArray, ys: FloatArray) = authorized { uid ->
            EmbeddedSessionRegistry.touchPoints(uid, handle, action, actionIndex, pointerIds, xs, ys)
        }
        override fun back(handle: Int) = authorized { uid -> EmbeddedSessionRegistry.back(uid, handle) }
        override fun producerLifetime(handle: Int): IBinder = authorized { uid -> EmbeddedSessionRegistry.producerLifetime(uid, handle) }
        override fun release(handle: Int) = authorized { uid -> EmbeddedSessionRegistry.release(uid, handle) }
        override fun taskState(handle: Int, packageName: String): Int = authorized { uid ->
            EmbeddedSessionRegistry.taskState(uid, handle, packageName)
        }
    }

    override fun onBind(intent: Intent?): IBinder = gateway
}
