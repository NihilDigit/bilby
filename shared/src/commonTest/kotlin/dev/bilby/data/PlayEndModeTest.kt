package dev.bilby.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayEndModeTest {

    @Test
    fun `三态循环顺序为 播完暂停 到 连播 到 洗脑循环 再回 播完暂停`() {
        assertEquals(PlayEndMode.Continue, PlayEndMode.Pause.next())
        assertEquals(PlayEndMode.RepeatOne, PlayEndMode.Continue.next())
        assertEquals(PlayEndMode.Pause, PlayEndMode.RepeatOne.next())
    }

    @Test
    fun `旧版 playback_auto_next 无缝迁移且新键优先`() {
        assertEquals(PlayEndMode.Continue, PlayEndMode.fromPrefs(modeKey = null, legacyAutoNext = null))
        assertEquals(PlayEndMode.Continue, PlayEndMode.fromPrefs(modeKey = null, legacyAutoNext = true))
        assertEquals(PlayEndMode.Pause, PlayEndMode.fromPrefs(modeKey = null, legacyAutoNext = false))
        assertEquals(PlayEndMode.Pause, PlayEndMode.fromPrefs(modeKey = "unknown", legacyAutoNext = false))
        assertEquals(PlayEndMode.RepeatOne, PlayEndMode.fromPrefs(modeKey = "repeat_one", legacyAutoNext = false))
        assertEquals(PlayEndMode.Pause, PlayEndMode.fromPrefs(modeKey = "pause", legacyAutoNext = true))
    }

    @Test
    fun `PlaybackPrefs autoNext 只在 Continue 下为真`() {
        assertTrue(PlaybackPrefs(endMode = PlayEndMode.Continue).autoNext)
        assertFalse(PlaybackPrefs(endMode = PlayEndMode.Pause).autoNext)
        assertFalse(PlaybackPrefs(endMode = PlayEndMode.RepeatOne).autoNext)
    }

    @Test
    fun `队列顺序三态循环为 正序 到 倒序 到 随机 再回 正序`() {
        assertEquals(QueueOrder.Reverse, QueueOrder.Forward.next())
        assertEquals(QueueOrder.Shuffle, QueueOrder.Reverse.next())
        assertEquals(QueueOrder.Forward, QueueOrder.Shuffle.next())
    }

    @Test
    fun `旧版 playback_shuffled 无缝迁移且新键优先`() {
        assertEquals(QueueOrder.Forward, QueueOrder.fromPrefs(orderKey = null, legacyShuffled = null))
        assertEquals(QueueOrder.Forward, QueueOrder.fromPrefs(orderKey = null, legacyShuffled = false))
        assertEquals(QueueOrder.Shuffle, QueueOrder.fromPrefs(orderKey = null, legacyShuffled = true))
        assertEquals(QueueOrder.Shuffle, QueueOrder.fromPrefs(orderKey = "unknown", legacyShuffled = true))
        assertEquals(QueueOrder.Reverse, QueueOrder.fromPrefs(orderKey = "reverse", legacyShuffled = true))
        assertEquals(QueueOrder.Forward, QueueOrder.fromPrefs(orderKey = "forward", legacyShuffled = true))
    }

    @Test
    fun `PlaybackPrefs shuffled 只在 Shuffle 下为真`() {
        assertFalse(PlaybackPrefs(order = QueueOrder.Forward).shuffled)
        assertFalse(PlaybackPrefs(order = QueueOrder.Reverse).shuffled)
        assertTrue(PlaybackPrefs(order = QueueOrder.Shuffle).shuffled)
    }

    @Test
    fun `默认直播画质按是否计费网络切换`() {
        val defaults = PlayerPrefs()
        assertEquals(SettingsStore.DEFAULT_LIVE_QUALITY, defaults.defaultLiveQualityOn(metered = false))
        assertEquals(SettingsStore.DEFAULT_LIVE_QUALITY_METERED, defaults.defaultLiveQualityOn(metered = true))

        val customized = PlayerPrefs(defaultLiveQualityWifi = 400, defaultLiveQualityMetered = 150)
        assertEquals(400, customized.defaultLiveQualityOn(metered = false))
        assertEquals(150, customized.defaultLiveQualityOn(metered = true))
    }
}
