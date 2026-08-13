package dev.bilby.ui.live

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import dev.bilby.AppContainer
import dev.bilby.BiliLog
import dev.bilby.player.AudioPlaybackService
import dev.bilby.player.liveMediaId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/**
 * 直播间的接线:连 session、把选好的流交给服务、把状态和弹幕流交给界面。
 *
 * 和播放页一样,**页面不持有播放器**:它连一个 [MediaController],画面和控制都走它。
 */
@Composable
fun LiveRoomRoute(
    container: AppContainer,
    roomId: Long,
    onBack: () -> Unit,
    onUserClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: LiveRoomViewModel = viewModel(
        key = "live-$roomId",
        factory = viewModelFactory {
            initializer {
                LiveRoomViewModel(roomId, container.liveRepository, container.liveDanmakuClient, container.settings)
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val danmakuPrefs by container.settings.danmakuPrefs.collectAsStateWithLifecycle(
        initialValue = dev.bilby.data.DanmakuPrefs(),
    )

    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val future = MediaController.Builder(context, AudioPlaybackService.sessionToken(context))
            .buildAsync()
        future.addListener(
            {
                controller = runCatching { future.get() }
                    .onFailure { BiliLog.w("直播间连接播放服务失败", it) }
                    .getOrNull()
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            // 离开直播间就停:直播没有"后台接着听"的场景 —— 它一直在往前走,回来时听到的
            // 也不是离开时那一段。**不 release 播放器**,它归服务所有。
            controller?.pause()
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    val active = controller
    val audioState by AudioPlaybackService.state.collectAsStateWithLifecycle()

    // 开播了就交给服务。**命令报的是房间号和档位,不是地址** —— 取流归服务(见
    // AudioPlaybackService.resolveLiveStream),页面这一份只用来判断开没开播、能选哪几档。
    // 命令是幂等的:标题晚一步到只更新元数据,而档位或纯音频变了才重新取一次流。
    LaunchedEffect(active, state.isLive, state.currentQn, state.title) {
        if (!state.isLive) return@LaunchedEffect
        val connected = active ?: return@LaunchedEffect
        connected.sendCustomCommand(
            SessionCommand(AudioPlaybackService.ACTION_OPEN_LIVE, Bundle.EMPTY),
            bundleOf(
                AudioPlaybackService.EXTRA_ROOM_ID to roomId,
                AudioPlaybackService.EXTRA_LIVE_QN to state.currentQn,
                AudioPlaybackService.EXTRA_TITLE to state.title,
                AudioPlaybackService.EXTRA_UP_NAME to state.anchorName,
                AudioPlaybackService.EXTRA_COVER_URL to state.coverUrl,
            ),
        )
    }

    LiveRoomScreen(
        state = state,
        danmaku = vm.danmaku,
        player = active,
        // 播放器此刻装的是不是这个房间。和播放页同一个判据,只是标识换成了直播那一套。
        attached = audioState.loadKey == liveMediaId(roomId),
        danmakuPrefs = danmakuPrefs,
        // 弹幕开关是全局设置,不是这个房间的状态 —— 在直播间关掉,回到视频页也是关的,
        // 这与视频页那边改它的效果一致。
        onDanmakuEnabledChange = { enabled ->
            scope.launch(NonCancellable) { container.settings.saveDanmakuEnabled(enabled) }
        },
        onQualityChange = vm::setQuality,
        onLoadMoreGuards = vm::loadMoreGuards,
        onSendDanmaku = vm::sendDanmaku,
        onRetry = vm::load,
        roomId = roomId,
        onBack = onBack,
        onUserClick = onUserClick,
        modifier = modifier,
    )
}
