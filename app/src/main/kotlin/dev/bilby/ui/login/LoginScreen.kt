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
import dev.bilby.ui.theme.BilbyTheme

sealed interface LoginUiState {
    data object Loading : LoginUiState // 正在申请二维码
    data class ShowQr(val url: String, val scanned: Boolean) : LoginUiState // scanned=true 表示已扫待确认
    data object Expired : LoginUiState // 二维码过期,需要用户点「刷新」
    data class Failed(val message: String) : LoginUiState
    data object Success : LoginUiState
}

private val QrSize = 220.dp

@Composable
fun LoginScreen(state: LoginUiState, onRefresh: () -> Unit) {
    Scaffold { insets ->
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
                    is LoginUiState.Loading -> QrPlaceholder()

                    is LoginUiState.ShowQr -> {
                        QrCodeImage(content = state.url, modifier = Modifier.size(QrSize))
                        if (state.scanned) {
                            QrOverlay {
                                Text(
                                    "已扫描,请在手机上确认",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    is LoginUiState.Expired -> {
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

                    is LoginUiState.Failed -> QrPlaceholder()

                    is LoginUiState.Success -> QrPlaceholder()
                }
            }

            Spacer(Modifier.padding(top = 16.dp))

            when (state) {
                is LoginUiState.ShowQr ->
                    if (!state.scanned) {
                        Text("用 B 站 App 扫码", style = MaterialTheme.typography.bodyMedium)
                    }

                is LoginUiState.Failed -> {
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

                is LoginUiState.Success -> Text("登录成功", style = MaterialTheme.typography.bodyMedium)

                else -> Unit
            }
        }
    }
}

/** 与二维码同尺寸的占位,避免 Loading -> ShowQr 切换时布局跳动。 */
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

/** 盖在二维码上的半透明遮罩 + 文案/操作。 */
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

private const val PreviewUrl = "https://passport.bilibili.com/x/passport-login/web/qrcode?oauthKey=demo"

@Preview(showBackground = true, name = "Loading")
@Composable
private fun LoginScreenLoadingPreview() {
    BilbyTheme { LoginScreen(LoginUiState.Loading, onRefresh = {}) }
}

@Preview(showBackground = true, name = "ShowQr")
@Composable
private fun LoginScreenShowQrPreview() {
    BilbyTheme { LoginScreen(LoginUiState.ShowQr(url = PreviewUrl, scanned = false), onRefresh = {}) }
}

@Preview(showBackground = true, name = "ShowQr-Scanned")
@Composable
private fun LoginScreenScannedPreview() {
    BilbyTheme { LoginScreen(LoginUiState.ShowQr(url = PreviewUrl, scanned = true), onRefresh = {}) }
}

@Preview(showBackground = true, name = "Expired")
@Composable
private fun LoginScreenExpiredPreview() {
    BilbyTheme { LoginScreen(LoginUiState.Expired, onRefresh = {}) }
}

@Preview(showBackground = true, name = "Failed")
@Composable
private fun LoginScreenFailedPreview() {
    BilbyTheme { LoginScreen(LoginUiState.Failed("网络连接失败"), onRefresh = {}) }
}

@Preview(showBackground = true, name = "Success")
@Composable
private fun LoginScreenSuccessPreview() {
    BilbyTheme { LoginScreen(LoginUiState.Success, onRefresh = {}) }
}
