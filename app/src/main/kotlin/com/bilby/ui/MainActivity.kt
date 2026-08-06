package com.bilby.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.bilby.api.BiliResult
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.bilby.AppContainer
import com.bilby.BilbyApplication
import com.bilby.ui.feed.FeedScreen
import com.bilby.ui.feed.FeedViewModel
import com.bilby.ui.login.LoginScreen
import com.bilby.ui.login.LoginViewModel
import com.bilby.ui.theme.BilbyTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as BilbyApplication).container
        setContent {
            BilbyTheme {
                BilbyApp(container)
            }
        }
    }
}

/**
 * 登录态是唯一的根级分支:没有 Cookie 就只有登录页,拿到就直接进动态流。
 * 不做启动页、不做引导页 —— 打开即到要看的东西。
 */
@Composable
private fun BilbyApp(container: AppContainer) {
    // DataStore 第一帧是异步的:null 表示还没读出来,此时什么都不画,
    // 否则已登录用户每次冷启动都会闪一下登录页。
    val credentials by container.settings.credentials.collectAsStateWithLifecycle(initialValue = null)
    val loaded = credentials ?: return

    if (!loaded.isLoggedIn) {
        val vm: LoginViewModel = viewModel(
            factory = viewModelFactory { initializer { LoginViewModel(container.authRepository) } },
        )
        val state by vm.state.collectAsStateWithLifecycle()
        LoginScreen(state = state, onRefresh = vm::restart)
        return
    }

    // 每次冷启动检查一次是否该刷新 Cookie。cookie/info 自己会判断,不该刷时只是一次极轻的
    // 请求;放在这里而不是每次请求前检查,是因为 B 站的判断粒度本来就是"每日第一次访问"。
    LaunchedEffect(Unit) {
        val result = container.cookieRefresher.refreshIfNeeded()
        if (result is BiliResult.ApiError) {
            Log.w("Bilby", "cookie 刷新失败(${result.code}): ${result.message}")
        }
    }

    val backStack = rememberNavBackStack(Home)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Home> { HomeScreen(container) }
        },
    )
}

@Composable
private fun HomeScreen(container: AppContainer) {
    val vm: FeedViewModel = viewModel(
        factory = viewModelFactory { initializer { FeedViewModel(container.dynamicRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    FeedScreen(
        state = state,
        onLoadMore = vm::loadMore,
        onRetry = vm::loadFirstPage,
        onItemClick = { /* M2 播放页 */ },
    )
}
