package dev.bilby.ui.login

import androidx.lifecycle.ViewModel
import dev.bilby.BiliLog
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.data.AuthRepository
import dev.bilby.data.QrPollStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LoginViewModel(private val auth: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow<LoginUiState>(LoginUiState.Loading)
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    private var session: Job? = null

    init {
        restart()
    }

    /** 重新申请二维码并开始轮询。刷新按钮和首次进入走同一条路径。 */
    fun restart() {
        session?.cancel()
        session = viewModelScope.launch {
            _state.value = LoginUiState.Loading
            when (val qr = auth.generateQrCode()) {
                is BiliResult.Ok -> pollUntilSettled(qr.value.key, qr.value.url)
                is BiliResult.ApiError -> _state.value = LoginUiState.Failed("获取二维码失败: ${qr.message}")
                is BiliResult.Failure -> _state.value = LoginUiState.Failed("网络错误: ${qr.cause.message}")
            }
        }
    }

    private suspend fun pollUntilSettled(key: String, url: String) {
        _state.value = LoginUiState.ShowQr(url, scanned = false)
        val deadline = System.currentTimeMillis() + AuthRepository.QR_TTL_MILLIS

        while (viewModelScope.isActive && System.currentTimeMillis() < deadline) {
            delay(AuthRepository.POLL_INTERVAL_MILLIS)
            when (val poll = auth.pollQrCode(key)) {
                is BiliResult.Ok -> when (poll.value) {
                    QrPollStatus.NotScanned -> _state.value = LoginUiState.ShowQr(url, scanned = false)
                    QrPollStatus.ScannedUnconfirmed -> _state.value = LoginUiState.ShowQr(url, scanned = true)
                    QrPollStatus.Expired -> {
                        _state.value = LoginUiState.Expired
                        return
                    }

                    QrPollStatus.Success -> {
                        _state.value = LoginUiState.Success
                        return
                    }
                }

                // 轮询期间的网络抖动不该让用户重新扫码,继续轮到超时为止。
                is BiliResult.Failure -> BiliLog.w("扫码登录轮询异常", poll.cause)
                is BiliResult.ApiError -> {
                    BiliLog.w("扫码登录轮询失败(${poll.code}): ${poll.message}")
                    _state.value = LoginUiState.Failed("轮询失败(${poll.code}): ${poll.message}")
                    return
                }
            }
        }
        // 服务端没来得及回 86038 就先本地判过期,免得停在一个扫不动的码上。
        if (_state.value !is LoginUiState.Success) _state.value = LoginUiState.Expired
    }
}
