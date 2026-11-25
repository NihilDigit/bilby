package dev.bilby.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockMatcherTest {

    @Test
    fun `匹配器按 UP 主 mid 与大小写不敏感关键词过滤视频与用户`() {
        val rules = listOf(
            ContentBlockRule(ContentBlockRule.Kind.Up, "42", "某营销号"),
            ContentBlockRule(ContentBlockRule.Kind.Keyword, "震惊", "震惊"),
            ContentBlockRule(ContentBlockRule.Kind.Keyword, "clickbait", "ClickBait"),
        )
        val matcher = ContentBlockMatcher.fromRules(rules)

        assertTrue(matcher.matchesVideo(upMid = 42L, upName = "某营销号", title = "正常标题"))
        assertTrue(matcher.matchesVideo(upMid = 99L, upName = "路人", title = "太震惊了！必看"))
        assertTrue(matcher.matchesVideo(upMid = 99L, upName = "ClickBait Studio", title = "普通视频"))
        assertFalse(matcher.matchesVideo(upMid = 99L, upName = "科普君", title = "Compose 源码解析"))
        assertTrue(matcher.matchesUser(mid = 42L, name = "改名后的号"))
        assertTrue(matcher.matchesUser(mid = 77L, name = "震惊部"))
        assertFalse(matcher.matchesUser(mid = 77L, name = "科普君"))
    }

    @Test
    fun `空规则不命中任何条目`() {
        val matcher = ContentBlockMatcher.fromRules(emptyList())
        assertTrue(matcher.isEmpty)
        assertFalse(matcher.matchesVideo(upMid = 1L, upName = "任意", title = "任意标题"))
        assertFalse(matcher.matchesUser(mid = 1L, name = "任意"))
    }
}
