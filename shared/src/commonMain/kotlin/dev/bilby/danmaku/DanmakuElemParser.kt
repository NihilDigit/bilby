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
    val pool: Int = 0,
    val attr: Int = 0,
    val dmFrom: Int = 0,
)

/**
 * 是否为互动指令/投票衍生弹幕(notes/danmaku.md §11.2)。
 *
 * 观众点击官方互动投票/评分面板时,服务端会同时往 `seg.so` 里塞一条内容等于选项文案的弹幕:
 * - `dmFrom`(tag 27)= 2(`DmFromCmd`)
 * - `pool`(tag 11)= 3(指令弹幕池)
 * - `attr`(tag 13)第 19 位(`0x80000`)置位
 *
 * 这类弹幕由独立的投票面板承载,不进普通滚动弹幕池。
 */
internal fun RawDanmakuElem.isCommandDanmaku(): Boolean =
    dmFrom == DM_FROM_CMD || pool == POOL_CMD || (attr and ATTR_BIT_CMD) != 0

private const val DM_FROM_CMD = 2
private const val POOL_CMD = 3
private const val ATTR_BIT_CMD = 1 shl 19

/** 解析一条 `DanmakuElem` 消息体(已经是外层 length-delimited 切出来的那一段字节)。 */
internal fun parseDanmakuElem(bytes: ByteArray): RawDanmakuElem =
    parseDanmakuElem(DanmakuProtoReader(bytes))

/**
 * 解析一条 `DanmakuElem`。入参是限定在这条子消息区间内的 reader,不是切出来的字节副本——
 * 子消息的边界由 reader 自己的 `end` 保证,见 [DanmakuProtoReader.readMessage]。
 */
internal fun parseDanmakuElem(reader: DanmakuProtoReader): RawDanmakuElem {
    var id = 0L
    var idStr = ""
    var progress = 0
    var mode = 0
    var fontSize = 0
    var color = 0
    var content = ""
    var pool = 0
    var attr = 0
    var dmFrom = 0

    while (reader.hasNext()) {
        val (field, wireType) = reader.readTag()
        when (field) {
            1 -> id = reader.readVarint()
            2 -> progress = reader.readVarint().toInt()
            3 -> mode = reader.readVarint().toInt()
            4 -> fontSize = reader.readVarint().toInt()
            5 -> color = reader.readVarint().toInt()
            7 -> content = reader.readString()
            11 -> pool = reader.readVarint().toInt()
            12 -> idStr = reader.readString()
            13 -> attr = reader.readVarint().toInt()
            27 -> dmFrom = reader.readVarint().toInt()
            // 不认识的字段(未来新增的、或者我们没用到的,比如 ctime/weight/likeCount 这些)
            // 一律按它自己的 wire type 跳过,不能假设它是我们认识的哪一种。
            else -> reader.skip(wireType)
        }
    }
    return RawDanmakuElem(id, idStr, progress, mode, fontSize, color, content, pool, attr, dmFrom)
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
            elems.add(parseDanmakuElem(reader.readMessage()))
        } else {
            reader.skip(wireType)
        }
    }
    return elems
}
