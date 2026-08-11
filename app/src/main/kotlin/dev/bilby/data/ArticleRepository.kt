package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.ArticleViewDto
import dev.bilby.api.dto.OpusDetailDto
import dev.bilby.api.getData
import dev.bilby.api.toHttpsUrl
import dev.bilby.data.model.Article

/**
 * 专栏正文(DESIGN 2.4 之外的一块:动态里点开一篇专栏应该能就地读完,而不是被踢去浏览器)。
 *
 * **两条接口,不是一条**,接口事实见 notes/article.md:
 *
 * - 新版 `opus`:`GET /x/polymer/web-dynamic/v1/opus/detail`,**WBI 签名**,登录 Cookie。
 * - 旧版 `read/cv`:`GET /x/article/view`,**同样 WBI 签名**。
 *
 * 两者不能互相代替:opus id 与 cv 号是两套编号。opus 接口自己会在这个 id 其实是旧专栏时
 * 回一个 `fallback.id`(cv 号),这时改走 read 那条 —— 这是服务端指路,不是我们猜的。
 */
class ArticleRepository(private val client: BiliClient) {

    /**
     * @param isRead true = [id] 是 cv 号(旧版专栏),false = opus id。这两种 id 长得一样
     *   (都是一串数字),分不出来,所以由调用方带着它是从哪条链接/哪张卡片来的。
     */
    suspend fun loadArticle(id: String, isRead: Boolean): BiliResult<Article> =
        if (isRead) loadRead(id) else loadOpus(id)

    private suspend fun loadOpus(opusId: String): BiliResult<Article> {
        val result = client.getData<OpusDetailDto>(
            OPUS_DETAIL_URL,
            mapOf(
                "id" to opusId,
                "timezone_offset" to "-480",
                // 与动态流那条的 features 不是同一组:这里要的是"正文按新版结构给"。
                "features" to "htmlNewStyle",
            ),
            signed = true,
        )
        return when (result) {
            is BiliResult.Ok -> {
                val fallbackCvId = result.value.fallback?.id?.takeIf { it.isNotBlank() }
                if (fallbackCvId != null) {
                    BiliLog.d("opus $opusId 实为旧版专栏,改走 cv$fallbackCvId")
                    loadRead(fallbackCvId)
                } else {
                    result.value.toArticle(opusId)?.let { BiliResult.Ok(it) }
                        ?: emptyContent(OPUS_DETAIL_URL)
                }
            }

            is BiliResult.ApiError -> result
            is BiliResult.Failure -> result
        }
    }

    private suspend fun loadRead(cvId: String): BiliResult<Article> {
        val result = client.getData<ArticleViewDto>(
            ARTICLE_VIEW_URL,
            mapOf(
                "id" to cvId,
                "gaia_source" to "main_web",
                "web_location" to "333.976",
            ),
            signed = true,
        )
        return when (result) {
            is BiliResult.Ok -> result.value.toArticle(cvId)?.let { BiliResult.Ok(it) }
                ?: emptyContent(ARTICLE_VIEW_URL)

            is BiliResult.ApiError -> result
            is BiliResult.Failure -> result
        }
    }

    /**
     * 接口成功但正文一个块都没解析出来。**这不是空文章**,是我们不认得它的形状(最常见的是
     * 只给了一段旧版 HTML `content`),所以要留下一行日志,否则界面上只是一片空白。
     */
    private fun emptyContent(url: String): BiliResult<Article> {
        BiliLog.w("GET ${url.substringAfterLast('/')} 正文解析为空,可能是旧版 HTML 正文")
        return BiliResult.ApiError(CODE_UNREADABLE, "这篇专栏的正文暂时读不出来")
    }

    private fun OpusDetailDto.toArticle(opusId: String): Article? {
        val item = item ?: return null
        val title = item.modules.firstNotNullOfOrNull { it.moduleTitle?.text }.orEmpty()
        val author = item.modules.firstNotNullOfOrNull { it.moduleAuthor }
        val blocked = item.modules.firstNotNullOfOrNull { it.moduleBlocked }
        val blocks = item.modules
            .firstNotNullOfOrNull { it.moduleContent }
            ?.paragraphs
            ?.toArticleBlocks()
            .orEmpty()
        if (blocks.isEmpty() && blocked == null) return null
        return Article(
            id = opusId,
            title = title,
            authorName = author?.name.orEmpty(),
            authorFaceUrl = author?.face.orEmpty().toHttpsUrl(),
            authorMid = author?.mid ?: 0L,
            publishedAtEpochSeconds = author?.pubTs ?: 0L,
            blocks = blocks,
            webUrl = "${BiliConstants.MAIN_HOST}/opus/$opusId",
            blockedMessage = blocked?.let {
                listOf(it.title, it.hintMessage).filter(String::isNotBlank).joinToString("\n")
                    .ifBlank { null }
            },
        )
    }

    private fun ArticleViewDto.toArticle(cvId: String): Article? {
        // 只认段落数组那条路。旧版专栏还有一种只给 HTML 字符串的形态,那要一整套 HTML 渲染
        // 才画得出来;识别不了时由 emptyContent 报出来,界面上给"用浏览器打开"。
        val blocks = opus?.content?.paragraphs.orEmpty().toArticleBlocks()
        if (blocks.isEmpty()) return null
        return Article(
            id = cvId,
            title = title,
            authorName = author?.name.orEmpty(),
            authorFaceUrl = author?.face.orEmpty().toHttpsUrl(),
            authorMid = author?.mid ?: 0L,
            publishedAtEpochSeconds = publishTime,
            blocks = blocks,
            webUrl = "${BiliConstants.MAIN_HOST}/read/cv$cvId",
        )
    }

    private companion object {
        const val OPUS_DETAIL_URL = "${BiliConstants.WEB_HOST}/x/polymer/web-dynamic/v1/opus/detail"
        const val ARTICLE_VIEW_URL = "${BiliConstants.WEB_HOST}/x/article/view"

        /** 本地判定,不是 B 站的错误码 —— 负数区间和接口错误码不会撞。 */
        const val CODE_UNREADABLE = -1
    }
}
