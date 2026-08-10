package dev.bilby.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 定时怎么停。**三选一,不是两个正交开关。**
 *
 * 原先时长和"播完当前"是两个独立的旗子,宣称四种组合。但两个都设的那一格在行为上从来
 * 不存在:[onItemFinished] 只看旗子、不看定时到没到,所以当前这条先播完就当场停(那 N 分钟
 * 从未生效),定时先到则清掉时长继续等这条播完 —— 两条路都归到"播完这条就停"。四个状态
 * 只有三种行为,而多出来的那一格还让倒计时没法诚实显示:它到底是分钟数还是本条剩余,
 * 取决于哪个先到,而"哪个先到"要到那一刻才知道。
 *
 * 三选一之后每个模式各有一个显然的倒计时,文案也不用再拼(见 ListenScreen 的 sleepTimerLabel)。
 * 代价是"最迟 30 分钟,但别把正在放的切断"这个组合没有了 —— 它是旧注释描述的用法,
 * 而代码从来没有实现过它。
 */
sealed interface SleepTimerMode {
    /** 不定时。 */
    data object Off : SleepTimerMode

    /** 固定时长后停。 */
    data class After(val minutes: Int) : SleepTimerMode

    /** 当前这条播完就停,不看时长。短视频想"听完这条就睡"走的是这一格。 */
    data object EndOfItem : SleepTimerMode
}

data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.Off,
    /** 只有 [SleepTimerMode.After] 有值,UI 拿它倒计时。 */
    val remainingMillis: Long? = null,
)

/**
 * 定时关闭。助眠场景真正的风险是睡着后放一整晚(DESIGN 2.4b),所以这个功能不是可选项。
 *
 * **到点只暂停,不停服务、不关进程**:用户半夜醒来按一下耳机线控就该接着播,服务被杀掉
 * 之后那一下什么都不会发生。
 *
 * 计时用 [SystemClock.elapsedRealtime] 而不是 `System.currentTimeMillis`:后者会被系统
 * 对时和用户改时间挪动,睡前设的 30 分钟可能当场到点。深度 doze 下协程的 delay 会被推迟,
 * 倒计时显示可能停顿——但有音频在放时设备不会进深度 doze,这里不额外上 AlarmManager。
 */
class SleepTimer(
    private val scope: CoroutineScope,
    /** 到点时调用,实现里就是 player.pause()。 */
    private val onFire: () -> Unit,
) {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var countdown: Job? = null

    /** 设定定时。[SleepTimerMode.Off] 等价于 [cancel]。 */
    fun start(mode: SleepTimerMode) {
        countdown?.cancel()
        countdown = null
        when (mode) {
            SleepTimerMode.Off -> _state.value = SleepTimerState()
            SleepTimerMode.EndOfItem -> _state.value = SleepTimerState(SleepTimerMode.EndOfItem)
            is SleepTimerMode.After -> {
                val deadline = SystemClock.elapsedRealtime() + mode.minutes * 60_000L
                _state.value = SleepTimerState(mode, mode.minutes * 60_000L)
                countdown = scope.launch {
                    while (isActive) {
                        val remaining = deadline - SystemClock.elapsedRealtime()
                        if (remaining <= 0) break
                        _state.value = _state.value.copy(remainingMillis = remaining)
                        // 秒级刷新够 UI 用;对不齐整秒无所谓,倒计时不是秒表。
                        delay(1_000)
                    }
                    fire()
                }
            }
        }
    }

    fun cancel() {
        countdown?.cancel()
        countdown = null
        _state.value = SleepTimerState()
    }

    /**
     * 播放器报告当前这条播完了。返回 true 表示"该睡了",调用方**不要**再切下一条。
     * 由服务来问而不是这里监听播放器,是为了让 SleepTimer 不依赖 Player。
     *
     * 只有 [SleepTimerMode.EndOfItem] 才拦:定时模式到点由 [start] 里那个协程自己停,
     * 与这条播到哪儿无关。
     */
    fun onItemFinished(): Boolean {
        if (_state.value.mode != SleepTimerMode.EndOfItem) return false
        fire()
        return true
    }

    private fun fire() {
        countdown?.cancel()
        countdown = null
        _state.value = SleepTimerState()
        onFire()
    }
}
