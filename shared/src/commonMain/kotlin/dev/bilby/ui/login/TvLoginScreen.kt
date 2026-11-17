package dev.bilby.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.data.TvLoginRepository
import dev.bilby.data.TvPollStatus
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.CaptionDragHeight
import dev.bilby.ui.LocalWindowCaption
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.windowDragArea
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * TV 扫码登录,现在是唯一的登录入口(见 [TvLoginRepository])。一次扫码同时拿到
 * cookie(供所有读接口用)和 access_key(供点赞/投币等 app 端写接口用)。
 */
sealed interface TvLoginUiState {
    data object Requesting : TvLoginUiState // 正在申请二维码
    data class WaitingScan(val url: String) : TvLoginUiState
    data class ScannedUnconfirmed(val url: String) : TvLoginUiState
    data object Expired : TvLoginUiState
    data class Failed(val message: String) : TvLoginUiState
    data object Success : TvLoginUiState
}

private val QrImageSize = Dimens.QrCode
private val QrPadSize = 240.dp
private val LoginCardMaxWidth = 440.dp

/**
 * 扫码登录欢迎卡:居中的 `surfaceContainer` `extraLarge`(28dp)圆角卡片 + 桌面端融合窗口拖动栏与控制键。
 *
 * **二维码那块不跟主题**,理由见 [QrCodeImage]:固定纯白 [FixedColors.QrBackground] 衬垫(16dp 圆角)
 * 承托纯黑纯白二维码与自带 4 模块静区,深浅色及纯黑主题下扫码器均能稳定定位三个寻像角。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TvLoginScreen(
    state: TvLoginUiState,
    onRefresh: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val caption = LocalWindowCaption.current
    caption?.Host()

    Scaffold(modifier = modifier) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = Spacing.Comfortable,
                        end = Spacing.Comfortable,
                        top = if (caption != null) CaptionDragHeight + Spacing.Comfortable else Spacing.Loose,
                        bottom = Spacing.Loose,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier
                        .widthIn(max = LoginCardMaxWidth)
                        .fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.Loose, vertical = Spacing.Spacious),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.Loose),
                    ) {
                        Text(
                            text = stringResource(Res.string.login_title),
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )

                        Box(contentAlignment = Alignment.Center) {
                            when (state) {
                                is TvLoginUiState.WaitingScan -> QrSurface {
                                    QrCodeImage(state.url, Modifier.size(QrImageSize))
                                }

                                is TvLoginUiState.ScannedUnconfirmed -> {
                                    QrSurface { QrCodeImage(state.url, Modifier.size(QrImageSize)) }
                                    QrOverlay {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            stringResource(Res.string.login_scanned),
                                            style = MaterialTheme.typography.bodyMediumEmphasized,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = Spacing.Comfortable),
                                        )
                                    }
                                }

                                is TvLoginUiState.Expired -> {
                                    QrPlaceholder(showSpinner = false)
                                    QrOverlay {
                                        Text(
                                            stringResource(Res.string.login_qr_expired),
                                            style = MaterialTheme.typography.bodyMediumEmphasized,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center,
                                        )
                                        FilledTonalButton(onClick = onRefresh) {
                                            Text(stringResource(Res.string.action_refresh))
                                        }
                                    }
                                }

                                is TvLoginUiState.Requesting,
                                is TvLoginUiState.Failed,
                                is TvLoginUiState.Success,
                                -> QrPlaceholder(showSpinner = state is TvLoginUiState.Requesting)
                            }
                        }

                        when (state) {
                            is TvLoginUiState.WaitingScan ->
                                Text(
                                    text = stringResource(Res.string.login_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )

                            is TvLoginUiState.Failed -> Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                            ) {
                                Text(
                                    text = state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center,
                                )
                                TextButton(onClick = onRefresh) { Text(stringResource(Res.string.action_retry)) }
                            }

                            is TvLoginUiState.Success -> Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                            ) {
                                Text(
                                    stringResource(Res.string.login_success),
                                    style = MaterialTheme.typography.bodyMediumEmphasized,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                FilledTonalButton(onClick = onDone) { Text(stringResource(Res.string.action_done)) }
                            }

                            else -> Unit
                        }
                    }
                }
            }

            if (caption != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Tight, vertical = Spacing.Tight),
                ) {
                    Spacer(
                        modifier = Modifier
                            .weight(1f)
                            .height(CaptionDragHeight)
                            .windowDragArea(),
                    )
                    caption.Buttons()
                }
            }
        }
    }
}

/**
 * 二维码的白底(16dp 圆角 `shapes.large`,240dp 衬垫承托 220dp 码图)。
 *
 * **固定纯白 [FixedColors.QrBackground],不是 surface** —— 这一块是给扫码器看的,不是给人看的,
 * 跟着主题走会在深色下变成深灰底黑码,B 站客户端直接报"未成功解析到二维码"。
 * 这条踩过一次,见 [QrCodeImage] 的注释。
 */
@Composable
private fun QrSurface(content: @Composable () -> Unit) {
    Surface(
        color = FixedColors.QrBackground,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.size(QrPadSize),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun QrPlaceholder(showSpinner: Boolean) {
    Surface(
        modifier = Modifier.size(QrPadSize),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // 走整块那一档不是行内那一档:等的是这张码本身,它占满了这个位置。
            if (showSpinner) FullScreenLoading()
        }
    }
}

/** 盖在二维码上的状态提示。半透明是为了让下面的码还看得见,表示"码还在,只是状态变了"。 */
@Composable
private fun QrOverlay(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .size(QrPadSize)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = OverlayAlpha),
                MaterialTheme.shapes.large,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            content = content,
        )
    }
}

