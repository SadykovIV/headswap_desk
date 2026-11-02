package com.headswap.desk.dex

import android.content.Context
import dalvik.system.InMemoryDexClassLoader
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** The same packaged and hash-checked wrappers are used in the delegated and Shizuku processes. */
internal object ScrcpyRuntime {
    private var cached: ClassLoader? = null

    @Synchronized fun loader(context: Context): ClassLoader {
        cached?.let { return it }
        val archive = context.assets.open("dex/scrcpy-server-v4.1").use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            val limit = DexUserServicePolicy.MAX_CODE_BYTES
            while (out.size() <= limit) {
                val n = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                if (n < 0) break
                if (n > 0) out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        val dex = DexUserServicePolicy.verifiedDex(archive)
        return InMemoryDexClassLoader(ByteBuffer.wrap(dex), ScrcpyRuntime::class.java.classLoader)
            .also { cached = it }
    }
}
