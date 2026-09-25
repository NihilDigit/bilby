package dev.bilby.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.bilby.AppBuild
import dev.bilby.data.SettingsStore
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.LocalSystemActions
import dev.bilby.ui.components.MarkdownText
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.offline.formatBytes
import dev.bilby.ui.theme.Spacing
import dev.bilby.update.AppUpdateService
import dev.bilby.update.AvailableUpdate
import dev.bilby.update.UpdateFailure
import dev.bilby.update.UpdateStatus
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开屏那一次更新检查。挂在应用根部、登录之后,一次进程一次(见
 * [AppUpdateService.checkOnStartup])。
 *
 * **失败什么都不弹。** 主动去设置页点「检查更新」的人在等一个答复,开屏这一次没人在等 ——
 * 弹一句「检查更新失败」只是在通知用户一件他没问过的事情失败了。日志照记。
 */
@Composable
fun StartupUpdatePrompt(updater: AppUpdateService, settings: SettingsStore) {
    LaunchedEffect(updater) {
        // 压过的那一版不再提。判据是版本号相等,不是「压过没有」,见 SettingsStore。
        updater.checkOnStartup { version -> settings.ignoredUpdateVersion.first() == version }
    }
    val update = updater.startupUpdate ?: return
    val scope = rememberCoroutineScope()
    UpdateDialog(
        updater = updater,
        update = update,
        onDismiss = updater::dismissStartupUpdate,
        // 落盘走 NonCancellable:紧接着就要关弹窗,写没写完不该看界面的脸色。
        onIgnore = {
            updater.dismissStartupUpdate()
            scope.launch(NonCancellable) { settings.saveIgnoredUpdateVersion(update.version) }
        },
    )
}

