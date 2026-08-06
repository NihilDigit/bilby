package dev.bilby.api

import kotlin.random.Random

/**
 * `dm_img_list` / `dm_img_str` / `dm_cover_img_str` / `dm_img_inter` 这一组风控埋点参数。
 * 网页端真实客户端往这里塞的是 WebGL 与 canvas 指纹,我们没有也不需要真造一份 —— 服务端
 * 只看有没有、格式对不对。
 *
 * 生成方式照抄 PiliPlus `lib/utils/utils.dart:48-60`:先取 N 个落在 [0x26, 0x7E] 的随机字节
 * (注释写明"dm_img_str 不能有 `%`"),base64 之后**去掉末尾两个字符**。
 *
 * 两处细节此前各自跑偏过,都值得写下来:
 *
 * - 直接从 base64 字母表里随机抽字符看着一样,但抽出来的长度不受 4 的倍数约束,不是一段
 *   合法的 base64;服务端一旦真去解码就露馅。
 * - 空间那几个接口原先用的是两个写死的常量。固定值意味着这台设备每次请求都交出同一个
 *   "canvas 指纹",而真实浏览器不会 —— 恒定值本身就是可以被匹配的特征。
 */
object DmImgParams {

    /** 补进 WBI 接口参数表的四个键。每次调用都重新生成,不缓存。 */
    fun next(): Map<String, String> = mapOf(
        "dm_img_list" to "[]",
        "dm_img_str" to base64OfRandomBytes(16, 64),
        "dm_cover_img_str" to base64OfRandomBytes(32, 128),
        "dm_img_inter" to """{"ds":[],"wh":[0,0,0],"of":[0,0,0]}""",
    )

    private fun base64OfRandomBytes(minLength: Int, maxLength: Int): String {
        val bytes = ByteArray(Random.nextInt(minLength, maxLength + 1)) {
            (0x26 + Random.nextInt(0x59)).toByte()
        }
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        return encoded.dropLast(2)
    }
}
