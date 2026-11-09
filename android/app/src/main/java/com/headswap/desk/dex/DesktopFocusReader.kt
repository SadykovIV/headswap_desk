package com.headswap.desk.dex

import android.app.ActivityManager
import android.os.IBinder

/** Read only. Callers must intersect this observation with their live owned display IDs. */
internal object DesktopFocusReader {
    // IWindowManager has no getTopFocusedDisplayId binder entry on supported Android releases.
    // RunningTasks orders focused tasks first and uses the existing REAL_GET_TASKS grant.
    // Query just the first task; never search a phone app or move a task to make it focused.
    private val query by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "activity_task") as IBinder
            val service = Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val method = Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val callback: () -> Int? = {
                val tasks = method.invoke(service, 1, false, false, -1) as? List<*>
                (tasks?.firstOrNull() as? ActivityManager.RunningTaskInfo)?.let {
                    FrameworkTaskIdentityReader.read(it)?.displayId
                }
            }
            callback
        }.getOrNull()
    }
    fun current(): Int? = runCatching { query?.invoke() }.getOrNull()
}
