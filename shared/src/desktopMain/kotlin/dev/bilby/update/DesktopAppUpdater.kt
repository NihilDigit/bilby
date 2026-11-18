package dev.bilby.update

import com.github.luben.zstd.util.ZstdVersion
import dev.bilby.AppBuild
import dev.bilby.BiliLog
import dev.bilby.desktopDataDir
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
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Properties
import kotlin.math.abs
import kotlin.system.exitProcess

data class DesktopUpdate(
    override val version: String,
    override val notes: String,
    override val pageUrl: String,
    val plan: DesktopUpdatePlan,
) : AvailableUpdate {
    override val downloadSize: Long
        get() = when (plan) {
            is DesktopUpdatePlan.Patch -> plan.zip.size
            is DesktopUpdatePlan.Delta -> plan.delta.size
            is DesktopUpdatePlan.Installer -> plan.msi.size
            is DesktopUpdatePlan.Manual -> plan.msi.size
        }
    override val canInstallInApp: Boolean get() = plan !is DesktopUpdatePlan.Manual
}

sealed interface DesktopUpdatePlan {
    /** 只替换每次构建都会变的那几个文件,其余与本机逐字节相同。 */
    data class Patch(val zip: ReleaseAsset, val manifest: UpdateManifest) : DesktopUpdatePlan

    /**
     * 换的文件与 [Patch] 相同,但只下以本机版本为基准的差分。app.zip 装着全部 jar 与 AOT 缓存,
     * 差分只带其中变了的字节。本机文件对不上时退回 [fallback]。
     */
    data class Delta(val delta: ReleaseAsset, val fallback: Patch) : DesktopUpdatePlan

    /** 整包重装。MSI 的升级是先卸后装,要等 Bilby 退出后再跑,否则弹文件占用的对话框。 */
    data class Installer(val msi: ReleaseAsset) : DesktopUpdatePlan

    /** 便携版且无法增量,应用目录不可写,或开发时从 gradle 直接跑:只给下载页。 */
    data class Manual(val msi: ReleaseAsset) : DesktopUpdatePlan
}

