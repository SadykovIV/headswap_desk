package com.headswap.desk.dex

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd

/** Reads only power group zero's two uptime clocks. No raw dump is logged, saved or returned. */
@SuppressLint("PrivateApi")
internal object PhonePowerActivity {
    const val PERMISSION = android.Manifest.permission.DUMP
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_MS = 1_100L

    // --screen already opts into process-scoped hidden APIs. This binder is the system power
    // service, and the call has no user-supplied service name or dump arguments.
    private val service: IBinder? by lazy {
        runCatching {
            Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, Context.POWER_SERVICE) as? IBinder
        }.getOrNull()
    }

    fun read(context: Context): Long? {
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return null
        val power = service ?: return null
        val deadline = SystemClock.uptimeMillis() + MAX_MS
        val pipe = ParcelFileDescriptor.createPipe()
        pipe[0].use { source ->
            pipe[1].use { power.dumpAsync(it.fileDescriptor, emptyArray()) }
            val poll = StructPollfd().apply { fd = source.fileDescriptor; events = OsConstants.POLLIN.toShort() }
            val buffer = ByteArray(4096)
            val line = StringBuilder()
            var truncated = false
            var bytes = 0
            val parser = PhoneIdleDeadline.GroupParser()
            while (bytes < MAX_BYTES) {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0 || Os.poll(arrayOf(poll), left.toInt()) <= 0) return null
                val count = Os.read(source.fileDescriptor, buffer, 0, buffer.size)
                if (count <= 0) return null
                bytes += count
                for (i in 0 until count) {
                    val c = buffer[i].toInt().toChar()
                    if (c == '\n') {
                        if (!truncated) {
                            parser.line(line.toString(), SystemClock.uptimeMillis())?.let { return it }
                        }
                        truncated = false
                        line.setLength(0)
                    } else if (line.length < 160) line.append(c) else truncated = true
                }
            }
            return null
        }
    }
}
