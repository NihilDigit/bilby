package dev.bilby.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.bilby.AppContainerOwner
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /**
     * 退到后台(切走、锁屏)时把普通视频暂停,听视频继续。**判断在服务那边**,这里只负责
     * 报告"前台没了"——听不听得下去取决于播放器当前装的是什么,那是服务知道的事。
     *
     * 转屏不经过这里:manifest 声明了 configChanges,Activity 不重建。
     */
    override fun onStop() {
        super.onStop()
        (application as AppContainerOwner).container.platform.playback.pauseForAppBackground()
    }

    /**
     * 外面递进来的那条链接。**用 MutableStateFlow 而不是直接读 `intent`**:应用已经在跑时
     * 系统走的是 [onNewIntent],那时 composition 早就建好了,只读一次 intent 的写法收不到
     * 第二条链接。
     */
    private val incomingLink = MutableStateFlow<String?>(null)

    /** Android 12 及以下没有系统的应用语言,由这里套上用户选的那一种,见 [AppLanguageStore]。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageStore.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AppContainerOwner).container
        // 构造下载器:它在初始化时把上次进程被杀打断的条目接着下(见 OfflineDownloader 的 init)。
        // 放在这里而不是 Application.onCreate:补报心跳的 Worker 会在后台拉起进程,那时接着下
        // 就要从后台起前台服务,Android 12 起不允许。Activity 在前台,这一刻起得来。
        container.offlineDownloader
        incomingLink.value = linkFrom(intent)
        setContent {
            val systemActions = remember { AndroidSystemActions(this) }
            CompositionLocalProvider(
                LocalSystemActions provides systemActions,
                LocalPlaybackHost provides container.platform.playback,
            ) {
                BilbyRoot(container, incomingLink, windowChrome = { BilbyWindowChrome() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingLink.value = linkFrom(intent)
    }

    /**
     * VIEW 递的是链接本身;SEND 递的是一段话,链接埋在里面
     * (「【标题】 https://b23.tv/xxx 复制这段内容…」)。
     */
    private fun linkFrom(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(BilbyLink::extractUrl)
        else -> null
    }
}
