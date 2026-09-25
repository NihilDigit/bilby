package dev.bilby.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import dev.bilby.AppBuild
import dev.bilby.BiliLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** [apk] 已按本机 ABI 选好。 */
data class AndroidUpdate(
    override val version: String,
    override val notes: String,
    override val pageUrl: String,
    override val canInstallInApp: Boolean,
    val apk: ReleaseAsset,
) : AvailableUpdate {
    override val downloadSize: Long get() = apk.size
}

/**
 * Android 上的应用内更新。
 *
 * release 按 ABI 分包(bilby-<版本>-<abi>.apk)外加一个通用包。这里按 SUPPORTED_ABIS 的优先级
 * 挑本机的包,没有就退回通用包:装错架构的包会直接安装失败,而失败信息只说「解析包出现问题」,
 * 无从归因。下载时边写边算 SHA-256,与 GitHub 为附件公布的 digest 对不上就不安装。
 *
 * 安装走 PackageInstaller 会话,不走 `ACTION_VIEW` 加 FileProvider:后者靠的
 * `ACTION_INSTALL_PACKAGE` 一路自 API 29 弃用,而且装完装不完应用都收不到回音。
 *
 * debug 包的包名是 dev.bilby.debug,装 release 包上去是另一个应用而不是升级,所以 debug 包
 * 只检查、不安装,界面改为打开 Release 页面;开屏也不查。
 */
class AndroidAppUpdater(private val context: Context) : GithubUpdateService<AndroidUpdate>(
    releases = GithubReleaseClient(HttpClient(OkHttp), userAgent = "Bilby/${AppBuild.versionName}"),
    currentVersion = AppBuild.versionName,
    checksOnStartup = !AppBuild.debug,
) {
    override suspend fun resolve(release: LatestRelease): AndroidUpdate? {
        val apk = pickApk(release) ?: return null
        return AndroidUpdate(
            version = release.version,
            notes = release.notes,
            pageUrl = release.pageUrl,
            canInstallInApp = !AppBuild.debug,
            apk = apk,
        )
    }

    /** 下载并校验,成功后提交安装。未授予「安装未知应用」时先带用户去授权,授权后要再点一次。 */
    override suspend fun downloadAndInstall(update: AvailableUpdate) {
        val own = update.own()
        if (!own.canInstallInApp) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { BiliLog.w("打开安装未知应用授权页失败", it) }
            return
        }
        status = UpdateStatus.Downloading(update, 0f)
        val file = try {
            download(own)
        } catch (e: CancellationException) {
            status = UpdateStatus.Available(update)
            throw e
        } catch (e: Exception) {
            status = downloadFailed(e, update)
            return
        }
        runCatching { install(file) }
            .onSuccess { status = UpdateStatus.Installing(update) }
            .onFailure {
                BiliLog.w("提交安装会话失败", it)
                status = UpdateStatus.Failed(UpdateFailure.Launch, update)
            }
    }

    /**
     * 目录先整个清掉:上一次下到一半的残包会让安装器报「解析包出现问题」,那句提示指不向
     * 真正的原因。
     */
    private suspend fun download(update: AndroidUpdate): File = withContext(Dispatchers.IO) {
        val expected = update.apk.sha256 ?: throw ChecksumMismatchException("Release 没有公布摘要")
        val dir = File(context.cacheDir, UPDATE_DIR).apply { deleteRecursively(); mkdirs() }
        val target = File(dir, update.apk.name)
        val digest = MessageDigest.getInstance("SHA-256")
        target.outputStream().use { out ->
            releases.download(
                update.apk,
                onChunk = { buffer, length ->
                    out.write(buffer, 0, length)
                    digest.update(buffer, 0, length)
                },
                onProgress = { status = UpdateStatus.Downloading(update, it) },
            )
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            target.delete()
            throw ChecksumMismatchException("${update.apk.name}: $actual != $expected")
        }
        target
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("bilby.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
            // 系统要往回调 Intent 里填状态与确认页,必须可变。
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pending.intentSender)
        }
    }

    internal fun onInstallFailed(reason: UpdateFailure) {
        val update = (status as? UpdateStatus.Installing)?.update
        status = UpdateStatus.Failed(reason, update)
    }

    private fun pickApk(release: LatestRelease): ReleaseAsset? =
        Build.SUPPORTED_ABIS.firstNotNullOfOrNull { release.asset("bilby-${release.version}-$it.apk") }
            ?: release.asset("bilby-${release.version}-universal.apk")

    private companion object {
        const val UPDATE_DIR = "updates"
    }
}
