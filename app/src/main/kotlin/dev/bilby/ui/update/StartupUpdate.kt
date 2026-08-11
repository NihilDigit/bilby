package dev.bilby.ui.update

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.BuildConfig
import dev.bilby.R
import dev.bilby.data.SettingsStore
import dev.bilby.data.UpdateCheck
import dev.bilby.data.UpdateInfo
import dev.bilby.data.UpdateRepository
import dev.bilby.ui.components.MarkdownText
import dev.bilby.ui.theme.Spacing
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 开屏那一次更新检查的状态。
 *
 * [Idle] 覆盖了"还没查""查完了没有新版""用户压掉了"三种情况 —— 它们在界面上是同一件事:
 * 什么都不显示。分开建模只会得到三个必须一起处理的空状态。
 */
sealed interface StartupUpdateState {
    data object Idle : StartupUpdateState
    data class Available(val info: UpdateInfo) : StartupUpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : StartupUpdateState
    data class Ready(val info: UpdateInfo, val apk: File) : StartupUpdateState
    data class Failed(val info: UpdateInfo, val message: String) : StartupUpdateState
}

/**
 * 开屏检查一次有没有新版本。
 *
 * **一次进程一次,不是一次开屏一次。** 检查在 `init` 里做,而这个 ViewModel 挂在 Activity 的
 * store 上 —— 转屏、切深色、从后台回来都不会再查。查更新是网络请求,而它对用户的价值一天里
 * 只兑现一次。
 *
 * **失败什么都不弹。** 主动去设置页点"检查更新"的人在等一个答复,开屏这一次没人在等 ——
 * 弹一句"检查更新失败"只是在通知用户一件他没问过的事情失败了。日志照记。
 */
class StartupUpdateViewModel(
    private val updateRepository: UpdateRepository,
    private val settings: SettingsStore,
    private val downloadDir: File,
) : ViewModel() {

    private val _state = MutableStateFlow<StartupUpdateState>(StartupUpdateState.Idle)
    val state: StateFlow<StartupUpdateState> = _state.asStateFlow()

    init {
        // **debug 构建不查。** 本地跑出来的版本号是 0.0.0-dev,比任何已发布的 tag 都小,于是
        // 每次冷启动都会弹一个"有新版本"——而那个"新版本"正是开发者手上这份代码的上一版。
        // 它挡在首页前面,还得点一次才能开始干活。
        if (!BuildConfig.DEBUG) {
            viewModelScope.launch {
                when (val check = updateRepository.check(BuildConfig.VERSION_NAME)) {
                    is UpdateCheck.Available -> {
                        // 压过的那一版不再提。判据是版本号相等,不是"压过没有",见 SettingsStore。
                        if (settings.ignoredUpdateVersion.first() != check.info.version) {
                            _state.value = StartupUpdateState.Available(check.info)
                        }
                    }
                    UpdateCheck.UpToDate -> Unit
                    is UpdateCheck.Failed -> BiliLog.w("开屏检查更新失败: ${check.message}")
                }
            }
        }
    }

    fun download(info: UpdateInfo) {
        if (_state.value is StartupUpdateState.Downloading) return
        _state.value = StartupUpdateState.Downloading(info, 0f)
        viewModelScope.launch {
            val target = File(downloadDir, info.assetName)
            // 上一次下到一半的残包会让安装器报"解析包出现问题",而那句话指不向真正的原因。
            if (target.exists()) target.delete()
            val result = updateRepository.download(info, target) { progress ->
                _state.update { current ->
                    if (current is StartupUpdateState.Downloading) {
                        StartupUpdateState.Downloading(info, progress)
                    } else {
                        current
                    }
                }
            }
            _state.value = result.fold(
                onSuccess = { apk -> StartupUpdateState.Ready(info, apk) },
                onFailure = { error -> StartupUpdateState.Failed(info, error.message ?: "下载失败") },
            )
        }
    }

    /** 这一版不再提。落盘走 [NonCancellable]:紧接着就要关弹窗,写没写完不该看 UI 的脸色。 */
    fun ignore(info: UpdateInfo) {
        _state.value = StartupUpdateState.Idle
        viewModelScope.launch(NonCancellable) { settings.saveIgnoredUpdateVersion(info.version) }
    }

    /** 这次先不弹了,但不写盘 —— 下次冷启动还会问。 */
    fun dismiss() {
        _state.value = StartupUpdateState.Idle
    }
}