/**
 * 新版本提示。开屏与设置页共用这一个。
 *
 * 按 M3 的 basic dialog 排:标题(新版本号,下面一行当前版本与下载大小)、一块带底色的
 * 更新说明、右下角两个按钮,主动作是实心按钮、在最右。**不加图标也不加插画** —— 这里的
 * 内容是一段版本说明,它自己就是视觉锚点。
 *
 * dismiss 那一侧开屏时是「忽略此版本」,从设置页打开时是「在浏览器中查看」:主动来查的人
 * 不需要被问要不要跳过。M3 的对话框只给两个动作位,「这次先不看」本来就有出口 —— 点外面。
 *
 * 下载中不关弹窗,进度画在正文里:关掉之后它就成了一个没有任何反馈的后台任务,而用户刚刚
 * 按下的是「下载」。
 *
 * @param onIgnore 非 null 即开屏弹出的那一版。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdateDialog(
    updater: AppUpdateService,
    update: AvailableUpdate,
    onDismiss: () -> Unit,
    onIgnore: (() -> Unit)? = null,
) {
    val system = LocalSystemActions.current
    val scope = rememberCoroutineScope()
    // 状态属于别的版本(比如已经又查了一次)时,按这一版可下载处理。
    val status = updater.status.takeIf { it.concerns(update) } ?: UpdateStatus.Available(update)
    val busy = status is UpdateStatus.Downloading || status is UpdateStatus.Installing
    val openPage = { system.openInBrowser(update.pageUrl) }
    val install = { scope.launch { updater.downloadAndInstall(update) } }

    AlertDialog(
        // 下载中点外面不关:那一下会让正在进行的下载失去唯一的进度显示。
        onDismissRequest = { if (!busy) onDismiss() },
        // **撑开到接近整屏宽。** 平台默认宽度是给「一句话加两个按钮」定的,而这里装的是一整篇
        // 更新说明:按默认宽度排,每行只剩十来个字。宽窗口里封顶,一行太长同样难读。
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(DialogWidthFraction).widthIn(max = DialogMaxWidth),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
                Text(stringResource(Res.string.update_dialog_title, update.version))
                // 从哪一版升上来、要下多少:下不下载在这两件事上定,尤其是流量下。
                Text(
                    text = stringResource(Res.string.update_dialog_current, AppBuild.versionName) +
                        (if (update.downloadSize > 0) MetaSeparator + formatBytes(update.downloadSize) else ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 更新说明可能很长(整篇 changelog),给它一个上限再滚,不然按钮会被顶出屏幕。
                // 装在一块带底色的圆角区域里:它是这个对话框里唯一会滚的一块,光秃秃地排在
                // 标题下面时,滚到一半看不出边在哪。按 Markdown 渲染:release note 本来就是
                // Markdown 写的,当纯文本画出来满屏是 `**` 和 `-`。
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .heightIn(max = NotesMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(Spacing.Cozy),
                    ) {
                        MarkdownText(
                            text = update.notes.ifBlank { stringResource(Res.string.update_dialog_no_notes) },
                            stopAtHeadings = DownloadPageSections,
                        )
                    }
                }
                when (status) {
                    // 下面写已下多少、共多少:只有一根条的话,慢网下它几秒不动,看不出是卡了还是在走。
                    is UpdateStatus.Downloading -> Column(
                        modifier = Modifier.padding(top = Spacing.Cozy),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    ) {
                        LinearWavyProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (update.downloadSize > 0) {
                            Text(
                                text = formatBytes((update.downloadSize * status.progress).toLong()) +
                                    " / " + formatBytes(update.downloadSize),
                                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is UpdateStatus.Failed -> Text(
                        text = stringResource(status.reason.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Spacing.Cozy),
                    )
                    else -> Unit
                }
            }
        },
        // 主动作用实心按钮:这个对话框就是为这一下弹出来的,它和 dismiss 那一侧不是同一个分量。
        confirmButton = {
            when {
                // debug 包、便携版:装不了,只能去下载页。
                !update.canInstallInApp -> Button(onClick = openPage) {
                    Text(stringResource(Res.string.update_open_page))
                }
                status is UpdateStatus.Downloading -> Button(onClick = {}, enabled = false) {
                    Text(stringResource(Res.string.update_downloading, (status.progress * 100).toInt()))
                }
                status is UpdateStatus.ReadyToRestart -> Button(onClick = { scope.launch { updater.restartToInstall(update) } }) {
                    Text(stringResource(Res.string.update_restart))
                }
                status is UpdateStatus.Installing -> Button(onClick = {}, enabled = false) {
                    Text(stringResource(Res.string.update_installing))
                }
                // 校验不符,重试多半还是同一个结果,该去下载页。
                status is UpdateStatus.Failed && status.reason == UpdateFailure.Checksum -> Button(onClick = openPage) {
                    Text(stringResource(Res.string.update_open_page))
                }
                // 失败之后 confirm 就是「再来一次」:重下和第一次下没有区别,不必另给一个入口。
                status is UpdateStatus.Failed -> Button(onClick = { install() }) {
                    Text(stringResource(Res.string.action_retry))
                }
                else -> Button(onClick = { install() }) {
                    Text(stringResource(Res.string.update_download_and_install))
                }
            }
        },
        dismissButton = {
            if (!busy) {
                if (onIgnore != null) {
                    TextButton(onClick = onIgnore) { Text(stringResource(Res.string.update_ignore_version)) }
                } else if (update.canInstallInApp) {
                    TextButton(onClick = openPage) { Text(stringResource(Res.string.update_view_in_browser)) }
                }
            }
        },
    )
}

/** 这个状态说的是不是 [update] 这一版。 */
private fun UpdateStatus.concerns(update: AvailableUpdate): Boolean = when (this) {
    is UpdateStatus.Available -> this.update.version == update.version
    is UpdateStatus.Downloading -> this.update.version == update.version
    is UpdateStatus.ReadyToRestart -> this.update.version == update.version
    is UpdateStatus.Installing -> this.update.version == update.version
    is UpdateStatus.Failed -> this.update?.version == update.version
    else -> false
}

/** 更新说明最多占这么高,再长就在里面滚。再高按钮会被顶出屏幕。 */
private val NotesMaxHeight = 380.dp

/** 宽窗口里对话框的上限,同 Piko:再宽一行字就太长。 */
private val DialogMaxWidth = 560.dp

/**
 * release 正文里属于下载页的几节,应用内不画(见 [MarkdownText] 的 stopAtHeadings)。
 *
 * 与 `.github/workflows/release.yml` 里模板拼上去的标题逐字对应。**改那边要改这里** ——
 * 对不上的后果是这两节又出现在更新对话框里,不会有任何报错。
 */
private val DownloadPageSections = setOf("安装", "校验")

/** 弹窗占屏宽的比例。两侧各留一点,让人看得出底下还有东西。 */
private const val DialogWidthFraction = 0.92f
