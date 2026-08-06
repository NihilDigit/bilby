package dev.bilby.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.data.TvLoginRepository
import dev.bilby.data.TvPollStatus
import dev.bilby.ui.theme.BilbyTheme
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

private val QrSize = 220.dp

@Composable
fun TvLoginScreen(
    state: TvLoginUiState,
    onRefresh: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("扫码登录", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.padding(top = 24.dp))

            Box(contentAlignment = Alignment.Center) {
                when (state) {
                    is TvLoginUiState.Requesting -> QrPlaceholder()

                    is TvLoginUiState.WaitingScan ->
                        QrCodeImage(content = state.url, modifier = Modifier.size(QrSize))

                    is TvLoginUiState.ScannedUnconfirmed -> {
                        QrCodeImage(content = state.url, modifier = Modifier.size(QrSize))
                        QrOverlay {
                            Text(
                                "已扫描,请在手机上确认",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    is TvLoginUiState.Expired -> {
                        QrPlaceholder()
                        QrOverlay {
                            Text(
                                "二维码已过期",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = onRefresh) { Text("刷新") }
                        }
                    }

                    is TvLoginUiState.Failed -> QrPlaceholder()

                    is TvLoginUiState.Success -> QrPlaceholder()
                }
            }

            Spacer(Modifier.padding(top = 16.dp))

            when (state) {
                is TvLoginUiState.WaitingScan ->
                    Text("用 B 站 App 扫码登录", style = MaterialTheme.typography.bodyMedium)

                is TvLoginUiState.Failed -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.padding(top = 12.dp))
                        Button(onClick = onRefresh) { Text("重试") }
                    }
                }

                is TvLoginUiState.Success -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("登录成功", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.padding(top = 12.dp))
                        Button(onClick = onDone) { Text("完成") }
                    }
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun QrPlaceholder() {
    Surface(
        modifier = Modifier.size(QrSize),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun QrOverlay(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .size(QrSize)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

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
