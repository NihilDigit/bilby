package dev.bilby.offline

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.bilby.BilbyApplication
import dev.bilby.BiliLog

/**
 * 联网后补发断网期间没发出去的心跳。发什么、发完怎么记账都在
 * [dev.bilby.data.HeartbeatReporter.flushPending],这里只管"什么时候跑"。
 *
 * 交给 WorkManager 而不是自己监听网络:补发要能等到进程死过一回之后。断网看完缓存、划掉 app,
 * 第二天联网时 app 未必被打开,而 WorkManager 的任务存在它自己的库里,满足联网条件时由系统拉起。
 *
 * 用默认的两参构造、自己去 [BilbyApplication] 取容器,而不是注册自定义 WorkerFactory:后者要
 * 关掉 WorkManager 的自动初始化、改 manifest,只为了把一个本来就是单例的依赖递进来。
 * 默认工厂按类名反射构造,keep 规则由 work-runtime 自带的 consumer rules 提供。
 */
class HeartbeatFlushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reporter = (applicationContext as BilbyApplication).container.heartbeatReporter
        return if (reporter.flushPending()) {
            Result.success()
        } else {
            BiliLog.w("补发心跳没发完,退避后再试 attempt=$runAttemptCount")
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "heartbeat-flush"

        /**
         * 排一次补发。**KEEP**:已经排着或正在跑的那一个会把表里的全部条目发完(它每一轮都重读
         * 这张表),再排一个只是让同一批请求发两遍。APPEND 在断网看视频时每 5 秒接一个任务,
         * 链越排越长,联网那一刻一口气跑几百个空任务。
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<HeartbeatFlushWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            }.onFailure { BiliLog.w("排心跳补发任务失败", it) }
        }
    }
}
