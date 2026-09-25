package dev.bilby.update

import dev.bilby.AppBuild
import dev.bilby.BiliLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

data class DesktopUpdate(
    override val version: String,
    override val notes: String,
    override val pageUrl: String,
    val plan: DesktopUpdatePlan,
) : AvailableUpdate {
    override val downloadSize: Long
        get() = when (plan) {
            is DesktopUpdatePlan.Patch -> plan.zip.size
            is DesktopUpdatePlan.Installer -> plan.msi.size
            is DesktopUpdatePlan.Manual -> plan.msi.size
        }
    override val canInstallInApp: Boolean get() = plan !is DesktopUpdatePlan.Manual
}

sealed interface DesktopUpdatePlan {
    /** 只替换每次构建都会变的那几个文件,其余与本机逐字节相同。 */
    data class Patch(val zip: ReleaseAsset, val manifest: UpdateManifest) : DesktopUpdatePlan

    /** 整包重装。MSI 的升级是先卸后装,要等 Bilby 退出后再跑,否则弹文件占用的对话框。 */
    data class Installer(val msi: ReleaseAsset) : DesktopUpdatePlan

    /** 便携版且无法增量,或开发时从 gradle 直接跑:只给下载页。 */
    data class Manual(val msi: ReleaseAsset) : DesktopUpdatePlan
}

/**
 * Windows 端的应用内更新,照 Piko 的做法。
 *
 * 每个版本除了 MSI 与便携 zip,还附一份应用目录清单(files.json)与只含易变文件的 app.zip。
 * 检查时把本机应用目录与新版清单逐个比对:不同之处都在 app.zip 里就增量更新,否则 MSI 装的
 * 走整包重装,便携版只给下载页。
 *
 * 替换文件要先退出自己:下载校验完停在 [UpdateStatus.ReadyToRestart],用户同意后写出一个
 * PowerShell 脚本、脱离本进程启动,由它等本进程退出、替换或跑 msiexec、再启动新版本,见
 * resources/update/apply-update.ps1。本进程经 [exitRequests] 请入口退出。
 */
