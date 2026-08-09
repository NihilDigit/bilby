package dev.bilby.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset

/**
 * 动效的唯一来源。
 *
 * **分工是规范自己划的**,不是我们拟的。easing-and-duration 页顶上那条注写着:
 *
 * > In the expressive update, components and motion now use the motion physics system, which uses
 * > springs. Products should migrate to the new system. **The easing and duration system is still
 * > used for transitions** and can be used by teams that haven't yet updated to GM3 Expressive.
 *
 * 于是:
 * - **转场**(整屏级:压栈、返回、切根目的地)用这里的缓动 + 时长;
 * - **组件动效**(控件显隐、提示浮层)用 `MaterialTheme.motionScheme` 的 spring。
 *
 * 两边不要互串。spring 取不到 duration,拿它做转场就没法表达"退出比进入短";而组件用 tween
 * 会失去 expressive 那套物理感,规范明确要求组件迁过去。
 */
object Motion {

    /**
     * 缓动。数值逐条抄自 easing-and-duration 的 token 表(Android 那一列的 `PathInterpolator`
     * 参数),没有改动。
     */
    object Easing {
        /** `md.sys.motion.easing.emphasized.decelerate` —— 进入用。 */
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        /** `md.sys.motion.easing.emphasized.accelerate` —— 退出用。 */
        val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

        /** `md.sys.motion.easing.standard` —— 简单、小幅、功能性的那些。 */
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        /** `md.sys.motion.easing.standard.decelerate`。 */
        val StandardDecelerate = CubicBezierEasing(0f, 0f, 0f, 1f)

        /** `md.sys.motion.easing.standard.accelerate`。 */
        val StandardAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    }

    /**
     * 时长。token 名与数值同样照抄。**挑哪一档由两件事决定**,规范写得很直白:
     * "Transitions that cover small areas of the screen have short durations. Those that traverse
     * large areas have long durations",以及 "Transitions that exit, dismiss, or collapse an element
     * use shorter durations"。
     */
    object Duration {
        /** `md.sys.motion.duration.short3`。 */
        const val Short3 = 150

        /** `md.sys.motion.duration.medium1`。 */
        const val Medium1 = 250

        /** `md.sys.motion.duration.medium4`。 */
        const val Medium4 = 400
    }

    /**
     * Forward and backward(压栈 / 返回)。
     *
     * 形态取 **Android 平台默认**,规范原文:"**Android** uses a fade as screens slide. This reduces
     * the amount of motion, since the screens don't have to slide the full width of the device.";
     * 选型那一节又补了一句 "Both Android and iOS should use platform defaults for forward and
     * backward navigation."
     *
     * 所以是**滑动 + 淡入淡出**,而且两页都只走一小段(五分之一屏),不是让一页走满整屏 ——
     * 走满是 lateral 的做法,规范明确说别拿 lateral 做层级导航("Sliding content the full width
     * of the screen is excessive for a high frequency transition")。
     *
     * 时长取 medium4(400ms):它覆盖的是"a medium area of the screen"。进入配 emphasized
     * decelerate、退出配 emphasized accelerate,这也是那张表给的成对用法。
     */
    val ForwardEnterSlide: FiniteAnimationSpec<IntOffset> =
        tween(Duration.Medium4, easing = Easing.EmphasizedDecelerate)
    val ForwardExitSlide: FiniteAnimationSpec<IntOffset> =
        tween(Duration.Medium4, easing = Easing.EmphasizedAccelerate)
    val ForwardEnterFade: FiniteAnimationSpec<Float> =
        tween(Duration.Medium4, easing = Easing.EmphasizedDecelerate)
    val ForwardExitFade: FiniteAnimationSpec<Float> =
        tween(Duration.Medium4, easing = Easing.EmphasizedAccelerate)

    /** 滑动幅度:屏宽的五分之一。"don't have to slide the full width" 落到一个具体的数。 */
    const val ForwardSlideFraction = 5

    /**
     * Top level(点底栏 / rail 换根目的地)。
     *
     * 规范:"The exiting screen **quickly fades out and then** the entering screen fades in. Since
     * the content of top level destinations isn't necessarily related, the motion intentionally
     * does not use grouping or persistent elements";applying-transitions 的 clean fades 又要求
     * "Fully fade out content before fading new content in."
     *
     * 所以进入那一档要**延后一个退出时长**,不是两条同时跑。退出 short3、进入 medium1,
     * 合起来 400ms 以内 —— 规范管这叫 "a quick fade"。
     */
    val TopLevelExitFade: FiniteAnimationSpec<Float> =
        tween(Duration.Short3, easing = Easing.StandardAccelerate)
    val TopLevelEnterFade: FiniteAnimationSpec<Float> =
        tween(Duration.Medium1, delayMillis = Duration.Short3, easing = Easing.StandardDecelerate)
}

/**
 * 系统的"减弱动效"开着没有。
 *
 * 规范把它列为"好的转场"第一条:"Most platforms have a reduced animation setting... If that setting
 * is on, transitions should **use subtle fades instead of intense sliding or scaling animations**
 * and disable decorative effects"。
 *
 * Android 上它表现为动画缩放被调到 0(开发者选项里的三个缩放,或无障碍的"移除动画")。
 * 读 `TRANSITION_ANIMATION_SCALE`:它管的正是窗口/页面级转场,和这里要退化的东西对得上。
 *
 * **组件那一侧不用自己处理。** Compose 的动画跑在 `AndroidUiDispatcher` 的
 * `MotionDurationScale` 下,那个值读的就是系统的动画时长缩放,关掉动画时所有 spring 自动
 * 变成瞬时。这里补的是另一个设置项,两者可以分别为 0。
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    // 进程存活期间当它不变:这个开关在系统设置里,改完基本都会重进应用;为它挂一个
    // ContentObserver,换来的是每次重组都要读一次 Settings。
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}
