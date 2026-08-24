package dev.bilby.data

import dev.bilby.data.model.RichLinkIcon
import dev.bilby.data.model.RichSpan

/**
 * 评论正文的解析:一个扁平的 `message` 字符串加几张边表,变成 [RichSpan] 序列。
 *
 * **专栏和动态不走这里。** 那两处的接口直接给结构化节点,映射一遍就够(见
 * `ArticleContentParser`、`DynamicCardMapper`);只有评论的 REST 接口把富文本压成了一个
 * 字符串,要靠这一遍扫描还原。三处的差别到这里为止,再往后共用
 * `ui/components/BiliRichText.kt`。
 *
 * 这一整段原先长在 `ui/comment/CommentSection.kt` 里,边扫边拼 `AnnotatedString`,于是
 * "怎么认"和"画成什么样"焊在一起,想单测认得对不对就得起一个 Compose 环境。
 */

/**
 * 正文里要特殊处理的四种东西:表情占位符 `[doge]`、@提及、跳转链接、时间点。
 * 一次扫描全认出来 —— 分成两遍就得处理"第二遍的匹配落在第一遍的替换里"这种交叉。
 *
 * 表情键的长度设了上限:`[` 到 `]` 之间不限长的话,一句"[这里省略一万字]看看"会被整段
 * 当成一个表情键去查表(查不到,原样显示,但白扫一遍)。B 站的表情名都很短。
 *
 * **时间点的冒号半角全角都认。** 中文输入法默认打出来的是全角「:」,而这条正则原先只认
 * 半角 —— 表现是相当一部分"12：34"根本不可点,和作者没写时间点完全同形。
 */
internal val RichTokenRegex = Regex(
    """\[[^\[\]]{1,20}]|@[^\s@]+|https?://\S+|(?<!\d)(?:(\d{1,2})[:：])?(\d{1,2})[:：](\d{2})(?!\d)""",
)

/**
 * 带上这条评论自己的链接 key 之后的扫描器。没有链接时直接复用 [RichTokenRegex],
 * 不为每条评论重新编译一个正则 —— 评论列表滚动时这是热路径,而绝大多数评论没有链接。
 *
 * **key 按长度倒序排在最前面。** 一条评论里 `av2` 和 `av2333` 可以同时出现,正则的交替是
 * 最左最先匹配,短的排在前面就会把长的咬掉一截,剩下 `333` 落在正文里。PiliPlus 拼这个正则
 * 时按 map 原序 join(`reply_item_grpc.dart` 的 `_buildMessage`),没有排序,这一点不照抄。
 *
 * 链接 key 排在 [RichTokenRegex] 之前还有第二个作用:一条整链接(`https://...`)同时命中
 * 这里的 key 和那边的 URL 分支,先匹配到 key 才能拿到标题。
 */
internal fun richTokenRegex(links: Map<String, CommentLink>): Regex {
    if (links.isEmpty()) return RichTokenRegex
    val keys = links.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    return Regex("$keys|${RichTokenRegex.pattern}")
}

/**
 * 一个 @ 能点开:[mid] 是去处,[length] 是这个 token 里属于名字的那一截。
 * 后者存在是因为正则按空白切,"@张三,你看" 会整串落进一个 token,而只有 "@张三" 是人。
 */
internal data class MentionLink(val mid: Long, val length: Int)

/**
 * 把正文里的 @ 和接口给的 [CommentMention] 对上,返回「token 起点 -> 去处」。
 *
 * **不能只按名字匹配**:members 里的 uname 是此刻的昵称,正文留的是发帖当时的昵称,
 * 抽样 11 条有 5 条对不上(全是 "回复 @旧名 :" 这种楼中楼)。所以分两轮:
 *
 * 一、名字能对上的先认领,长名字优先 —— 否则昵称 "abc" 会抢走 "@abcd" 这个 token。
 * 二、剩下的人按出现顺序配剩下的 token,**且只在两边数量相等时才配**。数量不等意味着
 *    正文里有 @ 不属于任何一个人(邮箱、"@一下"),这时按顺序配会把链接接到别人身上,
 *    而一个指向错误用户的链接比不可点更糟。
 */
internal fun resolveMentions(
    tokens: List<MatchResult>,
    mentions: List<CommentMention>,
): Map<Int, MentionLink> {
    if (mentions.isEmpty()) return emptyMap()
    val atTokens = tokens.filter { it.value.startsWith('@') }
    if (atTokens.isEmpty()) return emptyMap()

    val links = mutableMapOf<Int, MentionLink>()
    val unnamed = mutableListOf<CommentMention>()
    for (mention in mentions.sortedByDescending { it.uname.length }) {
        val needle = "@${mention.uname}"
        val hit = atTokens.firstOrNull { it.range.first !in links && it.value.startsWith(needle) }
        if (hit != null) links[hit.range.first] = MentionLink(mention.mid, needle.length) else unnamed += mention
    }

    val free = atTokens.filter { it.range.first !in links }
    if (unnamed.size == free.size) {
        free.forEachIndexed { index, token ->
            links[token.range.first] = MentionLink(unnamed[index].mid, token.value.length)
        }
    }
    return links
}