/**
 * 新版本提示。
 *
 * 按 M3 的 basic dialog 排:一个标题、一段正文、右下角两个文字按钮,confirm 在最右。
 * **不加图标也不加插画** —— 规范给 hero icon 的位置是"内容本身需要一个视觉锚点"时用的,
 * 而这里的内容是一段版本说明,它自己就是锚点。
 *
 * 「忽略此版本」放在 dismiss 那一侧而不是做成第三个按钮:M3 的对话框只给两个动作位,第三个
 * 按钮会挤成两行,而"这次先不看"本来就有出口 —— 点外面。
 *
 * 下载中不关弹窗,进度画在正文里:关掉之后它就成了一个没有任何反馈的后台任务,而用户刚刚
 * 按下的是"下载"。
 */
@Composable
fun StartupUpdateDialog(
    state: StartupUpdateState,
    onDownload: (UpdateInfo) -> Unit,
    onInstall: (File) -> Unit,
    onIgnore: (UpdateInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val info = when (state) {
        StartupUpdateState.Idle -> return
        is StartupUpdateState.Available -> state.info
        is StartupUpdateState.Downloading -> state.info
        is StartupUpdateState.Ready -> state.info
        is StartupUpdateState.Failed -> state.info
    }
    val downloading = state is StartupUpdateState.Downloading

    AlertDialog(
        // 下载中点外面不关:那一下会让正在进行的下载失去唯一的进度显示。
        onDismissRequest = { if (!downloading) onDismiss() },
        // **撑开到接近整屏宽。** 平台默认宽度是给"一句话加两个按钮"定的,而这里装的是一整篇
        // 更新说明:按默认宽度排,每行只剩十来个字,一段话要折成七八行,读起来像一根柱子。
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(DialogWidthFraction),
        title = { Text(stringResource(R.string.update_dialog_title, info.version)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 更新说明可能很长(整篇 changelog),给它一个上限再滚,不然按钮会被顶出屏幕。
                //
                // **按 Markdown 渲染**,和助理回复共用同一份渲染器:release note 本来就是
                // Markdown 写的,当纯文本画出来满屏是 `**`、`-` 和字面的 `&#13;`,而那正是
                // 用户此刻唯一要读的东西。
                Column(
                    modifier = Modifier
                        .heightIn(max = NotesMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MarkdownText(
                        text = info.notes.ifBlank { stringResource(R.string.update_dialog_no_notes) },
                        stopAtHeadings = DownloadPageSections,
                    )
                }
                when (state) {
                    is StartupUpdateState.Downloading -> LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.Cozy),
                    )
                    is StartupUpdateState.Failed -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Spacing.Cozy),
                    )
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is StartupUpdateState.Ready -> TextButton(onClick = { onInstall(state.apk) }) {
                    Text(stringResource(R.string.update_install))
                }
                is StartupUpdateState.Downloading -> TextButton(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.update_downloading, (state.progress * 100).toInt()))
                }
                // 失败之后 confirm 就是"再来一次":重下和第一次下没有区别,不必另给一个入口。
                is StartupUpdateState.Failed -> TextButton(onClick = { onDownload(info) }) {
                    Text(stringResource(R.string.action_retry))
                }
                else -> TextButton(onClick = { onDownload(info) }) {
                    Text(stringResource(R.string.update_download))
                }
            }
        },
        dismissButton = {
            if (!downloading) {
                TextButton(onClick = { onIgnore(info) }) {
                    Text(stringResource(R.string.update_ignore_version))
                }
            }
        },
    )
}

/** 更新说明最多占这么高,再长就在里面滚。再高按钮会被顶出屏幕。 */
private val NotesMaxHeight = 380.dp

/**
 * release 正文里属于下载页的几节,应用内不画(见 [MarkdownText] 的 stopAtHeadings)。
 *
 * 与 `.github/workflows/release.yml` 里模板拼上去的标题逐字对应。**改那边要改这里** ——
 * 对不上的后果是这两节又出现在更新对话框里,不会有任何报错。
 */
private val DownloadPageSections = setOf("安装", "校验")

/** 弹窗占屏宽的比例。两侧各留一点,让人看得出底下还有东西。 */
private const val DialogWidthFraction = 0.92f
