package dev.bilby.ui.search

import androidx.navigation3.runtime.NavKey
import dev.bilby.BvidCodec
import dev.bilby.ui.BilbyLink
import dev.bilby.ui.Video

/**
 * 搜索框里是一个编号或一条链接时,回车直接去的那一页;不是就返回 null,照常搜。
 *
 * 认的是整段输入:`BV1…` 与 `av…`(前缀大小写不论,照 PiliPlus `utils/id_utils.dart`),以及
 * 含一条站内链接的文本(客户端分享出来的那一段带标题和尾巴,链接夹在中间)。短链要先展开,
 * 不在这里,见 SearchChatViewModel.search。
 *
 * 纯数字不在此列:它既可能是 UID,也可能就是想搜这串数字,由结果页顶上那一行让人自己选。
 */
internal fun directDestination(input: String): NavKey? {
    val text = input.trim()
    BvExact.matchEntire(text)?.let { return Video("BV" + text.drop(2)) }
    AvExact.matchEntire(text)?.let { match ->
        return match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }?.let { Video(BvidCodec.fromAid(it)) }
    }
    val url = BilbyLink.extractUrl(text) ?: return null
    return BilbyLink.destinationOf(url)
}

/** 纯数字的输入,当作可能的 UID。 */
internal fun uidCandidate(query: String): Long? =
    query.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()?.takeIf { it > 0 }

private val BvExact = Regex("^bv1[0-9a-zA-Z]{9}$", RegexOption.IGNORE_CASE)
private val AvExact = Regex("^av(\\d+)$", RegexOption.IGNORE_CASE)
