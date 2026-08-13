package dev.bilby.player

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 直播项和视频项住在同一份 playlist 里,取流走哪条接口只由条目自己回答
 * (`AudioPlaybackService.resolveStream` 按 [isLive] 分派)。分派认错的表现是直播去问 playurl
 * 或者视频去问直播接口,两种都只在真机上才看得见。
 *
 * 重来一遍时装载参数是否沿用同样在这里测:不沿用的话断一次流,直播自己变回视频、画质自己
 * 跳一档,而那两件事用户都没做过。
 *
 * 要 Robolectric 是因为参数存在 `Bundle` 里,JVM 上它是个空壳。
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveMediaItemTest {

    private val live = liveMediaItem(
        display = QueueItem(
            bvid = liveMediaId(ROOM_ID),
            title = "标题",
            upName = "主播",
            coverUrl = "https://example/cover.jpg",
            durationSeconds = 0,
        ),
        roomId = ROOM_ID,
        qn = 400,
        onlyAudio = true,
        loadNonce = 7,
    )

    private val video = QueueItem(
        bvid = "BV1xx411c7mD",
        title = "标题",
        upName = "UP",
        coverUrl = "",
        durationSeconds = 100,
    ).toMediaItem(requestedCid = 42, loadNonce = 7)

    @Test
    fun `直播项带着取流要用的全部参数`() {
        assertTrue(live.isLive)
        assertEquals(ROOM_ID, live.liveRoomId)
        assertEquals(400, live.liveQn)
        assertTrue(live.liveOnlyAudio)
        assertEquals("live:$ROOM_ID", live.mediaId)
    }

    @Test
    fun `视频项不是直播`() {
        assertFalse(video.isLive)
        assertEquals(0L, video.liveRoomId)
        assertEquals(42L, video.cidHint)
    }

    @Test
    fun `重来一遍沿用档位与纯音频,只换 nonce`() {
        val again = live.withLoadParams(loadNonce = 8)

        assertEquals(400, again.liveQn)
        assertTrue(again.liveOnlyAudio)
        assertEquals(ROOM_ID, again.liveRoomId)
        assertEquals(8, again.loadNonce)
        assertEquals(live.mediaId, again.mediaId)
    }

    @Test
    fun `换档只换档,不把纯音频带回默认值`() {
        val again = live.withLoadParams(loadNonce = 8, liveQn = 10000)

        assertEquals(10000, again.liveQn)
        assertTrue(again.liveOnlyAudio)
    }

    @Test
    fun `视频重来一遍不会长出房间号`() {
        val again = video.withLoadParams(loadNonce = 8, requestedCid = 43)

        assertFalse(again.isLive)
        assertEquals(43L, again.cidHint)
    }

    private companion object {
        const val ROOM_ID = 1234L
    }
}
