package dev.bilby.danmaku

/**
 * `DanmakuElem` 里我们实际用到的字段,tag 号见 notes/danmaku.md §2/§6.3:实测确认过
 * progress/mode/fontsize/color/midHash/content 分别是 tag 2/3/4/5/6/7,`id` 是消息里
 * 声明顺序最靠前的字段,tag 1;`idStr`(渲染层要用的 id)延续同一套顺序编号,tag 12——
 * 服务端把不连续的 `count`(100)/`isSelf`(102) 两个客户端本地扩展字段专门标出来,恰恰
 * 说明前面 1~20 号字段都是按声明顺序连续编号的,不然没必要单独强调这两个是例外。
 */
internal data class RawDanmakuElem(
    val id: Long = 0,
    val idStr: String = "",
    val progressMillis: Int = 0,
    val mode: Int = 0,
    val fontSize: Int = 0,
    val color: Int = 0,
    val content: String = "",
)

/** 解析一条 `DanmakuElem` 消息体(已经是外层 length-delimited 切出来的那一段字节)。 */
internal fun parseDanmakuElem(bytes: ByteArray): RawDanmakuElem {
    val reader = DanmakuProtoReader(bytes)
    var id = 0L
    var idStr = ""
    var progress = 0
    var mode = 0
    var fontSize = 0
    var color = 0
    var content = ""

    while (reader.hasNext()) {
        val (field, wireType) = reader.readTag()
        when (field) {
            1 -> id = reader.readVarint()
            2 -> progress = reader.readVarint().toInt()
            3 -> mode = reader.readVarint().toInt()
            4 -> fontSize = reader.readVarint().toInt()
            5 -> color = reader.readVarint().toInt()
            7 -> content = reader.readString()
            12 -> idStr = reader.readString()
            // 不认识的字段(未来新增的、或者我们没用到的,比如 ctime/weight/likeCount 这些)
            // 一律按它自己的 wire type 跳过,不能假设它是我们认识的哪一种。
            else -> reader.skip(wireType)
        }
    }
    return RawDanmakuElem(id, idStr, progress, mode, fontSize, color, content)
}

/**
 * 解析一份 `DmSegMobileReply`,取出全部 `elems`(tag 1,repeated `DanmakuElem`,LEN 编码)。
 * `state`/`aiFlag`/`segmentRules`/`colorfulSrc`/`contextSrc` 这些字段(tag 2~6)当前业务
 * 用不到,统统按 wire type 跳过。
 */
internal fun parseDmSegMobileReply(bytes: ByteArray): List<RawDanmakuElem> {
    val reader = DanmakuProtoReader(bytes)
    val elems = mutableListOf<RawDanmakuElem>()
    while (reader.hasNext()) {
        val (field, wireType) = reader.readTag()
        if (field == 1 && wireType == DanmakuProtoReader.WIRE_LEN) {
            elems.add(parseDanmakuElem(reader.readLengthDelimited()))
        } else {
            reader.skip(wireType)
        }
    }
    return elems
}
