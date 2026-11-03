package com.headswap.desk.cluster

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.assist.AssistStructure
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.View
import com.headswap.desk.dex.ShellDisplayIme
import java.lang.reflect.Proxy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

internal sealed interface ShellManeuverSample {
    data object Unavailable : ShellManeuverSample
    data object VisibleWithoutHint : ShellManeuverSample
    data class Card(val packageName: String, val arrowLabel: String, val distance: String,
        val unit: String, val languageTags: String) : ShellManeuverSample

    fun encode(): Array<String>? = when (this) {
        Unavailable -> null
        VisibleWithoutHint -> emptyArray()
        is Card -> arrayOf(packageName, arrowLabel, distance, unit, languageTags)
    }

    companion object {
        fun decode(fields: Array<out String?>?): ShellManeuverSample {
            if (fields == null) return Unavailable
            if (fields.isEmpty()) return VisibleWithoutHint
            if (fields.size != 5) return Unavailable
            val pkg = fields[0]?.takeIf { it in YandexGuidanceCard.PACKAGES } ?: return Unavailable
            val arrow = fields[1]?.takeIf { it.isNotBlank() && it.length <= 120 } ?: return Unavailable
            val number = fields[2]?.takeIf { it.isNotBlank() && it.length <= 24 } ?: return Unavailable
            val unit = fields[3]?.takeIf { it.isNotBlank() && it.length <= 16 } ?: return Unavailable
            val tags = fields[4]?.takeIf { it.length <= 80 } ?: return Unavailable
            if (listOf(arrow, number, unit, tags).any { value -> value.any(Char::isISOControl) }) return Unavailable
            return Card(pkg, arrow, number, unit, tags)
        }
    }
}

