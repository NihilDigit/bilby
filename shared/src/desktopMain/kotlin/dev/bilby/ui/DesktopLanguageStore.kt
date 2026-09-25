package dev.bilby.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.bilby.BiliLog
import java.io.File
import java.util.Locale
import java.util.Properties

/**
 * 桌面的界面语言。选择存在一个 properties 文件里,在第一帧之前同步读出。
 *
 * 生效靠改 JVM 的默认 Locale:Compose 资源按 `Locale.current` 挑 `values` 还是 `values-en`,
 * 桌面上它读的就是默认 Locale;`String.format` 与日期格式也跟着变。不存 DataStore,理由同
 * Android 12 及以下那条:要在界面起来之前同步拿到。
 *
 * 默认 Locale 改了,已经组合出来的文案不会自己变。换语言时入口处把整棵界面拆掉重建一次
 * (见 desktop 的 Main.kt),[language] 就是给它观察的。
 */
class DesktopLanguageStore(private val file: File) {
    /** 启动时的系统语言。选回「跟随系统」时还原成它,不能再读 Locale.getDefault(),那时已被改过。 */
    private val systemLocale: Locale = Locale.getDefault()

    var language: AppLanguage by mutableStateOf(load())
        private set

    init {
        applyLocale(language)
    }

    fun set(value: AppLanguage) {
        if (value == language) return
        save(value)
        applyLocale(value)
        language = value
    }

    private fun applyLocale(value: AppLanguage) {
        Locale.setDefault(value.tag?.let(Locale::forLanguageTag) ?: systemLocale)
    }

    private fun load(): AppLanguage {
        if (!file.isFile) return AppLanguage.System
        val props = Properties()
        return runCatching {
            file.inputStream().use(props::load)
            AppLanguage.fromTag(props.getProperty(KEY_TAG))
        }.onFailure { BiliLog.w("读界面语言失败 ${file.path}", it) }
            .getOrDefault(AppLanguage.System)
    }

    private fun save(value: AppLanguage) {
        val props = Properties()
        value.tag?.let { props.setProperty(KEY_TAG, it) }
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, null) }
        }.onFailure { BiliLog.w("存界面语言失败 ${file.path}", it) }
    }

    private companion object {
        const val KEY_TAG = "tag"
    }
}
