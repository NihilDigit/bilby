package dev.bilby.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.bilby.BiliLog
import dev.bilby.resources.*
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.StringResource

/** 可供更新的版本。安装所需的附件由各平台的实现带着,界面只读这几项。 */
interface AvailableUpdate {
    val version: String
    val notes: String
    val pageUrl: String

    /** 这次实际要下载的字节数。桌面端增量更新时远小于整个安装包。 */
    val downloadSize: Long

    /**
     * 为 false 时只给下载页链接:Android 的 debug 包与 release 包名不同,装上去是另一个应用;
     * 桌面端的便携版与 gradle run 没有安装器可用。
     */
    val canInstallInApp: Boolean
}

/** 更新没走完的原因。界面只说「先换个网还是稍后再试」,异常原文只进日志。 */
enum class UpdateFailure(val message: StringResource) {
    Network(Res.string.update_failed_network),
    Check(Res.string.update_failed_check),
    Download(Res.string.update_failed_download),

    /** 重试多半还是同一个结果,该去下载页。 */
    Checksum(Res.string.update_failed_checksum),
    Launch(Res.string.update_failed_launch),
    InstallCancelled(Res.string.update_install_cancelled),
    Install(Res.string.update_install_failed),

    /** 桌面端:更新在应用退出后才进行,失败时只能留下记号,下次启动再说。 */
    PreviousAttempt(Res.string.update_previous_failed),
}

sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val update: AvailableUpdate) : UpdateStatus
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateStatus

    /** 已下载并校验,等用户同意退出重启。只有桌面端经过这一步:替换文件要先退出自己。 */
    data class ReadyToRestart(val update: AvailableUpdate) : UpdateStatus

    /** 已交给系统安装器,等用户确认。 */
    data class Installing(val update: AvailableUpdate) : UpdateStatus
    data class Failed(val reason: UpdateFailure, val update: AvailableUpdate? = null) : UpdateStatus
}

/**
 * 应用内更新。检查只在两处发生:开屏一次([checkOnStartup],一次进程一次)与设置页手动检查。
 * **没有后台轮询**,查更新只发生在应用正被使用的时候,红点与推送一样不做。
 *
 * 开屏弹窗与设置页读的是同一份 [status]:在设置页开始的下载,回到首页也还是那一份。
 */
interface AppUpdateService {
    /** 由 Compose State 支撑,界面直接读。 */
    val status: UpdateStatus

    /**
     * 开屏检查发现、还没被关掉的新版本,开屏弹窗据此显示。放在这里而不是界面的 remember 里:
     * Android 转屏重建界面后弹窗还在,而检查不会再做一次。
     */
    val startupUpdate: AvailableUpdate?

    /** [silent] 为 true 时失败不改状态,只记日志。 */
    suspend fun check(silent: Boolean = false)

    /**
     * 开屏检查,一次进程一次,开发构建不查。[isIgnored] 为真的版本不提示。
     * 失败只记日志:没人在等这个结果,弹一句「检查更新失败」只是打扰。
     */
    suspend fun checkOnStartup(isIgnored: suspend (version: String) -> Boolean)

    fun dismissStartupUpdate()

    /** Android 下完即交给系统安装器;桌面端下完停在 [UpdateStatus.ReadyToRestart]。 */
    suspend fun downloadAndInstall(update: AvailableUpdate)

    /** 桌面端:退出并替换文件,完成后重新启动。 */
    suspend fun restartToInstall(update: AvailableUpdate) {}
}

/**
 * 两端共用的检查逻辑:取 GitHub 最新 Release、比版本、按平台挑附件。
 *
 * [resolve] 返回 null 表示这个 Release 里还没有本平台的附件。桌面端的附件由另一个 job 在
 * Release 建好之后才挂上,这段时间按「没有新版本」处理,不报错。
 */
abstract class GithubUpdateService<U : AvailableUpdate>(
    protected val releases: GithubReleaseClient,
    private val currentVersion: String,
    /**
     * 开发构建为 false:本地版本号是 0.0.0-dev,比任何已发布的 tag 都小,每次冷启动都会弹一个
     * 「有新版本」挡在首页前面,而那个新版本正是手上这份代码的上一版。
     */
    private val checksOnStartup: Boolean,
) : AppUpdateService {

    final override var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
        protected set

    private var startupChecked = false

    protected abstract suspend fun resolve(release: LatestRelease): U?

    override suspend fun check(silent: Boolean) {
        if (status is UpdateStatus.Checking || status is UpdateStatus.Downloading || status is UpdateStatus.ReadyToRestart) return
        status = UpdateStatus.Checking
        val result = when (val check = releases.check(currentVersion)) {
            is ReleaseCheck.Newer -> try {
                resolve(check.release)?.let { UpdateStatus.Available(it) } ?: UpdateStatus.UpToDate
            } catch (e: CancellationException) {
                status = UpdateStatus.Idle
                throw e
            } catch (e: Exception) {
                checkFailed(e)
            }
            ReleaseCheck.UpToDate -> UpdateStatus.UpToDate
            is ReleaseCheck.Failed -> checkFailed(check.cause)
        }
        status = if (silent && result is UpdateStatus.Failed) UpdateStatus.Idle else result
    }

    final override var startupUpdate by mutableStateOf<AvailableUpdate?>(null)
        private set

    override suspend fun checkOnStartup(isIgnored: suspend (version: String) -> Boolean) {
        if (!checksOnStartup || startupChecked) return
        startupChecked = true
        check(silent = true)
        val update = (status as? UpdateStatus.Available)?.update ?: return
        // 上次装这一版没成功:照样弹出,忽略过也不算数。用户点过更新,说明并没有打算跳过它。
        if (takePreviousFailure(update.version)) {
            status = UpdateStatus.Failed(UpdateFailure.PreviousAttempt, update)
            startupUpdate = update
        } else if (!isIgnored(update.version)) {
            startupUpdate = update
        }
    }

    /** 上次更新到 [version] 是否失败过,读过即清掉。只有桌面端会这样。 */
    protected open fun takePreviousFailure(version: String): Boolean = false

    override fun dismissStartupUpdate() {
        startupUpdate = null
    }

    private fun checkFailed(cause: Throwable): UpdateStatus.Failed {
        BiliLog.w("检查更新失败", cause)
        return UpdateStatus.Failed(if (cause.isNetworkFailure()) UpdateFailure.Network else UpdateFailure.Check)
    }

    protected fun downloadFailed(cause: Throwable, update: AvailableUpdate): UpdateStatus.Failed {
        BiliLog.w("下载更新失败", cause)
        val reason = when {
            cause is ChecksumMismatchException -> UpdateFailure.Checksum
            cause.isNetworkFailure() -> UpdateFailure.Network
            else -> UpdateFailure.Download
        }
        return UpdateStatus.Failed(reason, update)
    }

    @Suppress("UNCHECKED_CAST")
    protected fun AvailableUpdate.own(): U = this as U
}