/** Task-specific Assist in the existing shell helper. No global assistant, accessibility or screenshot. */
@SuppressLint("PrivateApi", "BlockedPrivateApi")
internal object ShellYandexGuidance {
    private const val RECEIVER = "android.app.IAssistDataReceiver"
    private const val TIMEOUT_MS = 650L
    private const val MAX_NODES = 2500
    private val intType = Integer.TYPE
    private val boolType = java.lang.Boolean.TYPE
    private val taskManager by lazy { Class.forName("android.app.IActivityTaskManager") }
    private val manager by lazy { Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null) }
    private val receiverType by lazy { Class.forName(RECEIVER) }
    // Older Android implementations hard-code fetchStructure=false in the three/four-argument overload.
    private val request by lazy {
        taskManager.getMethod("requestAssistDataForTask", receiverType, intType, String::class.java, String::class.java, boolType)
    }
    private val tasks by lazy { taskManager.getMethod("getTasks", intType, boolType, boolType, intType) }
    private val structureTask by lazy { AssistStructure::class.java.getMethod("getTaskId") }

    private data class Target(val task: Int, val pkg: String)

    private fun top(display: Int, owner: Int, user: Int): Target? {
        ShellDisplayIme.requireOwned(display, owner)
        // The service filters by display; it returns just the top task, without intent extras.
        val info = (tasks.invoke(manager, 1, false, false, display) as List<*>).firstOrNull()
            as? ActivityManager.RunningTaskInfo ?: return null
        val pkg = info.topActivity?.packageName ?: return null
        if (pkg !in YandexGuidanceCard.PACKAGES || info.taskId < 0 ||
            info.javaClass.getField("displayId").getInt(info) != display ||
            info.javaClass.getField("userId").getInt(info) != user ||
            !info.javaClass.getField("isVisible").getBoolean(info)) return null
        return Target(info.taskId, pkg)
    }

    /** Blocking, off the UI/IME/panel thread. Discard late responses and switched/removed tasks. */
    fun read(displayId: Int, displayOwnerUid: Int, userId: Int): ShellManeuverSample {
        return try {
            val method = request
            val target = top(displayId, displayOwnerUid, userId) ?: return ShellManeuverSample.Unavailable
            val until = SystemClock.elapsedRealtime() + TIMEOUT_MS
            val answers = ArrayBlockingQueue<ShellManeuverSample>(1)
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(RECEIVER); return true }
                    if (code != IBinder.FIRST_CALL_TRANSACTION) return false // onHandleAssistData only.
                    data.enforceInterface(RECEIVER)
                    if (SystemClock.elapsedRealtime() >= until) return true
                    val sample = try {
                        val b = data.readTypedObject(Bundle.CREATOR)
                        val identity = Binder.clearCallingIdentity()
                        try {
                            val structure = b?.getParcelable("structure", AssistStructure::class.java)
                            if (structure == null || top(displayId, displayOwnerUid, userId) != target ||
                                structure.activityComponent?.packageName != target.pkg ||
                                structureTask.invoke(structure) != target.task) ShellManeuverSample.Unavailable
                            else readStructure(structure, displayId, target.pkg)
                        } finally { Binder.restoreCallingIdentity(identity) }
                    } catch (_: Exception) { ShellManeuverSample.Unavailable }
                    catch (_: LinkageError) { ShellManeuverSample.Unavailable }
                    answers.offer(sample)
                    return true
                }
            }
            val receiver = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(receiverType)) { _, m, _ ->
                if (m.name == "asBinder") callback else null
            }
            if (method.invoke(manager, receiver, target.task, "com.android.shell", null, true) != true)
                return ShellManeuverSample.Unavailable
            val wait = (until - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            val result = answers.poll(wait, TimeUnit.MILLISECONDS) ?: return ShellManeuverSample.Unavailable
            if (result != ShellManeuverSample.Unavailable && top(displayId, displayOwnerUid, userId) != target)
                ShellManeuverSample.Unavailable else result
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ShellManeuverSample.Unavailable
        } catch (_: ReflectiveOperationException) { ShellManeuverSample.Unavailable }
        catch (_: RuntimeException) { ShellManeuverSample.Unavailable }
        catch (_: LinkageError) { ShellManeuverSample.Unavailable }
    }

    private fun readStructure(structure: AssistStructure, displayId: Int, pkg: String): ShellManeuverSample {
        val roots = (0 until structure.windowNodeCount).map(structure::getWindowNodeAt)
            .filter { it.displayId == displayId }
        if (roots.size != 1) return ShellManeuverSample.Unavailable
        val stack = ArrayDeque<AssistStructure.ViewNode>()
        stack.addLast(roots.single().rootViewNode)
        var visited = 0
        var balloon: AssistStructure.ViewNode? = null
        var hiddenPrimary = false
        while (stack.isNotEmpty()) {
            if (++visited > MAX_NODES) return ShellManeuverSample.Unavailable
            val node = stack.removeLast()
            if (node.idEntry == "next_upcoming_maneuver") continue
            if (node.idEntry == ForegroundYandexManeuver.PRIMARY && node.idPackage == pkg) {
                if (node.visibility != View.VISIBLE) { hiddenPrimary = true; continue }
                if (balloon != null) return ShellManeuverSample.Unavailable
                balloon = node
                continue
            }
            if (node.visibility != View.VISIBLE) continue
            for (i in 0 until node.childCount) stack.addLast(node.getChildAt(i))
        }
        val primary = balloon ?: return if (hiddenPrimary) ShellManeuverSample.VisibleWithoutHint else ShellManeuverSample.Unavailable
        if (primary.childCount > 100) return ShellManeuverSample.Unavailable
        val values = HashMap<String, AssistStructure.ViewNode>()
        val ids = setOf(ForegroundYandexManeuver.ARROW, ForegroundYandexManeuver.DISTANCE, ForegroundYandexManeuver.UNIT)
        for (i in 0 until primary.childCount) {
            val child = primary.getChildAt(i)
            if (child.visibility != View.VISIBLE || child.idPackage != pkg) continue
            val id = child.idEntry ?: continue
            if (id == ForegroundYandexManeuver.VIA) return ShellManeuverSample.VisibleWithoutHint
            if (id in ids && values.put(id, child) != null) return ShellManeuverSample.Unavailable
        }
        val arrow = values[ForegroundYandexManeuver.ARROW] ?: return ShellManeuverSample.Unavailable
        val label = arrow.contentDescription ?: return ShellManeuverSample.Unavailable
        val distance = values[ForegroundYandexManeuver.DISTANCE]?.text ?: return ShellManeuverSample.Unavailable
        val unit = values[ForegroundYandexManeuver.UNIT]?.text ?: return ShellManeuverSample.Unavailable
        if (label.length > 120 || distance.length > 24 || unit.length > 16) return ShellManeuverSample.Unavailable
        return ShellManeuverSample.decode(arrayOf(pkg, label.toString(), distance.toString(),
            unit.toString(), arrow.localeList?.toLanguageTags().orEmpty()))
    }
}
