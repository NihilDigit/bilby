package dev.bilby.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 清晰度就近匹配。批量缓存时用户只选一档,而十条视频各有各的档位集合 —— 这条规则每一条都要过,
 * 错了不会报错,只会安静地挑错画质,等看的时候才发现。
 *
 * 档位表见 notes/playurl.md §4.2:16=360P、32=480P、64=720P、74=720P60、80=1080P、120=4K。
 * 这些 id 是严格可比的数字,所以整条规则就是"取 <= 里最高的,一个都没有就往上取最低的"。
 */
class ResolveQualityTest {

    private val typical = listOf(16, 32, 64, 80, 120)

    @Test
    fun `所选档存在就取它`() {
        assertEquals(64, resolveQuality(typical, preferred = 64))
    }

    @Test
    fun `所选档不存在时降到不超过它的最高一档`() {
        // 74(720P60)这条视频没有,退到 64(720P),不能跳到 80。
        assertEquals(64, resolveQuality(typical, preferred = 74))
    }

    @Test
    fun `比最高档还高时取最高档`() {
        // 用户选 8K,这条视频最高只有 4K —— 120 <= 127,落在主规则里,不算回退。
        assertEquals(120, resolveQuality(typical, preferred = 127))
    }

    @Test
    fun `所有档位都高于所选档时升到最低可行的那一档`() {
        // 这是回退唯一会发生的情形:用户选 360P,而这条片源最低就是 720P。只能往上,
        // 而最低的那一档既是离他要的最近的,也是最省的。
        assertEquals(64, resolveQuality(listOf(64, 80, 120), preferred = 16))
    }

    @Test
    fun `只有一档时无论选什么都是它`() {
        assertEquals(80, resolveQuality(listOf(80), preferred = 16))
        assertEquals(80, resolveQuality(listOf(80), preferred = 80))
        assertEquals(80, resolveQuality(listOf(80), preferred = 120))
    }

    @Test
    fun `一档都没有时返回 null`() {
        // 编一个档位出来会让调用方拿着它继续往下走,最后在"找不到这一档的流"处失败,
        // 而那个位置离原因很远。空就是空,调用方按取流失败处理。
        assertNull(resolveQuality(emptyList(), preferred = 80))
    }

    @Test
    fun `档位表乱序也照大小判,不看下标`() {
        // 服务端一般按从高到低给,但这条规则不该依赖顺序 —— 依赖了就会在某一天默默换答案。
        assertEquals(64, resolveQuality(listOf(120, 16, 80, 64, 32), preferred = 74))
        assertEquals(64, resolveQuality(listOf(120, 80, 64), preferred = 16))
    }
}
