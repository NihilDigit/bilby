package dev.bilby.api

import java.util.Base64

/**
 * `x-bili-aurora-eid`:把 mid 的十进制 ASCII 逐字节与固定 key 循环异或,再 base64 并去掉
 * 填充的 '='。原样搬自 PiliPlus `lib/utils/id_utils.dart:75-90` 的 `genAuroraEid`。
 *
 * 这是一个可逆的混淆,不是加密——它没有隐藏任何东西,B 站自己就要靠它还原 mid。
 * 单独成文件是为了能在 JVM 单测里直接跑:算错了服务端不会报错,只会在风控侧留下一个
 * 对不上号的设备,本地无从自检。
 */
object AuroraEid {

    private const val KEY = "ad1va46a7lza"

    fun of(mid: String): String {
        if (mid.isEmpty() || mid == "0") return ""
        val bytes = mid.toByteArray(Charsets.US_ASCII)
        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor KEY[i % KEY.length].code).toByte()
        }
        // java.util.Base64 而不是 android.util.Base64:后者在 JVM 单测里是空壳(返回 0),
        // 这段算法恰恰只能靠单测校验。minSdk 29 覆盖得到 java.util.Base64(API 26+)。
        return Base64.getEncoder().withoutPadding().encodeToString(bytes)
    }
}
