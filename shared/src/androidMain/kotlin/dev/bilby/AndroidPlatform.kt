package dev.bilby

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.bilby.data.db.BilbyDatabase
import dev.bilby.offline.HeartbeatFlushWorker
import dev.bilby.offline.OfflineDownloadService
import dev.bilby.player.AndroidNetworkStatus
import dev.bilby.player.AndroidPlaybackHost
import dev.bilby.player.NetworkStatus
import dev.bilby.player.PlaybackHost
import dev.bilby.update.AndroidAppUpdater
import dev.bilby.update.AppUpdateService
import java.io.File

class AndroidPlatform(context: Context) : Platform {

    private val appContext = context.applicationContext

    override val filesDir: File get() = appContext.filesDir

    /** 与 `preferencesDataStore(name)` 委托的位置相同,升级前的凭据原地可读。 */
    override val preferencesDir: File get() = File(appContext.filesDir, "datastore")

    /**
     * 缓存文件放外部私有目录:不要权限、随卸载清除、不进媒体库,而空间通常比内部存储宽裕得多
     * —— 视频是这个应用里唯一的大件。拿不到(没有外置卷)时回落内部存储。
     */
    override val offlineRoot: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "offline")

    override val network: NetworkStatus = AndroidNetworkStatus(appContext)

    override val playback: PlaybackHost = AndroidPlaybackHost(appContext)

    override fun databaseBuilder(): RoomDatabase.Builder<BilbyDatabase> =
        Room.databaseBuilder<BilbyDatabase>(appContext, appContext.getDatabasePath("bilby.db").absolutePath)

    override fun onOfflineDownloadsBusy(busy: Boolean) = OfflineDownloadService.setRunning(appContext, busy)

    override fun scheduleHeartbeatFlush() = HeartbeatFlushWorker.enqueue(appContext)

    override val updater: AppUpdateService = AndroidAppUpdater(appContext)
}
