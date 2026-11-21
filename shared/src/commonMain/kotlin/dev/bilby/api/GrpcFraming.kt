package dev.bilby.api

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * B 站 app 端 gRPC-over-HTTP 的 5 字节帧头编解码(见 `notes/subtitle-api.md` §2,
 * 对照 PiliPlus `lib/grpc/grpc_req.dart:23-39`)。
 *
 * 帧格式:`[compressed: 1B (0=裸字节, 1=gzip)][length: 4B 大端无符号整数][payload: length 字节]`。
 * 请求体很小(如 `DmViewReq` 仅十余字节),发送时不压缩(`compressed = 0`);响应体服务端常回 `1`(gzip)。
 */
internal fun frameGrpcMessage(proto: ByteArray): ByteArray {
    val len = proto.size
    val out = ByteArray(GRPC_FRAME_HEADER_SIZE + len)
    out[0] = 0
    out[1] = ((len ushr 24) and 0xFF).toByte()
    out[2] = ((len ushr 16) and 0xFF).toByte()
    out[3] = ((len ushr 8) and 0xFF).toByte()
    out[4] = (len and 0xFF).toByte()
    proto.copyInto(out, destinationOffset = GRPC_FRAME_HEADER_SIZE)
    return out
}

internal fun unframeGrpcMessage(framed: ByteArray): ByteArray {
    check(framed.size >= GRPC_FRAME_HEADER_SIZE) {
        "gRPC 响应不足 5 字节帧头(actual=${framed.size})"
    }
    val compressed = framed[0].toInt() and 0xFF
    check(compressed == 0 || compressed == 1) {
        "gRPC 压缩标记非法($compressed)"
    }
    val length = ((framed[1].toInt() and 0xFF) shl 24) or
        ((framed[2].toInt() and 0xFF) shl 16) or
        ((framed[3].toInt() and 0xFF) shl 8) or
        (framed[4].toInt() and 0xFF)
    check(length >= 0 && GRPC_FRAME_HEADER_SIZE.toLong() + length.toLong() <= framed.size.toLong()) {
        "gRPC 帧长度越界(length=$length, total=${framed.size})"
    }
    val payload = framed.copyOfRange(GRPC_FRAME_HEADER_SIZE, GRPC_FRAME_HEADER_SIZE + length)
    return if (compressed == 1) {
        GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
    } else {
        payload
    }
}

private const val GRPC_FRAME_HEADER_SIZE = 5
