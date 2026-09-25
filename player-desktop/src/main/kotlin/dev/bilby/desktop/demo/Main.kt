package dev.bilby.desktop.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.bilby.desktop.player.DesktopPlayer
import dev.bilby.desktop.player.StreamSource
import dev.nihildigit.danmaku.Danmaku
import dev.nihildigit.danmaku.DanmakuClock
import dev.nihildigit.danmaku.DanmakuLayer
import dev.nihildigit.danmaku.DanmakuMode
import dev.nihildigit.danmaku.DanmakuOptions
import dev.nihildigit.danmaku.rememberDanmakuController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface

/**
 * 桌面播放链路的冒烟窗口:mpv 画面进 Compose,上面叠一层弹幕与状态行。
 *
 * `./gradlew :player-desktop:run -Pvideo=<url 或路径> [-Paudio=<url 或路径>]`
 */
fun main(args: Array<String>) {
    val source = parseSource(args)
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Bilby",
            state = rememberWindowState(width = 1280.dp, height = 760.dp),
        ) {
            PlayerDemo(source)
        }
    }
}

private fun parseSource(args: Array<String>): StreamSource {
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val video = requireNotNull(arg("--video")) { "usage: --video <url|path> [--audio <url|path>]" }
    return StreamSource(video = video, audio = arg("--audio"))
}

@Composable
private fun PlayerDemo(source: StreamSource) {
    val player = remember { DesktopPlayer(Dispatchers.Default) }
    DisposableEffect(player) { onDispose { player.close() } }
    LaunchedEffect(player, source) { player.open(source) }

    val clock = remember(player) {
        object : DanmakuClock {
            override val positionMillis: Long get() = player.positionMillis
            override val isPlaying: Boolean get() = player.mediamp.state.value.isPlaying
            override val playbackSpeed: Float get() = player.speed
        }
    }
    val controller = rememberDanmakuController(clock, DanmakuOptions(fontSizeSp = 18f), contentKey = source)
    LaunchedEffect(controller) { controller.setPool(syntheticDanmaku()) }
    // 帧循环空闲时挂起,播放状态变化与 seek 要主动唤醒它
    LaunchedEffect(player, controller) {
        merge(player.mediamp.state, player.mediamp.events).collect { controller.notifyChanged() }
    }

    var status by remember { mutableStateOf("") }
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            if (tick++ % 10 == 0 && status.isNotEmpty()) println("[demo] $status")
            val state = player.mediamp.state.value
            val mpv = player.mpv
            status = "hwdec ${mpv.getPropertyString("hwdec-current")}  " +
                "audio ${mpv.getPropertyString("audio-codec-name")}  " +
                "dropped ${mpv.getPropertyInt("frame-drop-count")}/${mpv.getPropertyInt("vo-delayed-frame-count")}  " +
                "${player.positionMillis / 1000}s  ${state.mediaStatus}"
            delay(500)
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun togglePause() = player.mpv.setPropertyBoolean("pause", !player.mpv.getPropertyBoolean("pause"))
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.Spacebar -> togglePause()
                    Key.DirectionRight -> player.mediamp.seekTo(player.positionMillis + 10_000)
                    Key.DirectionLeft -> player.mediamp.seekTo((player.positionMillis - 10_000).coerceAtLeast(0))
                    else -> return@onKeyEvent false
                }
                true
            }
            // 不用 clickable:它自带空格触发点击,和上面的空格键各切一次,暂停等于没按
            .pointerInput(player) { detectTapGestures { togglePause() } },
    ) {
        MpvMediampPlayerSurface(player.mediamp, Modifier.fillMaxSize())
        DanmakuLayer(controller, Modifier.fillMaxSize())
        BasicText(
            status,
            style = TextStyle(color = Color.White, fontSize = 13.sp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0x99000000))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** 每秒五条,十分钟,够把滚动与顶部两条排布路径都跑满。 */
private fun syntheticDanmaku(): List<Danmaku> = List(3000) { i ->
    Danmaku(
        id = i.toString(),
        playTimeMillis = i * 200L,
        mode = if (i % 10 == 0) DanmakuMode.TOP else DanmakuMode.SCROLL,
        color = 0xFFFFFF,
        text = "弹幕 $i",
    )
}
