package dev.bilby.ui.components

import androidx.compose.ui.platform.ClipEntry

/** 一段纯文本的剪贴板条目。[label] 只有 Android 用得上(系统复制预览里显示)。 */
expect fun plainTextClip(label: String, text: String): ClipEntry

/**
 * 要不要自己报一句「已复制」。
 *
 * **Android 13(API 33)起系统自己会弹一个复制预览浮层**,再报一条就是同一件事说两遍。
 * 官方文档把这条单列为 "Avoid duplicate notifications",同一节又要求 12L(API 32)及以下
 * 由应用自己给反馈 —— 见 `android-docs-mirror/pages/develop/ui/compose/touch-input/copy-and-paste.md`
 * 的 "Feedback to copying content"。
 *
 * **不能省成"一律不报"**:本项目 `minSdk = 29`,29–32 那一段真的在支持范围里,
 * 那些机器上不报就是按下之后什么都没发生。桌面系统从不提示,一律自己报。
 */
expect val NeedsCopyNotice: Boolean
