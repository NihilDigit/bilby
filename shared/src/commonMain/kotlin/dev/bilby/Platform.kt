package dev.bilby

import androidx.room.RoomDatabase
import dev.bilby.data.db.BilbyDatabase
import dev.bilby.player.NetworkStatus
import dev.bilby.player.PlaybackHost
import dev.bilby.update.AppUpdateService
import java.io.File

/**
 * [AppContainer] 向平台要的东西:文件放哪、网络怎样、后台工作交给谁。
 *
 * 只收容器装配时用得到的服务。界面层的平台差异(窗口、画中画、系统分享)不经这里,
 * 在各自的 expect 声明旁边。
 */
interface Platform {
    /** 应用私有的数据目录。放待补发的心跳这类不属于缓存的记录。 */
    val filesDir: File

    /** DataStore 文件所在目录,见 [dev.bilby.data.preferencesStore]。 */
    val preferencesDir: File

    /** 离线缓存的根目录。视频是这个应用里唯一的大件,放在空间最宽裕的地方。 */
    val offlineRoot: File

    val network: NetworkStatus

    /** 唯一的播放服务。Android 上是前台的 MediaSessionService,桌面上是进程内的 mpv。 */
    val playback: PlaybackHost

    /** 数据库文件位置与 SQLite 驱动。其余配置在 [BilbyDatabase.create]。 */
    fun databaseBuilder(): RoomDatabase.Builder<BilbyDatabase>

    /**
     * 离线下载开始与结束。Android 上用前台服务顶住进程,免得下到一半被系统回收;
     * 桌面进程不会被回收,什么也不做。
     */
    fun onOfflineDownloadsBusy(busy: Boolean)

    /** 断网时没发出去的心跳,联网后补发。Android 交给 WorkManager,能跨过进程死亡。 */
    fun scheduleHeartbeatFlush()

    /**
     * 应用内更新。null 表示这个平台不做,设置页不出现检查更新,开屏也不查。
     * 挑哪个附件、怎么安装归各平台,见 [dev.bilby.update.GithubUpdateService]。
     */
    val updater: AppUpdateService?
}
