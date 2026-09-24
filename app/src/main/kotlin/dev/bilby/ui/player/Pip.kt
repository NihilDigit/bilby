package dev.bilby.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.media3.common.Player
import dev.bilby.BiliLog

/**
 * 画中画。做法照 android-docs-mirror 的 `develop/ui/compose/system/pip-*.md`:manifest 声明
 * `supportsPictureInPicture`,按钮调 `enterPictureInPictureMode`,界面按 [rememberIsInPipMode]
 * 只留画面。
 *
 * **小窗上的播放、暂停不自己写。** 文档原话:"If you are using a MediaSession, there will be
 * default actions added to the window",播放器本来就挂在 session 上。
 *
 * **只从按钮进,不在按 Home 时自动进。** 自动进(`setAutoEnterEnabled`)意味着每次离开应用都
 * 留下一个浮窗,而看视频退到后台原本是暂停的(见 MainActivity.onStop);那是两种不同的约定,
 * 要不要换约定是另一件事。
 */

/** 这台设备能不能画中画。有的系统(部分低内存机、一些 TV)不支持,按钮就不给。 */
fun Context.supportsPip(): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/**
 * 进画中画。[aspect] 是画面的宽高比,未知时交给系统(默认 16:9 上下)。
 *
 * **比例要夹进 1:2.39 到 2.39:1。** 超出这个范围 `setAspectRatio` 直接抛异常(文档的 Warning),
 * 超宽的电影流和极窄的竖屏流都可能越界。
 */
fun Context.enterPip(aspect: Float?) {
    val activity = findActivity() ?: return
    val builder = PictureInPictureParams.Builder()
    aspect?.takeIf { it > 0f }?.let { ratio ->
        val clamped = ratio.coerceIn(MinAspect, MaxAspect)
        builder.setAspectRatio(Rational((clamped * AspectScale).toInt(), AspectScale))
    }
    runCatching { activity.enterPictureInPictureMode(builder.build()) }
        .onFailure { BiliLog.w("进入画中画失败", it) }
}

/**
 * 画面此刻的宽高比,还没量到时是 null。按像素宽高比校正过:部分流的像素不是正方形,只看
 * 宽高会把小窗开成错的形状。
 */
fun Player?.videoAspect(): Float? =
    this?.videoSize?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { it.width * it.pixelWidthHeightRatio / it.height }

/** 窗口此刻在不在画中画里,随进出变化。 */
@Composable
fun rememberIsInPipMode(): Boolean {
    val activity = LocalContext.current.findActivity() as? ComponentActivity ?: return false
    var inPip by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val observer = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity.addOnPictureInPictureModeChangedListener(observer)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(observer) }
    }
    return inPip
}

private const val MaxAspect = 2.39f
private const val MinAspect = 1f / 2.39f

/** 把浮点比例化成整数分子分母的倍数。千分之一的误差看不出来。 */
private const val AspectScale = 1000