/**
 * 一条评论正文解析成富文本。
 *
 * 分支的**顺序就是优先级**,不能重排:
 *
 * - 站内链接排最前。一条 `https://...` 同时是 [links] 的 key 和裸 URL 那一支,而只有这一支
 *   拿得到标题 —— 落到裸 URL 分支就又是一串地址。
 * - 表情排在时间点前面。表情键长得像 `[1:23]` 的极少但存在,而它在表里查得到就是表情。
 * - 认不出去处的 @ **只染色不成链接**:见 [resolveMentions],不是所有 @ 都能确定指向谁,
 *   而一个指向错误用户的链接比不可点更糟。染色用一段没有 mid 的 [RichSpan.Mention] 表达。
 *
 * @param emotes 表情占位符到图片地址。
 * @param links 服务端标出来的站内链接,key 是正文里的那一段字面。
 * @param mentions 这条评论 @ 到的人。
 */
internal fun parseCommentSpans(
    message: String,
    emotes: Map<String, String>,
    links: Map<String, CommentLink> = emptyMap(),
    mentions: List<CommentMention> = emptyList(),
): List<RichSpan> {
    val tokens = richTokenRegex(links).findAll(message).toList()
    if (tokens.isEmpty()) return listOf(RichSpan.Text(message))
    val mentionLinks = resolveMentions(tokens, mentions)

    val spans = mutableListOf<RichSpan>()
    fun addText(text: String) {
        if (text.isNotEmpty()) spans += RichSpan.Text(text)
    }

    var last = 0
    for (match in tokens) {
        addText(message.substring(last, match.range.first))
        val token = match.value
        val siteLink = links[token]
        val emoteUrl = emotes[token]
        val mentionLink = mentionLinks[match.range.first]
        val timestampMillis = if (emoteUrl == null) parseTimestampMillis(token) else null
        when {
            siteLink != null ->
                spans += RichSpan.Link(siteLink.title, siteLink.url, RichLinkIcon.Video)

            // 表情的 alt 用占位符原文:复制出去的应该是 `[doge]`,不是一个空洞。
            emoteUrl != null -> spans += RichSpan.Emoji(emoteUrl, token, EMOTE_SCALE)

            timestampMillis != null -> spans += RichSpan.Timestamp(token, timestampMillis)

            mentionLink != null -> {
                spans += RichSpan.Mention(token.take(mentionLink.length), mentionLink.mid)
                // 名字后面粘着的标点回归正文色:"@张三,你看" 里只有前半截是人。
                addText(token.drop(mentionLink.length))
            }

            // **服务端没标出来的裸链接照样能点。** `jump_url` 只收录它认得的那些,剩下的
            // 以前是染了色的死字 —— 一段蓝的、点不动的地址比黑的还难解释。显示的是地址原文
            // 而不是标题:这一支根本拿不到标题(拿得到的走上面 siteLink 那一支)。
            //
            // 去处交给调用方的 `onOpenLink`,那一层先按站内解析、认不出来才交给浏览器
            // (见 MainActivity 的 `openLink`)—— 于是漏标的 BV 号仍然落在应用里。
            token.isBareUrl() -> spans += RichSpan.Link(token, token)

            // 认不出去处的 @。mid 为 0 的 [RichSpan.Mention] 只染色不可点:不是所有 @ 都能
            // 确定指向谁,而一个指向错误用户的链接比不可点更糟。
            else -> spans += RichSpan.Mention(token, 0L)
        }
        last = match.range.last + 1
    }
    addText(message.substring(last))
    return spans
}

/**
 * `12:34` / `1:02:03` 换成毫秒;不是合法时间点时返回 null。半角与全角冒号都认。
 *
 * 分钟和秒都要在 60 以内 —— 不查的话 "1:99" 这种编号也会变成一个能点的时间点,
 * 跳过去落在一个和它无关的位置上。
 */
internal fun parseTimestampMillis(token: String): Long? {
    val normalized = token.replace('：', ':')
    val parts = normalized.split(':').mapNotNull { it.toLongOrNull() }
    if (parts.size != normalized.count { it == ':' } + 1) return null
    val seconds = when (parts.size) {
        2 -> {
            if (parts[1] >= 60) return null
            parts[0] * 60 + parts[1]
        }

        3 -> {
            if (parts[1] >= 60 || parts[2] >= 60) return null
            parts[0] * 3600 + parts[1] * 60 + parts[2]
        }

        else -> return null
    }
    return seconds * 1000
}

/**
 * 这个 token 是 [RichTokenRegex] 里裸 URL 那一支匹配到的。
 *
 * 判据只看协议头,不重新验一遍地址:这一串**已经**是那条正则匹配出来的,再写一条更严的规则
 * 只会和它对不齐 —— 两处判断迟早分家,而分家的表现是某些地址被认成 URL 却又不给链接。
 */
private fun String.isBareUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

/** 评论表情没有倍率字段,一律按一倍画。专栏那边的表情自带 scale。 */
private const val EMOTE_SCALE = 1f
