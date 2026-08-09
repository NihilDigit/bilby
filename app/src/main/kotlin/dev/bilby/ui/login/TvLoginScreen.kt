package dev.bilby.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.data.TvLoginRepository
import dev.bilby.data.TvPollStatus
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
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

private val QrSize = Dimens.QrCode

/**
 * 扫码登录。整屏只有一件事,所以内容垂直居中、不放顶栏 —— 这一页没有"返回"可去。
 *
 * **二维码那块不跟主题**,理由见 [QrCodeImage]。这里配合它的一点是:二维码永远画在一块
 * 白色 Surface 上,而不是直接落在主题背景上。位图自带 4 模块静区,但静区之外还是主题色;
 * 深色主题下那圈白边和黑底的硬边界会让部分扫码器把边界当成定位图形的一部分。
 */
@Composable
fun TvLoginScreen(
    state: TvLoginUiState,
    onRefresh: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { insets ->
        AdaptiveContent(
            modifier = Modifier.fillMaxSize().padding(insets),
            maxWidth = 520.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.Loose),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.Loose, Alignment.CenterVertically),
            ) {
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineSmall)

            Box(contentAlignment = Alignment.Center) {
                when (state) {
                    is TvLoginUiState.WaitingScan -> QrSurface { QrCodeImage(state.url, Modifier.size(QrSize)) }

                    is TvLoginUiState.ScannedUnconfirmed -> {
                        QrSurface { QrCodeImage(state.url, Modifier.size(QrSize)) }
                        QrOverlay {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                stringResource(R.string.login_scanned),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    is TvLoginUiState.Expired -> {
                        QrPlaceholder(showSpinner = false)
                        QrOverlay {
                            Text(
                                stringResource(R.string.login_qr_expired),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = onRefresh) {
                                Text(stringResource(R.string.action_refresh))
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
                        text = stringResource(R.string.login_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    TextButton(onClick = onRefresh) { Text(stringResource(R.string.action_retry)) }
                }

                is TvLoginUiState.Success -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                ) {
                    Text(stringResource(R.string.login_success), style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = onDone) { Text(stringResource(R.string.action_done)) }
                }

                else -> Unit
            }
            }
        }
    }
}

/**
 * 二维码的白底。**固定白色,不是 surface** —— 这一块是给扫码器看的,不是给人看的,
 * 跟着主题走会在深色下变成深灰底黑码,B 站客户端直接报"未成功解析到二维码"。
 * 这条踩过一次,见 [QrCodeImage] 的注释。
 */
@Composable
private fun QrSurface(content: @Composable () -> Unit) {
    Surface(
        color = FixedColors.QrBackground,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.size(QrSize),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun QrPlaceholder(showSpinner: Boolean) {
    Surface(
        modifier = Modifier.size(QrSize),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (showSpinner) CircularProgressIndicator()
        }
    }
}

/** 盖在二维码上的状态提示。半透明是为了让下面的码还看得见,表示"码还在,只是状态变了"。 */
@Composable
private fun QrOverlay(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .size(QrSize)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = OverlayAlpha),
                MaterialTheme.shapes.medium,
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

private const val OverlayAlpha = 0.92f

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
                is BiliResult.ApiError -> _state.value = TvLoginUiState.Failed("获取二维码失败: ${qr.message}")
                is BiliResult.Failure -> _state.value = TvLoginUiState.Failed("网络错误: ${qr.cause.message}")
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
                    _state.value = TvLoginUiState.Failed("轮询失败(${poll.code}): ${poll.message}")
                    return
                }
            }
        }
        if (_state.value !is TvLoginUiState.Success) _state.value = TvLoginUiState.Expired
    }
}

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
    BilbyTheme { TvLoginScreen(TvLoginUiState.Failed("网络连接失败"), onRefresh = {}, onDone = {}) }
}

@Preview(showBackground = true, name = "Success")
@Composable
private fun TvLoginScreenSuccessPreview() {
    BilbyTheme { TvLoginScreen(TvLoginUiState.Success, onRefresh = {}, onDone = {}) }
}
