package dev.bilby.data

/** `&#39;` 与 `&#x27;` 两种写法。B 站的标题里撇号就是以后者出现的。 */
private val NumericEntity = Regex("&#([xX][0-9a-fA-F]+|[0-9]+);")

/**
 * 把 HTML 实体还原成字符。
 *
 * B 站有几条接口交回来的正文是转义过的,而且**同一条内容在不同接口上不一样**:
 * 未登录的 `x/v2/reply/main` 给的是原样的 `'`,登录态的 `x/v2/reply` 给的是 `&#39;`
 * (av280814244 的 "u know what I&#39;m saying" 两边都抓过)。所以解码放在读取那一侧统一做,
 * 不按接口分。
 *
 * `&amp;` 必须最后解码,否则 `&amp;lt;` 这种双重转义会被错误地还原成 `<`;数字字符引用同理
 * 排在它前面 —— 双重转义过的 `&amp;#x27;` 里没有 `&#` 相邻,不会被这一步误伤。
 */
fun String.decodeHtmlEntities(): String = replace(NumericEntity) { match ->
    val body = match.groupValues[1]
    val code = if (body[0] == 'x' || body[0] == 'X') {
        body.drop(1).toIntOrNull(16)
    } else {
        body.toIntOrNull()
    }
    // 码点非法(超出 Unicode 范围之类)时原样留着,总好过抛异常把整条内容搞没。
    code?.takeIf { it in 0..0x10FFFF }?.let { String(Character.toChars(it)) } ?: match.value
}
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