/**
 * Windows 端的应用内更新。
 *
 * 每个版本除了 MSI 与便携 zip,还附一份应用目录清单(files.json)与只含易变文件的 app.zip。
 * 检查时把本机应用目录与新版清单逐个比对:不同之处都在 app.zip 里就增量更新,否则 MSI 装的
 * 走整包重装,便携版只给下载页。增量更新时若有以本机版本为基准的差分包(CI 为最近几个版本
 * 各出一份),改下差分包。
 *
 * 替换文件要先退出自己:下载校验完停在 [UpdateStatus.ReadyToRestart],用户同意后以 `javaw.exe -jar`
 * 启动纯 `java.base` 更新器 [dev.bilby.updater.WindowsUpdateApplier](仅在需替换 runtime 或走 MSI
 * 升级时将随包 `runtime/` 独立复制至数据目录下的辅助目录,常规补丁直接复用已安装的 `javaw.exe`
 * 而不落地任何可执行或 DLL 文件),等待握手文件就绪后本进程经 [exitRequests] 退出,由更新器等
 * 启动器与 JVM 双双退出、执行带事务回滚的补丁替换或 `msiexec`、再启动新版本。
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
    /** 本机应用目录。[exe] 是启动器,更新后由更新器重新拉起。 */
    private class Installation(val dir: File, val exe: File)

    private val json = Json { ignoreUnknownKeys = true }
    private val stagingRoot = stagingRoot()

    private val mutableExitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 更新器已启动并就绪,入口收到后退出应用。 */
    val exitRequests: SharedFlow<Unit> = mutableExitRequests.asSharedFlow()

    override suspend fun resolve(release: LatestRelease): DesktopUpdate? {
        val prefix = "bilby-windows-$ARCH-${release.version}"
        // 三个附件缺一个都按还没有新版本处理:Release 还是草稿时它们陆续挂上。
        val manifestAsset = release.asset("$prefix-files.json") ?: return null
        val zip = release.asset("$prefix-app.zip") ?: return null
        val msi = release.asset("$prefix.msi") ?: return null
        fun update(plan: DesktopUpdatePlan) = DesktopUpdate(release.version, release.notes, release.pageUrl, plan)

        // 写不进的(便携版解压在 Program Files 下)换不了文件,更新器要到退出之后才失败,每次启动又提示同一个新版。
        val installation = installation?.takeIf { isWritableDir(it.dir) && isWritableDir(it.dir.resolve("app")) }
            ?: return update(DesktopUpdatePlan.Manual(msi))
        val manifest = json.decodeFromString(UpdateManifest.serializer(), fetchVerified(manifestAsset).decodeToString())
        return withContext(Dispatchers.IO) {
            when {
                canPatch(installation.dir, manifest) -> {
                    val patch = DesktopUpdatePlan.Patch(zip, manifest)
                    val delta = release.asset("$prefix-from-${AppBuild.versionName}.zip")?.takeIf { ZstdPatch.isAvailable }
                    update(delta?.let { DesktopUpdatePlan.Delta(it, patch) } ?: patch)
                }
                isMsiInstall(installation.dir) -> update(DesktopUpdatePlan.Installer(msi))
                else -> update(DesktopUpdatePlan.Manual(msi))
            }
        }
    }

    // 桌面端的 Installing 是退出前的最后几秒:这时再查,状态会回到 Available,下载按钮又能点了。
    // Android 不能照此处理:系统安装器被划掉时可能不回调,Installing 会一直停着。
    override suspend fun check(silent: Boolean) {
        if (status is UpdateStatus.Installing) return
        super.check(silent)
    }

    override suspend fun downloadAndInstall(update: AvailableUpdate) {
        val own = update.own()
        if (!own.canInstallInApp || isStagingOrLaunched()) return
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
        keepScriptLogs(staging)
        staging.deleteRecursively()
        staging.mkdirs()
        when (val plan = update.plan) {
            is DesktopUpdatePlan.Patch -> stagePatch(plan, staging, update)
            is DesktopUpdatePlan.Delta -> {
                val zip = download(plan.delta, staging.resolve(plan.delta.name), update)
                try {
                    applyDelta(zip, plan.fallback.manifest, checkNotNull(installation).dir, staging.resolve(PATCH_DIR))
                } catch (e: ChecksumMismatchException) {
                    // 本机的 jar 或 AOT 缓存被改动过,或者不是差分所基于的那一版。
                    BiliLog.w("差分还原失败,改下完整补丁包", e)
                    staging.resolve(PATCH_DIR).deleteRecursively()
                    stagePatch(plan.fallback, staging, update)
                }
                zip.delete()
            }
            is DesktopUpdatePlan.Installer -> download(plan.msi, staging.resolve(plan.msi.name), update)
            is DesktopUpdatePlan.Manual -> error("便携版不能整包更新")
        }
    }

    private suspend fun stagePatch(plan: DesktopUpdatePlan.Patch, staging: File, update: DesktopUpdate) {
        val zip = download(plan.zip, staging.resolve(plan.zip.name), update)
        extractPatch(zip, plan.manifest, staging.resolve(PATCH_DIR))
        zip.delete()
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
        // 挂起之前就改状态:连点两下时,第二下看到的已不是 ReadyToRestart,不会再起一个更新器。
        status = UpdateStatus.Installing(update)
        val started = runCatching {
            withContext(Dispatchers.IO) { launchApplier(own, installation) }
        }.onFailure { BiliLog.w("启动更新程序失败", it) }
        if (started.isFailure) {
            status = UpdateStatus.Failed(UpdateFailure.Launch, update)
            return
        }
        mutableExitRequests.tryEmit(Unit)
    }

    /**
     * 暂存目录正被下载写入,或已交给更新器。此时再下载会先清空暂存目录,更新器拿到的就是
     * 重下到一半的文件。检查与改状态之间没有挂起点,两次点击在主线程上排队,后一次必然看到
     * 前一次改过的状态。
     */
    private fun isStagingOrLaunched(): Boolean = when (status) {
        is UpdateStatus.Downloading, is UpdateStatus.ReadyToRestart, is UpdateStatus.Installing -> true
        else -> false
    }

    private fun launchApplier(update: DesktopUpdate, installation: Installation) {
        val staging = stagingDir(update)
        val plan = update.plan
        val (mode, source) = when (plan) {
            is DesktopUpdatePlan.Patch, is DesktopUpdatePlan.Delta -> "patch" to staging.resolve(PATCH_DIR)
            is DesktopUpdatePlan.Installer -> "msi" to staging.resolve(plan.msi.name)
            is DesktopUpdatePlan.Manual -> error("便携版不能整包更新")
        }
        val checksums = staging.resolve(CHECKSUMS_FILE)
        checksums.writeText(stagedChecksums(plan).joinToString("") { (sha256, path) -> "$sha256  $path\n" })
        // 新版应用目录的全部文件。换完之后 app 与 runtime 下不在其中的即旧版留下的,更新器删掉;MSI 模式用不上。
        val keep = staging.resolve(KEEP_FILE)
        val manifest = manifestOf(plan)
        manifest?.let { keep.writeText(it.files.joinToString("") { entry -> "${entry.path}\n" }) }
        val touchesRuntime = manifest?.files?.any { it.patch && it.path.startsWith("runtime/") } == true
        launchWindowsApplier(
            mode = mode,
            installDir = installation.dir,
            exe = installation.exe,
            staging = staging,
            source = source,
            checksums = checksums,
            keep = keep.takeIf { it.isFile },
            touchesRuntime = touchesRuntime,
        )
    }

    /**
     * 更新器安装前逐个复核的摘要,路径相对暂存目录。下载时已校验过一遍,这里再交给更新器,是因为
     * 从校验完到本进程退出之间,暂存目录仍可能被改写:0.15.1 就把重下到一半的 MSI 交给了 msiexec。
     */
    private fun stagedChecksums(plan: DesktopUpdatePlan): List<Pair<String, String>> = when (plan) {
        is DesktopUpdatePlan.Patch -> plan.manifest.patchChecksums()
        is DesktopUpdatePlan.Delta -> plan.fallback.manifest.patchChecksums()
        is DesktopUpdatePlan.Installer -> listOf(checkNotNull(plan.msi.sha256) to plan.msi.name)
        is DesktopUpdatePlan.Manual -> error("便携版不能整包更新")
    }

    private fun manifestOf(plan: DesktopUpdatePlan): UpdateManifest? = when (plan) {
        is DesktopUpdatePlan.Patch -> plan.manifest
        is DesktopUpdatePlan.Delta -> plan.fallback.manifest
        is DesktopUpdatePlan.Installer, is DesktopUpdatePlan.Manual -> null
    }

    private fun UpdateManifest.patchChecksums() = files.filter { it.patch }.map { it.sha256 to "$PATCH_DIR/${it.path}" }

    private fun stagingDir(update: DesktopUpdate) = stagingRoot.resolve(update.version)

    /**
     * 重新暂存之前,把上一次更新器留下的日志追加进暂存根目录的 [HISTORY_LOG]。暂存目录每次下载都整个清掉,
     * 上一次为什么没装上就跟着没了,用户反馈时也无从查起。
     */
    private fun keepScriptLogs(staging: File) {
        runCatching {
            val history = stagingRoot.resolve(HISTORY_LOG)
            SCRIPT_LOGS
                .map(staging::resolve)
                .filter { it.isFile && it.length() > 0 }
                .forEach { log ->
                    val decoded = decodeScriptLog(log)
                    if (decoded.isNotBlank()) {
                        history.appendText("== ${staging.name}/${log.name}\n${decoded.trimEnd()}\n", Charsets.UTF_8)
                    }
                }
            if (history.isFile && history.length() > HISTORY_LIMIT) {
                history.writeBytes(truncateUtf8Tail(history.readBytes(), HISTORY_LIMIT / 2))
            }
        }.onFailure { BiliLog.w("保留更新历史日志失败", it) }
    }

    // WindowsUpdateApplier 失败时在暂存目录留下这个文件;暂存目录要到下一次下载才清。
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
        private const val CHECKSUMS_FILE = "staged.sha256"
        private const val KEEP_FILE = "keep.txt"
        private const val HISTORY_LOG = "update-history.log"
        private const val HISTORY_LIMIT = 1024 * 1024
        private val SCRIPT_LOGS = listOf("update.log", "applier.log", "powershell.log", "msiexec.log")

        /** 补丁的事务记录,在安装目录下,由 WindowsUpdateApplier 读写。 */
        private const val JOURNAL_FILE = ".bilby-update.journal"
        private const val APPLIER_RESOURCE = "/update/bilby-update-applier.bin"
        private const val HELPER_DIR_PREFIX = "bilby-update-helper-"

        /** 换掉 Release 接口地址,用于在本机对着假的 Release 走一遍更新。 */
        private const val API_OVERRIDE_PROPERTY = "bilby.update.api"
        private val FORWARDED_RELAUNCH_PROPS = listOf(API_OVERRIDE_PROPERTY)
        private val SANITIZED_HELPER_ENV = listOf(
            "JAVA_TOOL_OPTIONS",
            "JDK_JAVA_OPTIONS",
            "_JAVA_OPTIONS",
            "CLASSPATH",
            "_JPACKAGE_LAUNCHER",
        )

        /**
         * 发行包的架构与 JVM 的架构相同。x64 版跑在 ARM64 的仿真下时这里也是 x64,更新仍取 x64 的附件;
         * 换成 arm64 版要手动装一次 MSI,两个架构的升级标识相同,装上即原地替换。
         */
        private val ARCH = if (System.getProperty("os.arch") == "aarch64") "arm64" else "x64"

        /**
         * 暂存目录放在数据根目录下(`%APPDATA%\Bilby\update`),不再写进 `%TEMP%`,
         * 既避免跨实例互相覆盖暂存文件,也减少安全软件对 `%TEMP%` 落地更新器的误报。
         */
        private fun stagingRoot(): File =
            desktopDataDir().resolve("update").let { runCatching { it.canonicalFile }.getOrDefault(it) }

        /**
         * 辅助运行工作目录优先放在数据目录下(`%APPDATA%\Bilby\update-helper`),确保既不在 `%TEMP%`,
         * 也不在 [installDir] 内(防止锁定安装目录或被误当旧文件清理)。
         */
        private fun helperBaseDir(installDir: File): File {
            fun realOrNormalized(file: File): Path {
                val norm = file.toPath().toAbsolutePath().normalize()
                return runCatching { if (Files.exists(norm)) norm.toRealPath() else norm }.getOrDefault(norm)
            }
            val installPath = realOrNormalized(installDir)
            val candidates = listOfNotNull(
                desktopDataDir().resolve("update-helper"),
                System.getenv("LOCALAPPDATA")?.let(::File)?.resolve("Bilby/update-helper"),
                File(System.getProperty("java.io.tmpdir")),
            )
            return candidates.firstOrNull { !realOrNormalized(it).startsWith(installPath) }
                ?: File(System.getProperty("java.io.tmpdir"))
        }

        private fun decodeScriptLog(file: File): String {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return ""
            val text = when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                    String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
                looksLikeUtf16Le(bytes) -> String(bytes, Charsets.UTF_16LE)
                else -> String(bytes, Charsets.UTF_8)
            }
            return text.replace("\u0000", "")
        }

        private fun looksLikeUtf16Le(bytes: ByteArray): Boolean {
            if (bytes.size < 4 || bytes.size % 2 != 0) return false
            val samplePairs = minOf(bytes.size / 2, 128)
            var oddZeros = 0
            for (i in 0 until samplePairs) {
                if (bytes[i * 2 + 1] == 0.toByte()) oddZeros++
            }
            return oddZeros * 2 >= samplePairs
        }

        private fun truncateUtf8Tail(bytes: ByteArray, keepBytes: Int): ByteArray {
            if (bytes.size <= keepBytes) return bytes
            var start = bytes.size - keepBytes
            while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) {
                start++
            }
            val newline = (start until minOf(bytes.size, start + 512)).firstOrNull { bytes[it] == '\n'.code.toByte() }
            if (newline != null && newline + 1 < bytes.size) {
                start = newline + 1
            }
            return bytes.copyOfRange(start, bytes.size)
        }

        private fun appProcessSpecs(exe: File): List<String> {
            val self = ProcessHandle.current()
            val exeCanonical = runCatching { exe.canonicalFile }.getOrDefault(exe)
            val launcher = self.parent().orElse(null)?.takeIf { parent ->
                parent.info().command()
                    .map { runCatching { File(it).canonicalFile == exeCanonical }.getOrDefault(false) }
                    .orElse(false)
            }
            return listOfNotNull(self, launcher).map { handle ->
                val startMs = handle.info().startInstant().map { it.toEpochMilli() }.orElse(-1L)
                "${handle.pid()}:$startMs"
            }
        }

        private fun copyDirectoryTree(source: Path, target: Path) {
            Files.walk(source).use { stream ->
                val it = stream.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    val rel = source.relativize(p)
                    val dst = target.resolve(rel)
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(dst)
                    } else {
                        dst.parent?.let { Files.createDirectories(it) }
                        Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }

        /**
         * 以 `javaw.exe -jar` 启动纯 `java.base` 更新器 `WindowsUpdateApplier`。
         *
         * 常规补丁不触及 `runtime/` 时直接复用安装目录内已有的 `javaw.exe`,不向磁盘复制任何
         * `.exe` / `.dll`,避免触发安全软件对释放并执行可执行文件的误报;仅在 `msi` 整包重装、
         * `recover` 恢复或补丁自身修改 `runtime/` 时将随包 `runtime/` 独立复制至数据目录下的
         * 辅助工作区以解除文件占用。必须等子进程写出 `ready` 握手文件后才返回。
         */
        internal fun launchWindowsApplier(
            mode: String,
            installDir: File,
            exe: File,
            staging: File,
            source: File?,
            checksums: File?,
            keep: File?,
            watchedProcessSpecs: List<String> = appProcessSpecs(exe),
            waitSeconds: Int = 120,
            touchesRuntime: Boolean = false,
            helperRoot: File? = null,
        ): Process {
            staging.mkdirs()
            val helperBase = (helperRoot ?: helperBaseDir(installDir)).apply { mkdirs() }
            val helperDir = Files.createTempDirectory(helperBase.toPath(), HELPER_DIR_PREFIX).toFile()
            var launchedOk = false
            try {
                val needsIsolatedRuntime = mode != "patch" || touchesRuntime
                val installedRuntime = installDir.resolve("runtime")
                val javaBin = if (installedRuntime.resolve("bin/javaw.exe").isFile) {
                    if (needsIsolatedRuntime) {
                        val copiedRuntime = helperDir.resolve("runtime")
                        copyDirectoryTree(installedRuntime.toPath(), copiedRuntime.toPath())
                        copiedRuntime.resolve("bin/javaw.exe").also {
                            check(it.isFile) { "复制的更新器运行时缺少 javaw.exe" }
                        }
                    } else {
                        installedRuntime.resolve("bin/javaw.exe")
                    }
                } else {
                    val currentHome = File(System.getProperty("java.home"))
                    currentHome.resolve("bin/javaw.exe").takeIf { it.isFile }
                        ?: currentHome.resolve("bin/java.exe")
                }
                val applierJar = helperDir.resolve("bilby-update-applier.jar")
                val resource = checkNotNull(DesktopAppUpdater::class.java.getResourceAsStream(APPLIER_RESOURCE)) {
                    "缺少内置更新器资源 $APPLIER_RESOURCE"
                }
                resource.use { input -> applierJar.outputStream().use { input.copyTo(it) } }

                val readyFile = helperDir.resolve("ready")
                val ownerPidFile = helperDir.resolve("owner.pid")
                val requestFile = helperDir.resolve("request.properties")
                val props = Properties().apply {
                    setProperty("mode", mode)
                    setProperty("pids", watchedProcessSpecs.joinToString(","))
                    setProperty("installDir", installDir.absolutePath)
                    setProperty("executable", exe.name)
                    setProperty("logFile", staging.resolve("update.log").absolutePath)
                    setProperty("source", source?.absolutePath.orEmpty())
                    setProperty("checksums", checksums?.absolutePath.orEmpty())
                    setProperty("keepList", keep?.absolutePath.orEmpty())
                    setProperty("waitSeconds", waitSeconds.toString())
                    setProperty("readyFile", readyFile.absolutePath)
                    setProperty("ownerPidFile", ownerPidFile.absolutePath)
                    for (propName in FORWARDED_RELAUNCH_PROPS) {
                        System.getProperty(propName)?.takeIf { it.isNotEmpty() }?.let { propVal ->
                            setProperty("relaunchProp.$propName", propVal)
                        }
                    }
                }
                requestFile.writer(Charsets.UTF_8).use { props.store(it, null) }

                val pb = ProcessBuilder(
                    javaBin.absolutePath,
                    "-Xms16m",
                    "-Xmx64m",
                    "-XX:-UsePerfData",
                    "-Djava.awt.headless=true",
                    "-jar",
                    applierJar.absolutePath,
                    requestFile.absolutePath,
                )
                    .directory(helperDir)
                    .redirectErrorStream(true)
                    .redirectOutput(staging.resolve("applier.log"))
                val env = pb.environment()
                val keysToDrop = env.keys.filter { k -> SANITIZED_HELPER_ENV.any { it.equals(k, ignoreCase = true) } }
                keysToDrop.forEach(env::remove)

                val proc = pb.start()
                runCatching { proc.outputStream.close() }
                val deadline = System.nanoTime() + 15_000_000_000L
                while (!readyFile.isFile) {
                    if (!proc.isAlive) {
                        val errOut = staging.resolve("applier.log").takeIf { it.isFile }?.readText().orEmpty().trim()
                        error("更新器提前退出 (code=${proc.exitValue()}): $errOut")
                    }
                    if (System.nanoTime() >= deadline) {
                        proc.destroyForcibly()
                        error("等待更新器就绪握手超时")
                    }
                    Thread.sleep(50L)
                }
                launchedOk = true
                return proc
            } finally {
                if (!launchedOk) {
                    runCatching { helperDir.deleteRecursively() }
                }
            }
        }

        private fun cleanupFinishedHelpers(installDir: File) {
            val roots = linkedSetOf(
                helperBaseDir(installDir),
                File(System.getProperty("java.io.tmpdir")),
            )
            val staleCutoffMs = System.currentTimeMillis() - 10 * 60 * 1000L
            for (root in roots) {
                val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith(HELPER_DIR_PREFIX) } ?: continue
                for (dir in dirs) {
                    runCatching {
                        val ownerFile = dir.resolve("owner.pid")
                        val stillRunning = if (ownerFile.isFile) {
                            val parts = ownerFile.readText(Charsets.UTF_8).trim().split(':')
                            val pid = parts.getOrNull(0)?.toLongOrNull()
                            val expectedStart = parts.getOrNull(1)?.toLongOrNull() ?: -1L
                            if (pid == null) {
                                false
                            } else {
                                val handle = ProcessHandle.of(pid).orElse(null)
                                if (handle == null || !handle.isAlive) {
                                    false
                                } else {
                                    val actualStart = handle.info().startInstant().map { it.toEpochMilli() }.orElse(-1L)
                                    expectedStart <= 0L || actualStart <= 0L || abs(actualStart - expectedStart) <= 5_000L
                                }
                            }
                        } else {
                            dir.lastModified() > staleCutoffMs
                        }
                        if (!stillRunning) {
                            dir.deleteRecursively()
                        }
                    }
                }
            }
        }

        private fun isWritableDir(dir: File): Boolean {
            if (!dir.isDirectory) return false
            val probe = runCatching { Files.createTempFile(dir.toPath(), ".write-probe-", null) }.getOrNull() ?: return false
            runCatching { Files.deleteIfExists(probe) }
            return true
        }

        /**
         * 上次的补丁没做完(断电、更新进程被杀)时留下事务记录。换文件的顺序保证这时仍启动得起来,但 jar 已是新旧混杂,
         * 本进程正开着它们,换不回去:把更新器以 recover 模式交出去,由它等本进程退出、按记录回滚、再拉起旧版。
         * 返回 true 表示已交出,调用方随即退出。记录被独占锁定说明更新器还在干活(更新中途又点开了 Bilby),不管它。
         */
        private fun handOverInterruptedUpdate(installDir: File, exe: File): Boolean {
            val journal = installDir.resolve(JOURNAL_FILE)
            if (!journal.isFile) return false
            val staging = try {
                FileChannel.open(
                    journal.toPath(),
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                ).use { ch ->
                    val lock = try {
                        ch.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                    if (lock == null) {
                        BiliLog.w("更新器正在持有事务日志锁,跳过中断恢复")
                        return false
                    }
                    lock.use {
                        Channels.newReader(ch, Charsets.UTF_8).buffered().readLine()
                            ?.takeIf { it.startsWith("staging\t") }?.substringAfter('\t')?.let(::File)
                            ?: stagingRoot().resolve("recover")
                    }
                }
            } catch (_: NoSuchFileException) {
                return false
            } catch (e: IOException) {
                BiliLog.w("更新器正在持有事务日志锁,跳过中断恢复", e)
                return false
            }
            return runCatching {
                launchWindowsApplier(
                    mode = "recover",
                    installDir = installDir,
                    exe = exe,
                    staging = staging,
                    source = null,
                    checksums = null,
                    keep = null,
                )
                BiliLog.w("检测到上次更新中断,已移交更新器回滚并重启")
            }.onFailure { BiliLog.w("启动中断更新恢复程序失败", it) }.isSuccess
        }

        private val ROOT_PATCHED = Regex("""[^\\/]+\.exe""")
        private val APP_PATCHED = Regex("""[^\\/]+\.(jar|cfg|aot)|\.jpackage\.xml""")

        private fun removeUpdateLeftovers(installDir: File) {
            fun leftovers(dir: File, patched: Regex) = dir.listFiles { file ->
                file.isFile && (file.name.endsWith(".old") || file.name.endsWith(".new")) &&
                    patched.matches(file.name.dropLast(4))
            }.orEmpty().toList()
            val found = leftovers(installDir, ROOT_PATCHED) + leftovers(installDir.resolve("app"), APP_PATCHED)
            found.forEach { leftover ->
                if (leftover.delete()) BiliLog.d("已清理更新残留 ${leftover.name}")
            }
        }

        /**
         * 安装包把 zstd-jni 的 DLL 放在资源目录的 zstd 子目录里。zstd-jni 默认把它从 jar 解压到
         * %TEMP%,进程占着删不掉,每次更新留一份。资源目录里没有时(gradle run、测试)沿用默认行为。
         */
        private fun useBundledZstd() {
            val dir = System.getProperty("compose.application.resources.dir") ?: return
            val dll = File(dir, "zstd/${System.mapLibraryName("libzstd-jni-${ZstdVersion.VERSION}")}")
            if (dll.isFile) System.setProperty("ZstdNativePath", dll.absolutePath)
        }

        /**
         * 版本取 `bilby.version`(即 [AppBuild.versionName]),不取 jpackage 写的
         * `jpackage.app-version`:后者是 MSI 的版本号,本地打的包一律是 1.0.0。
         * 安装位置取 `jpackage.app-path`,gradle run 时没有,那时只给下载页、开屏也不查。
         */
        fun create(): DesktopAppUpdater {
            useBundledZstd()
            val exe = System.getProperty("jpackage.app-path")?.let(::File)?.takeIf { it.isFile }?.also { launcher ->
                val dir = launcher.parentFile ?: return@also
                if (handOverInterruptedUpdate(dir, launcher)) {
                    exitProcess(0)
                }
                if (!dir.resolve(JOURNAL_FILE).exists()) {
                    removeUpdateLeftovers(dir)
                    Thread({ cleanupFinishedHelpers(dir) }, "Bilby-UpdateHelper-Cleanup").apply {
                        isDaemon = true
                        start()
                    }
                }
            }
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
