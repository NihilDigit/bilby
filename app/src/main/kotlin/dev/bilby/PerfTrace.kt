package dev.bilby

/**
 * 性能测量入口。只做一件事:在单调时钟上记下"某条链路的某个节点何时发生",并在链路结束时
 * 把各段间隔一次性打出来。
 *
 * **为什么不用 BiliLog.d。** 那条路每记一个点就是一次 `Log.d` 系统调用,而这里要测的恰好是
 * 毫秒级的间隔——起播链路七八个节点、弹幕编译每段一次,逐点打印本身就进了被测量的区间。
 * 这里改成先攒进内存,链路 [end] 时汇总成一行。
 *
 * **为什么是 [System.nanoTime] 而不是 `currentTimeMillis`。** 后者会被 NTP 校时和用户改时间
 * 拽着走,测出负数间隔;前者是单调的,没有绝对时间含义,正好只用来做差。
 *
 * **默认只在 debug 生效**,与 [BiliLog.w] 那条"警告在 release 照打"的规矩相反:这里的量级
 * 是每帧、每段弹幕,留在 release 里会把真正要看的行淹掉。需要在 release 包上采一次数据时
 * 把 [enabled] 打开,它是 var 而不是 const,就是为了留这条口子。
 *
 * **不记任何凭据**:调用方只传节点名和条数、字节数这类计数,URL 一律走 [pathOnly] 之后的路径。
 */
object PerfTrace {

    /** 默认跟随构建类型。release 上采样时由调试入口临时置 true。 */
    var enabled: Boolean = BuildConfig.DEBUG

    /**
     * 一条链路。不是线程安全的:每条链路都由单一所有者驱动(起播链路归 service,弹幕链路归
     * 一次编译),跨线程共用一个 chain 说明链路划错了。
     */
    class Chain internal constructor(private val name: String) {
        private val startNanos = System.nanoTime()
        private var lastNanos = startNanos
        private val marks = StringBuilder()

        /**
         * 记一个节点。打出来的是"距上一个节点"和"距链路起点"两个数——只给总耗时找不出是哪
         * 一段慢,只给分段耗时对不上端到端的观感。
         */
        fun mark(event: String) {
            if (!enabled) return
            val now = System.nanoTime()
            marks.append(' ').append(event).append('=')
                .append((now - lastNanos) / NANOS_PER_MILLI).append("ms@")
                .append((now - startNanos) / NANOS_PER_MILLI)
            lastNanos = now
        }

        /** 附一个计数(条数、字节数、缓存命中数)。不参与计时,只是让那一行自解释。 */
        fun count(label: String, value: Long) {
            if (!enabled) return
            marks.append(' ').append(label).append('=').append(value)
        }

        fun end() {
            if (!enabled) return
            BiliLog.d("perf $name total=${(System.nanoTime() - startNanos) / NANOS_PER_MILLI}ms$marks")
        }
    }

    /** 开一条链路。[enabled] 为 false 时返回的 chain 每个方法都是空操作,调用方不必自己加判断。 */
    fun chain(name: String): Chain = Chain(name)

    /**
     * 测一段同步代码。返回值原样透出,所以可以直接把 `= block()` 包起来而不改调用形态。
     * inline + crossinline 之外不做别的封装:这个函数自己会出现在被测量的热路径里。
     */
    inline fun <T> span(name: String, block: () -> T): T {
        if (!enabled) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            BiliLog.d("perf $name=${(System.nanoTime() - start) / NANOS_PER_MILLI}ms")
        }
    }

    const val NANOS_PER_MILLI = 1_000_000L
}
