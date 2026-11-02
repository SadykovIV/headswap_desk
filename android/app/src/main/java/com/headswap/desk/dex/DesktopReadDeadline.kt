package com.headswap.desk.dex

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Placement is advisory. A missed read must never cancel an input, task launch or display lease. */
internal object DesktopReadDeadline {
    fun <T> call(post: (Runnable) -> Boolean, waitMs: Long, unavailable: () -> T, read: () -> T): T {
        val task = FutureTask(read)
        try {
            if (!post(task)) return unavailable()
            return task.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (_: RejectedExecutionException) {
            return unavailable()
        } catch (_: TimeoutException) {
            task.cancel(false)
            return unavailable()
        } catch (_: InterruptedException) {
            task.cancel(false)
            Thread.currentThread().interrupt()
            return unavailable()
        }
    }
}
