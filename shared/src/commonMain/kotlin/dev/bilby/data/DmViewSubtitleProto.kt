package dev.bilby.data

import dev.bilby.api.dto.SubtitleTrackDto
import dev.bilby.danmaku.DanmakuProtoReader
import java.io.ByteArrayOutputStream

/**
 * `bilibili.community.service.dm.v1.DM/DmView` 的最小 protobuf 编解码
 * (见 `notes/subtitle-api.md` §2,字段号核自 PiliPlus `lib/grpc/bilibili/community/service/dm/v1.pb.dart`)。
 *
 * 与弹幕模块同样手写解析并复用 [DanmakuProtoReader],不引入反射式 protobuf 运行时,
 * 保持零额外 R8/ProGuard keep 规则。
 */
internal fun encodeDmViewRequest(aid: Long, cid: Long, type: Int = DM_VIEW_TYPE_VIDEO): ByteArray {
    val out = ByteArrayOutputStream(24)
    if (aid > 0L) writeVarintField(out, DM_VIEW_REQ_FIELD_PID, aid)
    if (cid > 0L) writeVarintField(out, DM_VIEW_REQ_FIELD_OID, cid)
    if (type != 0) writeVarintField(out, DM_VIEW_REQ_FIELD_TYPE, type.toLong())
    return out.toByteArray()
}

/**
 * 从 `DmViewReply` 提取字幕轨清单。
 *
 * `DmViewReply.subtitle`(tag 3, `VideoSubtitle`) -> `subtitles`(tag 3, repeated `SubtitleItem`):
 * - tag 3: `lan`
 * - tag 4: `lan_doc`
 * - tag 5: `subtitle_url`
 * - tag 7: `type` (`0 = CC`, `1 = AI`)
 */
internal fun parseDmViewSubtitles(bytes: ByteArray): List<SubtitleTrackDto> {
    if (bytes.isEmpty()) return emptyList()
    val reader = DanmakuProtoReader(bytes)
    val tracks = mutableListOf<SubtitleTrackDto>()
    while (reader.hasNext()) {
        val (field, wireType) = reader.readTag()
        if (field == DM_VIEW_REPLY_FIELD_SUBTITLE && wireType == DanmakuProtoReader.WIRE_LEN) {
            parseVideoSubtitle(reader.readMessage(), tracks)
        } else {
            reader.skip(wireType)
        }
    }
    return tracks
}

private fun parseVideoSubtitle(reader: DanmakuProtoReader, out: MutableList<SubtitleTrackDto>) {
    while (reader.hasNext()) {
        val (field, wireType) = reader.readTag()
        if (field == VIDEO_SUBTITLE_FIELD_SUBTITLES && wireType == DanmakuProtoReader.WIRE_LEN) {
            parseSubtitleItem(reader.readMessage())?.let(out::add)
        } else {
            reader.skip(wireType)
        }
    }
}

private fun parseSubtitleItem(reader: DanmakuProtoReader): SubtitleTrackDto? {
    var lan = ""
    var lanDoc = ""
    var subtitleUrl = ""
    var type = 0
    while (reader.hasNext()) {
        val (field, wireType) = reader.readTag()
        when {
            field == SUBTITLE_ITEM_FIELD_LAN && wireType == DanmakuProtoReader.WIRE_LEN ->
                lan = reader.readString()
            field == SUBTITLE_ITEM_FIELD_LAN_DOC && wireType == DanmakuProtoReader.WIRE_LEN ->
                lanDoc = reader.readString()
            field == SUBTITLE_ITEM_FIELD_URL && wireType == DanmakuProtoReader.WIRE_LEN ->
                subtitleUrl = reader.readString()
            field == SUBTITLE_ITEM_FIELD_TYPE && wireType == DanmakuProtoReader.WIRE_VARINT ->
                type = reader.readVarint().toInt()
            else -> reader.skip(wireType)
        }
    }
    if (lan.isBlank() || subtitleUrl.isBlank()) return null
    return SubtitleTrackDto(
        lan = lan,
        lanDoc = lanDoc,
        type = type,
        subtitleUrl = subtitleUrl,
    )
}

private fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
    writeVarint(out, ((fieldNumber shl 3) or DanmakuProtoReader.WIRE_VARINT).toLong())
    writeVarint(out, value)
}

private fun writeVarint(out: ByteArrayOutputStream, raw: Long) {
    var v = raw
    while ((v and 0x7FL.inv()) != 0L) {
        out.write(((v and 0x7FL) or 0x80L).toInt())
        v = v ushr 7
    }
    out.write(v.toInt())
}

private const val DM_VIEW_TYPE_VIDEO = 1
private const val DM_VIEW_REQ_FIELD_PID = 1
private const val DM_VIEW_REQ_FIELD_OID = 2
private const val DM_VIEW_REQ_FIELD_TYPE = 3
private const val DM_VIEW_REPLY_FIELD_SUBTITLE = 3
private const val VIDEO_SUBTITLE_FIELD_SUBTITLES = 3
private const val SUBTITLE_ITEM_FIELD_LAN = 3
private const val SUBTITLE_ITEM_FIELD_LAN_DOC = 4
private const val SUBTITLE_ITEM_FIELD_URL = 5
private const val SUBTITLE_ITEM_FIELD_TYPE = 7
