package com.headswap.desk.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
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
            val apk = validate(ctx, spec, temp)
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

    fun validate(ctx: Context, spec: DesktopComponent, file: File): PreparedComponent {
        val pm = ctx.packageManager
        val apk = pm.getPackageArchiveInfo(file.absolutePath, sigFlags) ?: error("Файл не является подписанным APK.")
        check(apk.packageName == spec.pkg) { "Выберите адаптированный APK ${spec.title}, пакет ${spec.pkg}." }
        val signer = pm.getPackageInfo(ctx.packageName, sigFlags).signers()
        check(signer.isNotEmpty() && apk.signers() == signer) { "Подпись компонента отличается от подписи HeadSwap." }
        val current = installed(ctx, spec)
        check(current == null || current.signers() == signer) { "Установленный компонент подписан другим ключом." }
        check((apk.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) { "Этой версии нужен более новый Android." }
        val code = apk.longVersionCode
        check(code >= spec.minCode) { "Эта версия несовместима с HeadSwap." }
        check(current == null || code > current.longVersionCode) { if (current?.longVersionCode == code) "Эта версия уже установлена." else "Установлена более новая версия." }
        return PreparedComponent(apk.versionName ?: error("В APK нет номера версии."), code, file)
    }
}