private const val OverlayAlpha = 0.94f

class TvLoginViewModel(private val repository: TvLoginRepository) : ViewModel() {

    private val _state = MutableStateFlow<TvLoginUiState>(TvLoginUiState.Requesting)
    val state: StateFlow<TvLoginUiState> = _state.asStateFlow()

    private var session: Job? = null

    init {
        restart()
    }

    /** 重新申请二维码并开始轮询。刷新按钮和首次进入走同一条路径。 */
    fun restart() {
        session?.cancel()
        session = viewModelScope.launch {
            _state.value = TvLoginUiState.Requesting
            when (val qr = repository.requestQrCode()) {
                is BiliResult.Ok -> pollUntilSettled(qr.value.authCode, qr.value.url)
                is BiliResult.ApiError -> _state.value = TvLoginUiState.Failed(SERVER_REFUSED)
                is BiliResult.Failure -> _state.value = TvLoginUiState.Failed(networkText(qr.cause))
            }
        }
    }

    private suspend fun pollUntilSettled(authCode: String, url: String) {
        _state.value = TvLoginUiState.WaitingScan(url)
        val deadline = System.currentTimeMillis() + TvLoginRepository.QR_TTL_MILLIS

        while (viewModelScope.isActive && System.currentTimeMillis() < deadline) {
            delay(TvLoginRepository.POLL_INTERVAL_MILLIS)
            when (val poll = repository.poll(authCode)) {
                is BiliResult.Ok -> when (poll.value) {
                    TvPollStatus.NotScanned -> _state.value = TvLoginUiState.WaitingScan(url)
                    TvPollStatus.ScannedUnconfirmed -> _state.value = TvLoginUiState.ScannedUnconfirmed(url)
                    TvPollStatus.Expired -> {
                        _state.value = TvLoginUiState.Expired
                        return
                    }

                    TvPollStatus.Success -> {
                        _state.value = TvLoginUiState.Success
                        return
                    }
                }

                // 轮询期间的网络抖动不该让用户重新扫码,继续轮到超时为止。
                is BiliResult.Failure -> Unit
                is BiliResult.ApiError -> {
                    _state.value = TvLoginUiState.Failed(POLL_REFUSED)
                    return
                }
            }
        }
        if (_state.value !is TvLoginUiState.Success) _state.value = TvLoginUiState.Expired
    }
}

/**
 * 失败时屏幕上说的话。
 *
 * **接口的 message 和错误码都不上屏。** 登录是新手必经的第一屏,而「网络错误: Unable to
 * resolve host」这样一行既说不出哪里出了问题,也说不出该做什么。原文和错误码
 * [TvLoginRepository] 每条分支都已经写进 [dev.bilby.BiliLog],这里不再打第二遍。
 */
private const val SERVER_REFUSED = "二维码没有取到,稍后重试"

/** 扫过之后接口才拒绝,当前这个码已经作废,所以说的是重新扫,不是等。 */
private const val POLL_REFUSED = "登录没有完成,重新扫一次"

/**
 * [IOException] 一族(DNS 解析不了、连不上、超时、证书)是用户自己能处理的;
 * 剩下的他做什么都没用,只能说清没成功。
 */
private fun networkText(cause: Throwable): String =
    if (cause is IOException) "网络不通,检查网络后重试" else SERVER_REFUSED

private const val PreviewUrl = "https://passport.bilibili.com/x/passport-tv-login/h5/qrcode/auth?auth_code=demo"

@Preview(showBackground = true, name = "Requesting")
@Composable
private fun TvLoginScreenRequestingPreview() {
    BilbyTheme { TvLoginScreen(TvLoginUiState.Requesting, onRefresh = {}, onDone = {}) }
}

@Preview(showBackground = true, name = "WaitingScan")
@Composable
private fun TvLoginScreenWaitingScanPreview() {
    BilbyTheme { TvLoginScreen(TvLoginUiState.WaitingScan(PreviewUrl), onRefresh = {}, onDone = {}) }
}

@Preview(showBackground = true, name = "ScannedUnconfirmed")
@Composable
private fun TvLoginScreenScannedPreview() {
    BilbyTheme { TvLoginScreen(TvLoginUiState.ScannedUnconfirmed(PreviewUrl), onRefresh = {}, onDone = {}) }
}

@Preview(showBackground = true, name = "Expired")
@Composable
private fun TvLoginScreenExpiredPreview() {
    BilbyTheme { TvLoginScreen(TvLoginUiState.Expired, onRefresh = {}, onDone = {}) }
}

@Preview(showBackground = true, name = "Failed")
@Composable
private fun TvLoginScreenFailedPreview() {
    BilbyTheme {
        TvLoginScreen(TvLoginUiState.Failed(networkText(IOException())), onRefresh = {}, onDone = {})
    }
}

@Preview(showBackground = true, name = "Success")
@Composable
private fun TvLoginScreenSuccessPreview() {
    BilbyTheme { TvLoginScreen(TvLoginUiState.Success, onRefresh = {}, onDone = {}) }
}
