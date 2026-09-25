package dev.bilby

import androidx.compose.runtime.Composable
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource

/**
 * 文案取值的唯一入口,替代 Compose 资源库自带的同名函数。
 *
 * 那一版只认 `%1$s` 与 `%1$d`,别的占位原样留在文案里:英文文案里有 `%1$.1f`(倍速、评分)
 * 和字面百分号 `%%`,换过去会直接显示成占位符。这里取原文后交给 [String.format],格式化规则
 * 与原先 Android 的 `getString(id, args)` 一致。没有参数时不格式化,文案里的 `%` 原样保留,
 * 这一点也与 Android 相同。
 */
@Composable
fun stringResource(resource: StringResource, vararg formatArgs: Any): String {
    val raw = org.jetbrains.compose.resources.stringResource(resource)
    return if (formatArgs.isEmpty()) raw else raw.format(*formatArgs)
}

/** 非组合上下文里取文案,规则同 [stringResource]。 */
suspend fun getString(resource: StringResource, vararg formatArgs: Any): String {
    val raw = org.jetbrains.compose.resources.getString(resource)
    return if (formatArgs.isEmpty()) raw else raw.format(*formatArgs)
}

/**
 * 给没有协程可挂的平台回调用(通知、前台服务的系统回调)。文案读自打包进应用的资源,
 * 不走网络,阻塞的时长是一次本地读取。
 */
fun getStringBlocking(resource: StringResource, vararg formatArgs: Any): String =
    runBlocking { getString(resource, *formatArgs) }
