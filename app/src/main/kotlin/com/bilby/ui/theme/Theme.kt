package com.bilby.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * M3 Expressive 已并入主线:没有独立的 MaterialExpressiveTheme 入口(它在当前版本是
 * internal),MaterialTheme 本身即是。
 */
@Composable
fun BilbyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme) {
        // MaterialTheme 只给配色方案,不设 LocalContentColor —— 它的默认值是纯黑,
        // 真正把内容色设成 onSurface/onBackground 的是 Surface。没有这层的话,
        // 任何没被 Scaffold 包住的界面都会在深色背景上写黑字。
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
