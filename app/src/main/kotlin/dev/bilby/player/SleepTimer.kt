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

sealed interface SleepTimerMode {
    /** 没设时长。 */
    data object Off : SleepTimerMode

    /** 固定时长后。 */
    data class After(val minutes: Int) : SleepTimerMode
}

/**
 * 时长([mode])和"播完当前视频再停"([finishCurrentItem])是两个正交的开关,不是三选一 ——
 * 只勾开关不设时长就是"播完这条就停",两个都设就是"最迟 N 分钟,但如果那时正好在放就等它放完"。
 */
data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.Off,
    /** 只有 [SleepTimerMode.After] 有值,UI 拿它倒计时。 */
    val remainingMillis: Long? = null,
    val finishCurrentItem: Boolean = false,
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

    /**
     * 设定定时。[minutes] 为 null 表示不设时长;[finishCurrentItem] 独立于时长,四种组合
     * 都由这一个方法表达,调用方(Slider 与 Switch)各自只管自己那一半,互不覆盖。
     */
    fun start(minutes: Int?, finishCurrentItem: Boolean) {
        countdown?.cancel()
        countdown = null
        if (minutes == null) {
            _state.value = SleepTimerState(SleepTimerMode.Off, null, finishCurrentItem)
            return
        }
        val deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        _state.value = SleepTimerState(SleepTimerMode.After(minutes), minutes * 60_000L, finishCurrentItem)
        countdown = scope.launch {
            while (isActive) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                _state.value = _state.value.copy(remainingMillis = remaining)
                // 秒级刷新够 UI 用;对不齐整秒无所谓,倒计时不是秒表。
                delay(1_000)
            }
            onDeadline()
        }
    }

    fun cancel() {
        countdown?.cancel()
        countdown = null
        _state.value = SleepTimerState()
    }

    /** 时长到点。要等这条播完的话不能现在就停——把时长部分清掉,旗子留给 [onItemFinished] 去问。 */
    private fun onDeadline() {
        if (_state.value.finishCurrentItem) {
            _state.value = _state.value.copy(mode = SleepTimerMode.Off, remainingMillis = null)
        } else {
            fire()
        }
    }

    /**
     * 播放器报告当前这条播完了。返回 true 表示"该睡了",调用方**不要**再切下一条。
     * 由服务来问而不是这里监听播放器,是为了让 SleepTimer 不依赖 Player。
     *
     * 只看 [SleepTimerState.finishCurrentItem] 这一个旗子:不管它是用户直接勾的
     * (没设时长,等价于旧版 EndOfItem),还是时长到点后转过来的,处理都一样。
     */
    fun onItemFinished(): Boolean {
        if (!_state.value.finishCurrentItem) return false
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