class DesktopAppUpdater private constructor(
    private val installation: Installation?,
    checksOnStartup: Boolean,
    releases: GithubReleaseClient,
) : GithubUpdateService<DesktopUpdate>(
    releases = releases,
    currentVersion = AppBuild.versionName,
    checksOnStartup = checksOnStartup,
) {
    /** 本机应用目录。[exe] 是启动器,更新后由脚本重新拉起。 */
    private class Installation(val dir: File, val exe: File)

    private val json = Json { ignoreUnknownKeys = true }
    private val stagingRoot = File(System.getProperty("java.io.tmpdir"), "bilby-update")

    private val mutableExitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 更新脚本已启动,入口收到后退出应用。 */
    val exitRequests: SharedFlow<Unit> = mutableExitRequests.asSharedFlow()

    override suspend fun resolve(release: LatestRelease): DesktopUpdate? {
        val prefix = "bilby-windows-$ARCH-${release.version}"
        // 三个附件缺一个都按还没有新版本处理:Release 还是草稿时它们陆续挂上。
        val manifestAsset = release.asset("$prefix-files.json") ?: return null
        val zip = release.asset("$prefix-app.zip") ?: return null
        val msi = release.asset("$prefix.msi") ?: return null
        fun update(plan: DesktopUpdatePlan) = DesktopUpdate(release.version, release.notes, release.pageUrl, plan)

        val installation = installation ?: return update(DesktopUpdatePlan.Manual(msi))
        val manifest = json.decodeFromString(UpdateManifest.serializer(), fetchVerified(manifestAsset).decodeToString())
        return withContext(Dispatchers.IO) {
            when {
                canPatch(installation.dir, manifest) -> update(DesktopUpdatePlan.Patch(zip, manifest))
                isMsiInstall(installation.dir) -> update(DesktopUpdatePlan.Installer(msi))
                else -> update(DesktopUpdatePlan.Manual(msi))
            }
        }
    }

    override suspend fun downloadAndInstall(update: AvailableUpdate) {
        val own = update.own()
        if (!own.canInstallInApp) return
        status = UpdateStatus.Downloading(update, 0f)
        status = try {
            withContext(Dispatchers.IO) { stage(own) }
            UpdateStatus.ReadyToRestart(update)
        } catch (e: CancellationException) {
            status = UpdateStatus.Available(update)
            throw e
        } catch (e: Exception) {
            downloadFailed(e, update)
        }
    }

    /** 下载到暂存目录并校验。增量更新还要把 app.zip 解开,逐个对照清单。 */
    private suspend fun stage(update: DesktopUpdate) {
        val staging = stagingDir(update)
        staging.deleteRecursively()
        staging.mkdirs()
        when (val plan = update.plan) {
            is DesktopUpdatePlan.Patch -> {
                val zip = download(plan.zip, staging.resolve(plan.zip.name), update)
                extractPatch(zip, plan.manifest, staging.resolve(PATCH_DIR))
                zip.delete()
            }
            is DesktopUpdatePlan.Installer -> download(plan.msi, staging.resolve(plan.msi.name), update)
            is DesktopUpdatePlan.Manual -> error("便携版不能整包更新")
        }
    }

    private suspend fun download(asset: ReleaseAsset, target: File, update: DesktopUpdate): File {
        val expected = asset.sha256 ?: throw ChecksumMismatchException("Release 没有公布 ${asset.name} 的摘要")
        val digest = MessageDigest.getInstance("SHA-256")
        target.outputStream().use { out ->
            releases.download(
                asset,
                onChunk = { buffer, length ->
                    out.write(buffer, 0, length)
                    digest.update(buffer, 0, length)
                },
                onProgress = { status = UpdateStatus.Downloading(update, it) },
            )
        }
        val actual = digest.digest().toHex()
        if (actual != expected) {
            target.delete()
            throw ChecksumMismatchException("${asset.name}: $actual != $expected")
        }
        return target
    }

    private suspend fun fetchVerified(asset: ReleaseAsset): ByteArray {
        val expected = asset.sha256 ?: throw ChecksumMismatchException("Release 没有公布 ${asset.name} 的摘要")
        val bytes = ByteArrayOutputStream()
        releases.download(asset, onChunk = { buffer, length -> bytes.write(buffer, 0, length) }, onProgress = {})
        val content = bytes.toByteArray()
        val actual = MessageDigest.getInstance("SHA-256").digest(content).toHex()
        if (actual != expected) throw ChecksumMismatchException("${asset.name}: $actual != $expected")
        return content
    }

    override suspend fun restartToInstall(update: AvailableUpdate) {
        val own = update.own()
        val installation = installation ?: return
        if (status !is UpdateStatus.ReadyToRestart) return
        val started = runCatching {
            withContext(Dispatchers.IO) { launchApplyScript(own, installation) }
        }.onFailure { BiliLog.w("启动更新脚本失败", it) }
        if (started.isFailure) {
            status = UpdateStatus.Failed(UpdateFailure.Launch, update)
            return
        }
        status = UpdateStatus.Installing(update)
        mutableExitRequests.tryEmit(Unit)
    }

    private fun launchApplyScript(update: DesktopUpdate, installation: Installation) {
        val staging = stagingDir(update)
        val script = staging.resolve("apply-update.ps1")
        val resource = checkNotNull(javaClass.getResourceAsStream("/update/apply-update.ps1")) { "缺少更新脚本" }
        resource.use { input -> script.outputStream().use { input.copyTo(it) } }
        val (mode, source) = when (val plan = update.plan) {
            is DesktopUpdatePlan.Patch -> "patch" to staging.resolve(PATCH_DIR)
            is DesktopUpdatePlan.Installer -> "msi" to staging.resolve(plan.msi.name)
            is DesktopUpdatePlan.Manual -> error("便携版不能整包更新")
        }
        // JDK 在 Windows 上以 CREATE_NO_WINDOW 创建子进程,控制台程序不会闪出窗口;
        // 子进程不随父进程退出,脚本在本进程退出后接着跑。
        ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", script.absolutePath,
            "-ProcessId", ProcessHandle.current().pid().toString(),
            "-InstallDir", installation.dir.absolutePath,
            "-Mode", mode,
            "-Source", source.absolutePath,
            "-Executable", installation.exe.name,
            "-LogFile", staging.resolve("update.log").absolutePath,
        )
            .directory(staging)
            // 脚本自己的日志之外,PowerShell 的解析错误之类只会出现在标准输出里。
            .redirectErrorStream(true)
            .redirectOutput(staging.resolve("powershell.log"))
            .start()
    }

    private fun stagingDir(update: DesktopUpdate) = stagingRoot.resolve(update.version)

    // apply-update.ps1 失败时在暂存目录留下这个文件;暂存目录要到下一次下载才清。
    override fun takePreviousFailure(version: String): Boolean {
        val marker = stagingRoot.resolve(version).resolve("failed")
        if (!marker.isFile) return false
        BiliLog.w("上次更新到 $version 未完成:${marker.readText().trim()}")
        marker.delete()
        return true
    }

    private fun isMsiInstall(dir: File): Boolean {
        val upgradeCode = System.getProperty(WindowsInstaller.UPGRADE_CODE_PROPERTY) ?: return false
        return WindowsInstaller.isInstalledAt(upgradeCode, dir)
    }

    companion object {
        private const val PATCH_DIR = "files"

        /** 换掉 Release 接口地址,用于在本机对着假的 Release 走一遍更新。 */
        private const val API_OVERRIDE_PROPERTY = "bilby.update.api"

        /** 只出 x64:mediamp 的运行时包只有 x64。 */
        private const val ARCH = "x64"

        /**
         * 版本取 `bilby.version`(即 [AppBuild.versionName]),不取 jpackage 写的
         * `jpackage.app-version`:后者是 MSI 的版本号,本地打的包一律是 1.0.0。
         * 安装位置取 `jpackage.app-path`,gradle run 时没有,那时只给下载页、开屏也不查。
         */
        fun create(): DesktopAppUpdater {
            val exe = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }
            val releases = GithubReleaseClient(
                http = HttpClient(OkHttp),
                userAgent = "Bilby/${AppBuild.versionName}",
                latestReleaseUrl = System.getProperty(API_OVERRIDE_PROPERTY) ?: GithubReleaseClient.LATEST_RELEASE_URL,
            )
            return DesktopAppUpdater(
                installation = exe?.let { Installation(it.parentFile, it) },
                checksOnStartup = exe != null && !AppBuild.debug && !AppBuild.versionName.endsWith("-dev"),
                releases = releases,
            )
        }
    }
}
