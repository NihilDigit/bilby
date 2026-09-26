package dev.bilby.ui.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.bilby.AppContainer
import dev.bilby.data.StoredDanmakuPrefsEditor
import dev.bilby.player.PlaybackCommand
import dev.bilby.player.PlayerHandle
import dev.bilby.player.liveMediaId
import dev.bilby.ui.LocalPlaybackHost
import dev.bilby.ui.player.rememberSettledPlaybackError

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
    val playback = LocalPlaybackHost.current
    val vm: LiveRoomViewModel = viewModel(
        key = "live-$roomId",
        factory = viewModelFactory {
            initializer {
                LiveRoomViewModel(
                    roomId,
                    container.liveRepository,
                    container.liveDanmakuClient,
                    container.settings,
                    container.danmakusRepository,
                    container.messageRepository::userFaces,
                    container.relationRepository,
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val danmakuPrefs by container.settings.danmakuPrefs.collectAsStateWithLifecycle(
        initialValue = dev.bilby.data.DanmakuPrefs(),
    )
    // 弹幕设置是全局的,不是这个房间的状态:在直播间改了,回到视频页也是改过的。
    val danmakuEditor = remember { StoredDanmakuPrefsEditor(container.settings, container.persistScope) }

    /**
     * 这个直播间只要声音。
     *
     * **页内的临时状态**:`rememberSaveable` 让它活过转屏,关掉直播间就随页面消失;不持久化、
     * 不跨房间,换一个房间回到默认的关。
     *
     * 它只改变一件事:退到后台时这一场不被 [dev.bilby.player.PlaybackHost.pauseForAppBackground] 停掉。
     * 页面仍在返回栈上、服务接着放、通知栏照常可控。离开直播间即停那条(下面的 onDispose)
     * 不受影响 —— 直播一直在往前走,回来时听到的也不是离开时那一段。
     */
    var onlyAudio by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(onlyAudio) {
        playback.setBackgroundPlaybackAllowed(onlyAudio)
        onDispose { playback.setBackgroundPlaybackAllowed(false) }
    }

    var controller by remember { mutableStateOf<PlayerHandle?>(null) }
    DisposableEffect(playback) {
        val connection = playback.connect { controller = it }
        onDispose {
            // 离开直播间就停:直播没有"后台接着听"的场景 —— 它一直在往前走,回来时听到的
            // 也不是离开时那一段。**不 release 播放器**,它归服务所有。
            controller?.pause()
            connection.release()
            controller = null
        }
    }

    val active = controller
    val audioState by playback.state.collectAsStateWithLifecycle()

    // 开播了就交给服务。**命令报的是房间号和档位,不是地址** —— 取流归服务(见
    // AudioPlaybackService.resolveLiveStream),页面这一份只用来判断开没开播、能选哪几档。
    // 命令是幂等的:标题晚一步到只更新元数据,而档位或纯音频变了才重新取一次流。
    LaunchedEffect(active, state.isLive, state.currentQn, onlyAudio, state.title) {
        if (!state.isLive) return@LaunchedEffect
        val connected = active ?: return@LaunchedEffect
        connected.send(
            PlaybackCommand.OpenLive(
                roomId = roomId,
                qn = state.currentQn,
                onlyAudio = onlyAudio,
                title = state.title,
                upName = state.anchorName,
                coverUrl = state.coverUrl,
            ),
        )
    }

    // 播放器现在停在这个房间上。**判据是队列那一条,不是 loadKey**:后者要等取流成功才置上,
    // 而失败正是这里要认的情形。和视频页认自己那条失败用的是同一个判据。
    val thisRoom = audioState.queue?.current?.bvid == liveMediaId(roomId)
    val playbackError = rememberSettledPlaybackError(audioState.error?.takeIf { thisRoom })

    LiveRoomScreen(
        state = state,
        danmaku = vm.danmaku,
        player = active,
        // 播放器此刻装的是不是这个房间。和播放页同一个判据,只是标识换成了直播那一套。
        attached = audioState.loadKey == liveMediaId(roomId),
        danmakuPrefs = danmakuPrefs,
        danmakuEditor = danmakuEditor,
        onQualityChange = vm::setQuality,
        onlyAudio = onlyAudio,
        onOnlyAudioChange = { onlyAudio = it },
        onLoadMoreGuards = vm::loadMoreGuards,
        onSendDanmaku = vm::sendDanmaku,
        onRetry = vm::load,
        playbackError = playbackError,
        // 直播 MediaItem 化之后重取流就是标准那一条:重新解析、重新要一次地址,当前档位和
        // 纯音频开关随条目原样带过去。控制条的刷新和失败面板的重试都发这一条。
        //
        // **播放器还没切到这个房间时不发。** 打开命令是异步投递的,页面画出控制条到服务真的
        // 换过来之间有一小段窗口,那时这条命令重来的是上一条视频。
        onReloadStream = {
            if (thisRoom) active?.send(PlaybackCommand.Retry)
        },
        // 同上:别人的装载不该让这个房间的刷新按钮转圈。
        reloadingStream = audioState.loading && thisRoom,
        roomId = roomId,
        onBack = onBack,
        onUserClick = onUserClick,
        onToggleFollow = vm::toggleFollow,
        modifier = modifier,
    )
}
