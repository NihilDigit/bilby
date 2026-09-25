package dev.bilby.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * [AppLanguage] 在 Android 上存在哪里、怎么生效。
 *
 * - **Android 13+ 走系统的应用语言**(`LocaleManager.applicationLocales`)。选择由系统存,
 *   系统设置「应用语言」里改的和这里改的是同一个值;改完系统自己重建 Activity。可选的几种在
 *   `res/xml/locales_config.xml` 里登记,系统设置那一页据此列出。
 * - **Android 10–12 没有这套接口**,由应用自己存(一个 SharedPreferences 键),在
 *   MainActivity.attachBaseContext 里给 Context 套上对应的 Locale,改完手动 recreate。
 *   不存 DataStore:attachBaseContext 在一切之前同步执行,DataStore 只能异步读。
 *
 * 只作用于界面。通知、播放服务的文案走 Application 的 Context,在 12 及以下仍是系统语言 ——
 * 那几处都是一两个词,为它在 Application 和 Service 上各套一层不值。
 */
object AppLanguageStore {
    /** 现在生效的那一项。 */
    fun current(context: Context): AppLanguage {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales
                .takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
        } else {
            storedTag(context)
        }
        return AppLanguage.fromTag(tag)
    }

    fun apply(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        } else {
            prefs(activity).edit { putString(KEY_TAG, language.tag) }
            activity.recreate()
        }
    }

    /** 12 及以下给 Activity 的 Context 套上存下的语言;13+ 与跟随系统时原样返回。 */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = storedTag(base) ?: return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(config)
    }

    private fun storedTag(context: Context): String? = prefs(context).getString(KEY_TAG, null)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "app_language"
    private const val KEY_TAG = "tag"
}
