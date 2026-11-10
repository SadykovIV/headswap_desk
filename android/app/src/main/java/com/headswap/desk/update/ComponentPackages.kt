package com.headswap.desk.update

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import androidx.core.content.FileProvider
import com.headswap.desk.component.DesktopComponentEntry
import com.headswap.desk.component.DesktopComponentPolicy
import com.headswap.desk.component.DesktopComponents
import java.io.File
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest

class ComponentApkProvider : FileProvider()

data class PreparedComponent(val version: String, val code: Long, val file: File)

internal object ComponentPackages {
    private val sigFlags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
    private fun PackageInfo.signers() = signingInfo?.apkContentsSigners?.toSet().orEmpty()

    fun installed(ctx: Context, spec: DesktopComponent): PackageInfo? = try {
        ctx.packageManager.getPackageInfo(spec.pkg, sigFlags)
    } catch (_: PackageManager.NameNotFoundException) { null }

    /** A setup screen must not recommend sensitive access to an APK with an untrusted publisher. */
    fun trustedInstalled(ctx: Context, spec: DesktopComponent): PackageInfo? = installed(ctx, spec)?.also {
        checkPublisher(ctx, spec, it)
        check(it.longVersionCode >= spec.minCode) { "Установлена несовместимая версия компонента." }
    }

    private fun dir(ctx: Context) = File(ctx.cacheDir, "verified-components").apply { mkdirs() }

    fun download(ctx: Context, spec: DesktopComponent, asset: ComponentAsset): PreparedComponent {
        require(spec == asset.component)
        var url = URL(asset.url)
        for (hop in 0..4) {
            require(url.protocol == "https" && url.userInfo == null && url.port in listOf(-1, 443) &&
                url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                "Неизвестный адрес файла компонента."
            }
            val conn = ReleaseFeed.connection(url)
            try {
                when (val status = conn.responseCode) {
                    200 -> return conn.inputStream.use { stage(ctx, spec, it, asset) }
                    301, 302, 303, 307, 308 -> url = URL(url, checkNotNull(conn.getHeaderField("Location")))
                    else -> error("GitHub HTTP $status")
                }
            } finally { conn.disconnect() }
        }
        error("Слишком много перенаправлений GitHub.")
    }

    fun stage(ctx: Context, spec: DesktopComponent, input: InputStream, expected: ComponentAsset? = null): PreparedComponent {
        return stageChecked(ctx, input, expected) { file -> validate(ctx, spec, file) }
    }

    fun stageProfile(ctx: Context, entry: DesktopComponentEntry, input: InputStream): PreparedComponent =
        stageChecked(ctx, input, null) { file -> validateProfile(ctx, entry, file) }

