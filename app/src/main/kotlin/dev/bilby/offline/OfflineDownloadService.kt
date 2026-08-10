package dev.bilby.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.bilby.BilbyApplication
import dev.bilby.BiliLog
import dev.bilby.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * 缓存进行时把进程钉在前台,并在通知栏显示正在下的是哪一条、下到几成。
 *
 * **它不下载任何东西。** 下载跑在 [OfflineDownloader] 里,而那个对象活在
 * [dev.bilby.AppContainer] 的 scope 上、和界面无关。这个服务的职责只有两条:让系统别在用户
 * 切走之后把进程回收掉,以及把下载器已有的状态照实画到通知栏上。
 *
 * 做成一个只观察不持有的壳,而不是把下载搬进服务里:搬进去的话,下载状态就绑在了服务的生死
 * 上,而服务是可以被系统随时停掉再拉起的。
 */
@OptIn(FlowPreview::class)
class OfflineDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val downloader = (application as BilbyApplication).container.offlineDownloader
        scope.launch {
            downloader.items
                .map { it.toNotificationProgress() }
                // **固定间隔采样,不在数据上做去重。**
                //
                // 下载器每写 1MB 发一次状态,而 NotificationManager 的上限是 5 次/秒,超出的
                // 直接丢弃 —— 真机 logcat 里是成片的 "Shedding notify (update) ...
                // rate limit (5.0) exceeded: input 42.5",表现是通知时有时无甚至根本不出现。
                //
                // 曾经想用"整数百分比变了没有"去挡,那是从数据上推频率:几百 MB 的任务百分比
                // 一秒也能跳好几次,多条并行时还会再叠上去,推出来的频率仍然不受控。直接压一道
                // 时间闸更直接,而且频率的上界与有几条在下无关。
                //
                // 一秒一次也正是人看通知要的东西:「还在动、大概到哪了」,不是精确到个位数。
                // `sample` 在上游没有新值时不发,所以空闲时不会有多余的 notify。
                .sample(NOTIFICATION_INTERVAL_MILLIS)
                .collect { progress ->
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(progress))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 三参重载从 API 29 起就有(minSdk 正是 29),Android 14 起还必须传类型且与 manifest
        // 里声明的一致,否则直接抛 MissingForegroundServiceTypeException。
        startForeground(
            NOTIFICATION_ID,
            buildNotification(NotificationProgress()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        // START_NOT_STICKY:被系统杀掉之后不要自动重启。真正的进度在 [OfflineDownloader]
        // 手上,空转一个前台通知只会骗人。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 通知栏上真正画出来的那几个字段,**只有这几个**。 */
    private data class NotificationProgress(
        val title: String = "",
        /** 0..100;-1 表示总长未知,画不确定态。 */
        val percent: Int = -1,
        /**
         * 还有几条没完(不含通知里画着的这一条)。多条一起缓存时,只看百分比会以为快好了。
         *
         * **并行下的那几条也算在里面。** 通知只画得下一条进度,而并发度调到 3 之后,另外两条
         * 正在下的既不在 Queued 里、也没画出来 —— 不算进来的话,三条一起下会显示成"还有 0 条"。
         */
        val queued: Int = 0,
    )

    private fun List<OfflineItem>.toNotificationProgress(): NotificationProgress {
        val running = firstOrNull { it.status == OfflineStatus.Running }
        val queued = count { it.status == OfflineStatus.Queued } +
            (count { it.status == OfflineStatus.Running } - 1).coerceAtLeast(0)
        if (running == null) return NotificationProgress(queued = queued)
        val fraction = running.progress
            ?: return NotificationProgress(title = running.title, queued = queued)
        return NotificationProgress(
            title = running.title,
            percent = (fraction * 100).toInt(),
            queued = queued,
        )
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.offline_notification_channel),
                // LOW:这条通知是"进程还活着"的凭证加一个进度读数,不是提醒,不该出声也不该
                // 弹横幅。
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(progress: NotificationProgress): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.offline_notification_title))
            // 正在下哪一条摆在第二行。没有(刚起来、或队列刚好空了)时不画这一行,
            // 而不是留一句"准备中"——那句话什么都没说。
            .setContentText(progress.title.takeIf { it.isNotEmpty() })
            // 还排着几条放 subText(通知右上角那一小行)。只画百分比的话,勾了十条的人会在
            // 第一条走到 99% 时以为马上就完了。
            .setSubText(
                progress.queued.takeIf { it > 0 }?.let { getString(R.string.offline_notification_queued, it) },
            )
            .setOngoing(true)
            .setProgress(100, progress.percent.coerceAtLeast(0), progress.percent < 0)
            .build()

    companion object {
        private const val CHANNEL_ID = "offline_download"
        private const val NOTIFICATION_ID = 4201

        /**
         * 通知多久更新一次。系统的上限是 5 次/秒(超出的直接丢),1 秒留了五倍余量,而人盯着
         * 通知看的是"还在动、大概到哪了",不是精确到个位数的百分比。
         */
        private const val NOTIFICATION_INTERVAL_MILLIS = 1_000L

        /**
         * 拉起/收掉。**调用方是 [OfflineDownloader] 的 busy 回调**,不是界面 —— 界面来去自由,
         * 而下载忙不忙只有下载器知道。
         *
         * 起服务失败不往上抛:Android 15 起后台起前台服务受限,真撞上的话下载照跑,只是不再
         * 有"别杀我"的保护。让缓存整个失败反而更糟。
         */
        fun setRunning(context: Context, running: Boolean) {
            val intent = Intent(context, OfflineDownloadService::class.java)
            runCatching {
                if (running) {
                    context.startForegroundService(intent)
                } else {
                    context.stopService(intent)
                }
            }.onFailure { BiliLog.w("缓存前台服务切换失败 running=$running", it) }
        }
    }
}
