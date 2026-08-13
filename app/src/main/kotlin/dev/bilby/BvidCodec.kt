package dev.bilby

/**
 * av 号与 BV 号互转。**用 2023 年那版算法,不是 2020 年公布的那版** —— 后者只覆盖到 `1 << 27`
 * 的 aid,新号早就超了。两版对老号给出的结果相同(`av170001` 两边都是 `BV17x411w7KC`),
 * 所以换算法不会让旧链接失效。
 *
 * 做法:`aid` 或上 `1 << 51` 再异或一个常数,按 58 进制从低位往高位填进 `BV1` 后面的九位,
 * 最后交换两对位置(3↔9、4↔7)把连号打散。[toAid] 是同一条路倒着走。
 *
 * 九位一定填满:置位之后的值落在 `[2^51, 2^52)`,而异或的常数不到 `2^45`,动不了那一位;
 * 这个区间正好夹在 58^8 和 58^9 之间。所以转换是一一对应的,不会出现前导空位。
 *
 * 放在这里而不是 `api/`:它不是接口,是同一个编号的两种写法,离线可算。两个方向各有一个
 * 一次都不必联网的用处——[fromAid] 让 av 链接进得了以 bvid 为身份的路由,[toAid] 让播放
 * 服务发得出心跳(那个接口收 aid,而队列项身上只有 bvid,见 [dev.bilby.player.ProgressSession])。
 */
object BvidCodec {

    private const val TABLE = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
    private const val XOR = 23_442_827_791_579L
    private const val MAX_AID = 1L shl 51
    private const val BASE = 58

    /** BV 号的固定长度:`BV1` 加九位。 */
    private const val BVID_LENGTH = 12

    fun fromAid(aid: Long): String {
        val chars = "BV1000000000".toCharArray()
        var index = chars.lastIndex
        var remaining = (MAX_AID or aid) xor XOR
        while (remaining > 0 && index >= 3) {
            chars[index] = TABLE[(remaining % BASE).toInt()]
            remaining /= BASE
            index--
        }
        chars.swap(3, 9)
        chars.swap(4, 7)
        return String(chars)
    }

    /** 认不出来的串给 0。调用方据此放弃,不要拿一个编出来的号去发请求。 */
    fun toAid(bvid: String): Long {
        if (bvid.length != BVID_LENGTH || !bvid.startsWith("BV1")) return 0
        val chars = bvid.toCharArray()
        // 两对交换互不相交,而且各自是自己的逆,所以倒着走就是再交换一次。
        chars.swap(3, 9)
        chars.swap(4, 7)
        var value = 0L
        for (index in 3..chars.lastIndex) {
            val digit = TABLE.indexOf(chars[index])
            if (digit < 0) return 0
            value = value * BASE + digit
        }
        return (value xor XOR) and (MAX_AID - 1)
    }

    private fun CharArray.swap(a: Int, b: Int) {
        val tmp = this[a]
        this[a] = this[b]
        this[b] = tmp
    }
}
