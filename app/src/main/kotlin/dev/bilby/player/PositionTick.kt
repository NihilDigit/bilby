package dev.bilby.player

/**
 * 服务发出的一次位置刻度。
 *
 * **播放位置的权威读数在服务这一侧**,因为播放器在这一侧。跨进程的 `MediaController` 给的是
 * 外推值:它拿"锚点位置 + 经过时间 × 倍速"现算(media3-session:1.10.1 的
 * `MediaUtils.getUpdatedCurrentPositionMs`),而 `setPlaybackSpeed()` 立刻 masking 新倍速却
 * 不刷新那个锚点,于是新倍速被追认到已经过去的那段时间上,读数跳过头,等 session 回包落地
 * 才纠正回来。长按倍速每次都经过这个窗口,表现是松手瞬间弹幕集体跳一下。controller 那一侧
 * 无解——锚点是它自己的私有状态,外部既读不到也刷不了。
 *
 * 所以刻度带上自己的锚点:[positionMillis] 是 [anchorMillis] 那一刻的真位置,消费方要更细的
 * 粒度就用 [positionAt] 自己外推,倍速一变服务立刻发新刻度,锚点跟着更新。
 *
 * [anchorMillis] 取 `SystemClock.elapsedRealtime()`:单调、不受用户改表影响、休眠期间照走。
 * 服务与界面同进程,两边读的是同一根。
 */
data class PositionTick(
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val anchorMillis: Long = 0,
) {

    /**
     * 外推到 [nowMillis] 时的位置。停着的时候不外推——那时位置就是锚点上那个数。
     *
     * 不与 [durationMillis] 取小:时长为 0(时间线还没到、或直播)时没有上界可言,而钳一个
     * 假的上界会让位置停在 0。多推出去几十毫秒的代价只是弹幕早一点点上屏。
     */
    fun positionAt(nowMillis: Long): Long {
        if (!isPlaying) return positionMillis
        val elapsed = (nowMillis - anchorMillis).coerceAtLeast(0)
        return positionMillis + (elapsed * speed).toLong()
    }
}
