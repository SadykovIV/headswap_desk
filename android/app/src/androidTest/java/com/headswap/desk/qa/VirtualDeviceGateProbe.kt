package com.headswap.desk.qa

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import java.lang.reflect.InvocationTargetException

/** Read-only capability diagnosis on disposable emulators; no association, role or PIN changes. */
internal object VirtualDeviceGateProbe {
    @JvmStatic fun run(test: Instrumentation) {
        val result = Bundle()
        var ui: UiAutomation? = null
        var created: Any? = null
        try {
            check(Build.HARDWARE in listOf("ranchu", "goldfish")) { "Disposable emulator required" }
            val context = test.targetContext
            val permissions = arrayOf("android.permission.CREATE_VIRTUAL_DEVICE",
                "android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY")
            result.putInt("sdk", Build.VERSION.SDK_INT)
            result.putInt("appUid", Process.myUid())
            permissions.forEach { permission ->
                val key = permission.substringAfterLast('.')
                result.putBoolean("before_$key", context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
                result.putBoolean("shell_$key", context.packageManager.checkPermission(permission, "com.android.shell") == PackageManager.PERMISSION_GRANTED)
            }
            val managerClass = Class.forName("android.companion.virtual.VirtualDeviceManager")
            val paramsClass = Class.forName("android.companion.virtual.VirtualDeviceParams")
            val methods = managerClass.methods.filter { it.name == "createVirtualDevice" }
            result.putString("createMethods", methods.joinToString("; ") { it.toGenericString() })
            result.putBoolean("hasNoAssociationOverload", methods.any { it.parameterTypes.contentEquals(arrayOf(paramsClass)) })
            val automation = test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ui = automation
            automation.adoptShellPermissionIdentity(*permissions)
            permissions.forEach { permission ->
                result.putBoolean("adopted_${permission.substringAfterLast('.')}",
                    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
            }
            val builderClass = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
            val builder = builderClass.getConstructor().newInstance()
            val params = builderClass.getMethod("build").invoke(builder)
            val manager = checkNotNull(context.getSystemService("virtualdevice"))
            try {
                // -1 deliberately denotes no existing CDM record. This never creates a record.
                created = managerClass.getMethod("createVirtualDevice", Int::class.javaPrimitiveType, paramsClass)
                    .invoke(manager, -1, params)
                result.putString("noAssociationResult", "Unexpected creation; closed in finally")
            } catch (error: InvocationTargetException) {
                val cause = error.targetException
                result.putString("noAssociationResult", "${cause.javaClass.name}: ${cause.message}")
            }
            result.putString("scope", "Capability gate only; no association, role or keyguard mutation; permission adoption dropped")
        } catch (error: Throwable) {
            result.putString("error", android.util.Log.getStackTraceString(error))
        } finally {
            created?.let { runCatching { it.javaClass.getMethod("close").invoke(it) } }
            ui?.dropShellPermissionIdentity()
        }
        test.finish(if (result.containsKey("error")) Activity.RESULT_CANCELED else Activity.RESULT_OK, result)
    }
}
