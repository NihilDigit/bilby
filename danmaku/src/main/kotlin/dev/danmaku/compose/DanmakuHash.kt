package dev.danmaku.compose

/**
 * FNV-1a,32 位。系统里唯一的"随机源"全部经过这个函数,不用 [String.hashCode] —— 后者在 JVM
 * 上是规范保证的,但 Kotlin 多平台的 `String.hashCode()` 没有跨平台一致性的规范承诺,而这个模块
 * 迟早要搬到 commonMain 给 JVM/Native 共用同一份编译结果,哈希值本身必须与平台无关。
 */
internal fun fnv1a(text: String): Int {
    var hash = -0x7ee3623b // 2166136261 的补码表示 (FNV offset basis)
    for (ch in text) {
        hash = hash xor ch.code
        hash *= 0x01000193 // FNV prime
    }
    return hash
}

/** 把 [fnv1a] 的输出映射到 `[0, 1)`,作为确定性的"随机数"。 */
internal fun unitHash(id: String): Float =
    ((fnv1a(id).toLong() and 0xFFFFFFFFL).toDouble() / 4_294_967_296.0).toFloat()
