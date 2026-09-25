package dev.bilby.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File

/**
 * 按名字在 [dir] 下开一个 Preferences DataStore,文件名是 `<name>.preferences_pb`。
 *
 * 命名规则照抄 Android 的 `preferencesDataStore(name)` 委托(`filesDir/datastore/<name>.preferences_pb`):
 * Android 端把 [dir] 设成 `filesDir/datastore`,升级前存下的凭据与设置原地可读。
 */
fun preferencesStore(dir: File, name: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(produceFile = { File(dir, "$name.preferences_pb") })
