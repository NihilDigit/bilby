package dev.bilby

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.bilby.data.db.BilbyDatabase
import dev.bilby.player.DesktopPlaybackHost
import dev.bilby.player.NetworkStatus
import dev.bilby.player.PlaybackHost
import dev.bilby.update.AppUpdateService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.io.File

/**
 * 桌面上的 [Platform]。数据放在用户目录下的一个文件夹里,Windows 上是 `%APPDATA%\Bilby`。
 *
 * @param container 播放服务要从容器取仓库,而容器由这个平台对象构造,只能延后求值。
 * @param updater 应用内更新。实现在 :desktop 里:它要读安装包启动器写的属性、在退出时拉起
 *   更新脚本,这些都是打包入口的事。
 */
class DesktopPlatform(
    container: () -> AppContainer,
    override val updater: AppUpdateService?,
) : Platform {

    private val dataDir: File = (System.getenv("APPDATA")?.let(::File) ?: File(System.getProperty("user.home")))
        .resolve(if (System.getenv("APPDATA") != null) "Bilby" else ".bilby")
        .apply { mkdirs() }

    override val filesDir: File get() = dataDir
    override val preferencesDir: File get() = File(dataDir, "datastore")
    override val offlineRoot: File get() = File(dataDir, "offline")

    /**
     * 桌面一律当作有网、不计费。读 Windows 的网络状态(计费连接、断网通知)要走系统接口,
     * 还没接;判错的代价只是多试一次请求,默认画质按不计费那一档取。
     */
    override val network: NetworkStatus = object : NetworkStatus {
        override fun hasInternet(): Boolean = true
        override fun internetAvailability(): Flow<Boolean> = flowOf(true)
        override fun isMetered(): Boolean = false
    }

    override val playback: PlaybackHost = DesktopPlaybackHost(container)

    override fun databaseBuilder(): RoomDatabase.Builder<BilbyDatabase> =
        Room.databaseBuilder<BilbyDatabase>(name = File(dataDir, "bilby.db").absolutePath)
            .setDriver(BundledSQLiteDriver())

    // 桌面进程不会因为在后台而被回收,下载不需要额外顶着。
    override fun onOfflineDownloadsBusy(busy: Boolean) = Unit

    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushContainer = container
    private var flushJob: Job? = null

    /**
     * 没有系统调度器可托付,补发交给进程内的一个协程:每隔一段时间试一次,发完为止。
     * 进程退出时没发完的留在表里,下次启动再排上时一起发。
     */
    override fun scheduleHeartbeatFlush() {
        if (flushJob?.isActive == true) return
        flushJob = flushScope.launch {
            while (!flushContainer().heartbeatReporter.flushPending()) {
                delay(HEARTBEAT_RETRY_MILLIS)
            }
        }
    }

    private companion object {
        const val HEARTBEAT_RETRY_MILLIS = 60_000L
    }
}
