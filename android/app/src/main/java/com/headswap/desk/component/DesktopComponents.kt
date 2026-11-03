package com.headswap.desk.component

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import java.security.MessageDigest
import java.text.Collator
import java.util.concurrent.atomic.AtomicBoolean

internal data class DesktopComponentEntry(
    val componentName: ComponentName,
    val label: String,
    val appLabel: String,
    val versionName: String,
    val versionCode: Long,
    val signerSha256: Set<String>,
    val modeId: String? = null,
) {
    val packageName: String get() = componentName.packageName
}

/** Installed providers only. Approval binds the visible entry and the current APK signers to this Android user. */
internal object DesktopComponents {
    const val ACTION = DesktopComponentPolicy.ACTION
    private const val STORE = "desktop_component_trust_v1"

    fun list(context: Context): List<DesktopComponentEntry> {
        val collator = Collator.getInstance()
        return query(context, null).sortedWith { a, b ->
            collator.compare(a.label, b.label).takeIf { it != 0 }
                ?: a.componentName.flattenToString().compareTo(b.componentName.flattenToString())
        }
    }

    /** The confirmed row might have been replaced while the user was reading the confirmation. */
    fun select(context: Context, entry: DesktopComponentEntry): Boolean {
        val current = query(context, entry.componentName).singleOrNull() ?: return false
        val user = userSerial(context)
        val pin = entry.pin(user)
        if (!pin.accepts(current.pin(user))) return false
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString("component", pin.component).putLong("user", user).putString("mode", pin.modeId)
            .putStringSet("signers", pin.signers.toSet()).apply()
        return true
    }

    /** Recheck manifest, membership of the current user and the live signing certificate on every use. */
    fun selected(context: Context): DesktopComponentEntry? {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val name = prefs.getString("component", null)?.let(ComponentName::unflattenFromString) ?: return null
        val pin = DesktopComponentPolicy.Pin(name.flattenToString(), prefs.getLong("user", -1),
            prefs.getStringSet("signers", null).orEmpty().toSet(), prefs.getString("mode", null))
        val user = userSerial(context)
        if (pin.userSerial != user || user < 0) return null
        return query(context, name).singleOrNull()?.takeIf { pin.accepts(it.pin(user)) }
    }

    fun clear(context: Context) { context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().clear().apply() }

    /** One UI notification per edit, even though a selection comprises several preference keys. */
    fun observeSelection(context: Context, listener: () -> Unit): () -> Unit {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val main = Handler(Looper.getMainLooper())
        val active = AtomicBoolean(true)
        val notify = Runnable { if (active.get()) listener() }
        val observer = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (active.get()) { main.removeCallbacks(notify); main.post(notify) }
        }
        prefs.registerOnSharedPreferenceChangeListener(observer)
        return {
            active.set(false)
            prefs.unregisterOnSharedPreferenceChangeListener(observer)
            main.removeCallbacks(notify)
        }
    }

    private fun DesktopComponentEntry.pin(user: Long) = DesktopComponentPolicy.Pin(componentName.flattenToString(), user, signerSha256, modeId)

    private fun userSerial(context: Context): Long = try {
        context.getSystemService(UserManager::class.java)?.getSerialNumberForUser(Process.myUserHandle()) ?: -1
    } catch (_: SecurityException) { -1 }

    private fun query(context: Context, only: ComponentName?): List<DesktopComponentEntry> {
        val pm = context.packageManager
        val intent = Intent(ACTION).addCategory(Intent.CATEGORY_DEFAULT).setPackage(only?.packageName)
        val flags = PackageManager.ResolveInfoFlags.of((PackageManager.GET_META_DATA or PackageManager.MATCH_DEFAULT_ONLY).toLong())
        return try {
            pm.queryIntentActivities(intent, flags).mapNotNull { result ->
                val activity = result.activityInfo ?: return@mapNotNull null
                val name = ComponentName(activity.packageName, activity.name)
                if (only != null && name != only) return@mapNotNull null
                try { read(context, activity, name) }
                catch (_: SecurityException) { null } // One inaccessible package must not hide other choices.
                catch (_: android.content.res.Resources.NotFoundException) { null } // The APK may be changing.
            }.distinctBy { it.componentName }
        } catch (_: SecurityException) { emptyList() }
    }

    @Suppress("DEPRECATION") // Inspect the actual metadata type and reject declared legacy shared UIDs, including hidden peers.
    private fun read(context: Context, activity: ActivityInfo, name: ComponentName): DesktopComponentEntry? {
        val pm = context.packageManager
        val permission = activity.permission
        val accessible = permission == null || pm.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED
        val modeId = activity.metaData?.get(DesktopComponentPolicy.MODE_ID)
        if (!DesktopComponentPolicy.acceptsDeclaration(activity.metaData?.get(DesktopComponentPolicy.API),
                activity.metaData?.get(DesktopComponentPolicy.PROFILE), activity.exported,
                activity.enabled && activity.applicationInfo.enabled, accessible, modeId)) return null
        if (!DesktopComponentPolicy.supportsIsolatedTask(activity.launchMode, activity.documentLaunchMode,
                (activity.flags and ActivityInfo.FLAG_ALLOW_TASK_REPARENTING) != 0)) return null
        val pkg = try {
            pm.getPackageInfo(name.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } catch (_: PackageManager.NameNotFoundException) { return null }
        val uid = pkg.applicationInfo?.uid ?: return null
        if (uid != activity.applicationInfo.uid || !DesktopComponentPolicy.acceptsOwner(name.packageName,
                context.packageName, Process.myUid(), uid, UserHandle.getUserHandleForUid(uid) == Process.myUserHandle(),
                pkg.sharedUserId, pm.getPackagesForUid(uid)?.toSet().orEmpty())) return null
        val sha = MessageDigest.getInstance("SHA-256")
        val signers = pkg.signingInfo?.apkContentsSigners?.map { cert ->
            sha.digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
        }?.toSet().orEmpty()
        if (signers.isEmpty()) return null
        fun label(value: CharSequence?) = value?.toString().orEmpty().take(128).filterNot {
            it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()
        }.trim().ifEmpty { name.packageName }
        return DesktopComponentEntry(name, label(activity.loadLabel(pm)), label(activity.applicationInfo.loadLabel(pm)),
            pkg.versionName.orEmpty(), pkg.longVersionCode, signers, modeId as? String)
    }
}
