package dev.bilby.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSearchTest {

    @Test
    fun `空白查询放行所有条目`() {
        assertTrue(matchesLibraryQuery("", "视频标题", "UP主", "BV1xx411c7mD"))
        assertTrue(matchesLibraryQuery("   \t  ", "视频标题", "UP主", "BV1xx411c7mD"))
    }

    @Test
    fun `单关键词大小写不敏感匹配任意字段`() {
        assertTrue(matchesLibraryQuery("bv1xx", "测试视频", "作者", "BV1XX411c7mD"))
        assertTrue(matchesLibraryQuery("compose", "Kotlin Compose 教程", "开发者", "BV1aa"))
        assertTrue(matchesLibraryQuery("分P二", "合集视频", "分P二", "作者", "BV1bb"))
        assertFalse(matchesLibraryQuery("rust", "Kotlin Compose 教程", "开发者", "BV1aa"))
    }

    @Test
    fun `多词段跨字段同时命中才返回真`() {
        assertTrue(matchesLibraryQuery("开发者 compose", "Kotlin Compose 教程", "开发者", "BV1aa"))
        assertFalse(matchesLibraryQuery("开发者 rust", "Kotlin Compose 教程", "开发者", "BV1aa"))
    }
}