    private fun stageChecked(ctx: Context, input: InputStream, expected: ComponentAsset?,
                             validateFile: (File) -> PreparedComponent): PreparedComponent {
        val temp = File.createTempFile("component-", ".apk", ctx.cacheDir)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            temp.outputStream().use { out ->
                val buffer = ByteArray(32768)
                while (true) {
                    check(!Thread.currentThread().isInterrupted) { "Проверка прервана." }
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    check(count <= (expected?.bytes ?: ComponentCatalog.MAX_BYTES)) { "APK превышает ожидаемый размер." }
                    out.write(buffer, 0, size)
                    hash.update(buffer, 0, size)
                }
            }
            val sha = hash.digest().joinToString("") { "%02x".format(it) }
            if (expected != null) check(expected.sha256 == sha && expected.bytes == count) { "Контрольная сумма APK не совпадает." }
            val apk = validateFile(temp)
            if (expected != null) check(apk.code == expected.code && apk.version == expected.version) { "Версия APK не совпадает с выпуском." }
            val final = File(dir(ctx), "$sha.apk")
            check(temp.renameTo(final)) { "Не удалось сохранить проверенный APK." }
            // The installer can still hold a URI from another Activity instance.
            dir(ctx).listFiles()?.filter { it != final && it.lastModified() < System.currentTimeMillis() - 86_400_000 }
                ?.forEach { it.delete() }
            return apk.copy(file = final)
        } finally { temp.delete() }
    }

    fun restore(ctx: Context, name: String?): File? = name?.takeIf { it.matches(Regex("[0-9a-f]{64}\\.apk")) }
        ?.let { File(dir(ctx), it) }?.takeIf { it.isFile }

    @Suppress("DEPRECATION")
    fun validateProfile(ctx: Context, entry: DesktopComponentEntry, file: File): PreparedComponent {
        val installed = DesktopComponents.installed(ctx, entry.componentName)
            ?: error("Установленный компонент изменился или недоступен.")
        check(installed.signerSha256 == entry.signerSha256 && installed.modeId == entry.modeId) {
            "Подпись или профиль установленного компонента изменились."
        }
        val flags = PackageManager.PackageInfoFlags.of((PackageManager.GET_SIGNING_CERTIFICATES or
            PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA).toLong())
        val apk = ctx.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Не удалось прочитать APK.")
        val sha = MessageDigest.getInstance("SHA-256")
        val pins = apk.signers().map { sha.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
        val activity = apk.activities?.firstOrNull { it.name == entry.componentName.className }
        val mode = activity?.metaData?.get(DesktopComponentPolicy.MODE_ID)
        val compatible = activity != null && mode == entry.modeId && DesktopComponentPolicy.acceptsDeclaration(
            activity.metaData?.get(DesktopComponentPolicy.API), activity.metaData?.get(DesktopComponentPolicy.PROFILE),
            activity.exported, activity.enabled && activity.applicationInfo.enabled,
            activity.permission.isNullOrEmpty() || ctx.checkSelfPermission(activity.permission) == PackageManager.PERMISSION_GRANTED, mode)
            && DesktopComponentPolicy.supportsIsolatedTask(activity.launchMode, activity.documentLaunchMode,
                activity.flags and android.content.pm.ActivityInfo.FLAG_ALLOW_TASK_REPARENTING != 0)
        ProfileUpdatePolicy.checkArchive(entry.packageName, entry.signerSha256, installed.versionCode,
            apk.packageName, pins, apk.longVersionCode, compatible)
        check((apk.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "Этой версии нужен более новый Android." }
        return PreparedComponent(apk.versionName.orEmpty(), apk.longVersionCode, file)
    }

    fun installerIntent(ctx: Context, apk: PreparedComponent): Intent {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.components", apk.file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true)
        val handler = ctx.packageManager.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull()?.activityInfo ?: error("Системный установщик APK недоступен.")
        return intent.setComponent(ComponentName(handler.packageName, handler.name))
    }

    @Suppress("DEPRECATION") // The Android package-removal Activity presents the system confirmation.
    fun uninstallIntent(ctx: Context, pkg: String): Intent {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", pkg, null))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        val handler = ctx.packageManager.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull()?.activityInfo ?: error("Системное удаление недоступно.")
        return intent.setComponent(ComponentName(handler.packageName, handler.name))
    }

    fun builtinRemoval(ctx: Context, spec: DesktopComponent): Intent {
        val pkg = checkNotNull(installed(ctx, spec)) { "Компонент не установлен." }
        checkPublisher(ctx, spec, pkg)
        return uninstallIntent(ctx, spec.pkg)
    }

    private fun checkPublisher(ctx: Context, spec: DesktopComponent, apk: PackageInfo) {
        val signatures = apk.signers()
        val expected = spec.publisherSha256
        val trusted = if (expected == null) {
            signatures.isNotEmpty() && signatures == ctx.packageManager.getPackageInfo(ctx.packageName, sigFlags).signers()
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            signatures.map { signature -> digest.digest(signature.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet() == setOf(expected)
        }
        check(trusted) { "Подпись компонента не совпадает с подтверждённым издателем." }
    }

    fun validate(ctx: Context, spec: DesktopComponent, file: File): PreparedComponent {
        val pm = ctx.packageManager
        val flags = PackageManager.PackageInfoFlags.of((PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA).toLong())
        val apk = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Файл не является подписанным APK.")
        check(apk.packageName == spec.pkg) { "Выберите адаптированный APK ${spec.title}, пакет ${spec.pkg}." }
        checkPublisher(ctx, spec, apk)
        val current = installed(ctx, spec)
        if (current != null) checkPublisher(ctx, spec, current)
        if (spec.publisherSha256 != null) {
            check(apk.activities.orEmpty().any { activity ->
                DesktopComponentPolicy.acceptsDeclaration(activity.metaData?.get(DesktopComponentPolicy.API),
                    activity.metaData?.get(DesktopComponentPolicy.PROFILE), activity.exported,
                    activity.enabled && activity.applicationInfo.enabled, activity.permission.isNullOrEmpty(),
                    activity.metaData?.get(DesktopComponentPolicy.MODE_ID)) &&
                    DesktopComponentPolicy.supportsIsolatedTask(activity.launchMode, activity.documentLaunchMode,
                        activity.flags and android.content.pm.ActivityInfo.FLAG_ALLOW_TASK_REPARENTING != 0)
            }) { "В APK нет совместимого профиля HeadSwap Desktop." }
        }
        check((apk.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "Этой версии нужен более новый Android." }
        val code = apk.longVersionCode
        check(code >= spec.minCode) { "Эта версия несовместима с HeadSwap." }
        check(current == null || code > current.longVersionCode) { if (current?.longVersionCode == code) "Эта версия уже установлена." else "Установлена более новая версия." }
        return PreparedComponent(apk.versionName ?: error("В APK нет номера версии."), code, file)
    }
}
