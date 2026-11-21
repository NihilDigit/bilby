package dev.bilby.offline

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.bilby.api.BiliClient
import dev.bilby.api.DeviceFingerprint
import dev.bilby.api.WbiKeys
import dev.bilby.api.WbiSigner
import dev.bilby.danmaku.DanmakuRepository
import dev.bilby.data.FingerprintStore
import dev.bilby.data.SettingsStore
import dev.bilby.data.VideoRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 离线下载器队列控制测试:覆盖启动不自动续下 Paused、pause 保留分片、resume/pauseAll/resumeAll/
 * retryAllFailed、clearQueue 仅清未完成条目、以及 deleteAndAwait 落盘后再算空间。
 */
class OfflineDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    private fun newStore(): OfflineStore = OfflineStore(temp.newFolder("offline"), json)

    private fun newDownloader(
        scope: CoroutineScope,
        store: OfflineStore,
        hasNetwork: () -> Boolean = { false },
        awaitNetwork: suspend () -> Unit = { CompletableDeferred<Unit>().await() },
    ): OfflineDownloader {
        val prefs = FakePreferencesDataStore()
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) })
        val client = BiliClient(
            http = http,
            settings = SettingsStore(prefs),
            wbiSigner = WbiSigner { WbiKeys("img", "sub") },
            fingerprint = DeviceFingerprint(FingerprintStore(prefs), http, json),
        )
        return OfflineDownloader(
            scope = scope,
            store = store,
            client = client,
            videoRepository = VideoRepository(client),
            danmakuRepository = DanmakuRepository(client),
            concurrency = flowOf(1),
            preferredCodecs = { emptyList() },
            hasNetwork = hasNetwork,
            awaitNetwork = awaitNetwork,
            diskFullReason = { "存储空间不足" },
        )
    }

    private fun request(bvid: String, cid: Long) = OfflineRequest(
        bvid = bvid,
        cid = cid,
        title = "视频 $bvid",
        upName = "作者",
        coverUrl = "",
        durationSeconds = 60,
        qualityId = 80,
        seasonId = 900L,
        seasonTitle = "合集甲",
        seasonMid = 77L,
    )

    @Test
    fun `启动时 Paused 条目不被自动重新排队且保留分片文件`() = runBlocking {
        val store = newStore()
        val paused = OfflineItem(
            bvid = "BV1paused",
            cid = 101L,
            title = "已暂停",
            status = OfflineStatus.Paused,
            downloadedBytes = 512L,
            totalBytes = 2048L,
            createdAtMillis = 100L,
        )
        store.write(paused)
        store.videoFile("BV1paused", 101L).writeBytes(ByteArray(512))

        val networkWaits = AtomicInteger(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloader = newDownloader(
                scope = scope,
                store = store,
                hasNetwork = { false },
                awaitNetwork = {
                    networkWaits.incrementAndGet()
                    CompletableDeferred<Unit>().await()
                },
            )
            withTimeout(5_000) {
                downloader.items.first { list -> list.any { it.bvid == "BV1paused" } }
            }
            val loaded = downloader.items.value.single { it.bvid == "BV1paused" }
            assertEquals(OfflineStatus.Paused, loaded.status)
            assertEquals(0, networkWaits.get())
            assertEquals(512L, store.videoFile("BV1paused", 101L).length())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `pause 取消运行中任务但保留部分分片文件,resume 重新入队`() = runBlocking {
        val store = newStore()
        val enteredRunOne = CompletableDeferred<Unit>()
        val resumedRunOne = CompletableDeferred<Unit>()
        val attempts = AtomicInteger(0)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloader = newDownloader(
                scope = scope,
                store = store,
                hasNetwork = { false },
                awaitNetwork = {
                    if (attempts.incrementAndGet() == 1) {
                        enteredRunOne.complete(Unit)
                    } else {
                        resumedRunOne.complete(Unit)
                    }
                    CompletableDeferred<Unit>().await()
                },
            )
            downloader.enqueue(listOf(request("BV1a", 10L)))
            withTimeout(5_000) { enteredRunOne.await() }

            // 模拟已落盘的部分 .m4s 分片
            val partial = store.videoFile("BV1a", 10L)
            partial.parentFile?.mkdirs()
            partial.writeBytes(ByteArray(4096))

            downloader.pauseAndAwait("BV1a", 10L)

            val afterPause = downloader.items.value.single { it.bvid == "BV1a" }
            assertEquals(OfflineStatus.Paused, afterPause.status)
            assertEquals(900L, afterPause.seasonId)
            assertEquals("合集甲", afterPause.seasonTitle)
            assertTrue("暂停不得删除已下载的分片文件", partial.isFile)
            assertEquals(4096L, partial.length())
            assertEquals(OfflineStatus.Paused, store.read("BV1a", 10L)?.status)

            // 恢复后重新进入排队并再次被消费者拉起
            downloader.resume("BV1a", 10L)
            withTimeout(5_000) { resumedRunOne.await() }
            assertEquals(OfflineStatus.Queued, downloader.items.value.single { it.bvid == "BV1a" }.status)
            assertEquals(4096L, partial.length())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `pauseAll 与 resumeAll 及 retryAllFailed 按状态精准筛选`() = runBlocking {
        val store = newStore()
        val completed = OfflineItem(
            bvid = "BV1done", cid = 1L, title = "已完成", status = OfflineStatus.Completed, createdAtMillis = 1L,
        )
        val failed = OfflineItem(
            bvid = "BV1fail", cid = 2L, title = "失败", status = OfflineStatus.Failed, error = "取流失败", createdAtMillis = 2L,
        )
        store.write(completed)
        store.write(failed)

        val activeStarted = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloader = newDownloader(
                scope = scope,
                store = store,
                hasNetwork = { false },
                awaitNetwork = {
                    activeStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                },
            )
            withTimeout(5_000) {
                downloader.items.first { list -> list.size == 2 }
            }
            downloader.enqueue(listOf(request("BV1q1", 11L), request("BV1q2", 12L)))
            withTimeout(5_000) { activeStarted.await() }
            withTimeout(5_000) {
                downloader.items.first { list -> list.count { it.status == OfflineStatus.Queued } == 2 }
            }

            // pauseAll 只暂停排队/下载中的两条,不动已完成与已失败
            downloader.pauseAllAndAwait()
            val afterPauseAll = downloader.items.value.associateBy { it.bvid }
            assertEquals(OfflineStatus.Paused, afterPauseAll["BV1q1"]?.status)
            assertEquals(OfflineStatus.Paused, afterPauseAll["BV1q2"]?.status)
            assertEquals(OfflineStatus.Completed, afterPauseAll["BV1done"]?.status)
            assertEquals(OfflineStatus.Failed, afterPauseAll["BV1fail"]?.status)

            // retryAllFailed 只重试失败条目,不动已暂停条目
            downloader.retryAllFailed()
            withTimeout(5_000) {
                downloader.items.first { list ->
                    list.firstOrNull { it.bvid == "BV1fail" }?.status == OfflineStatus.Queued
                }
            }
            val afterRetry = downloader.items.value.associateBy { it.bvid }
            assertEquals(OfflineStatus.Paused, afterRetry["BV1q1"]?.status)
            assertEquals(OfflineStatus.Paused, afterRetry["BV1q2"]?.status)

            // resumeAll 恢复全部已暂停条目
            downloader.resumeAll()
            withTimeout(5_000) {
                downloader.items.first { list ->
                    list.none { it.status == OfflineStatus.Paused }
                }
            }
            val afterResumeAll = downloader.items.value.associateBy { it.bvid }
            assertEquals(OfflineStatus.Queued, afterResumeAll["BV1q1"]?.status)
            assertEquals(OfflineStatus.Queued, afterResumeAll["BV1q2"]?.status)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `clearQueue 仅清除未完成条目与分片,绝不触碰已完成视频`() = runBlocking {
        val store = newStore()
        val done = OfflineItem(
            bvid = "BV1done", cid = 1L, title = "已完成", status = OfflineStatus.Completed, createdAtMillis = 1L,
        )
        val paused = OfflineItem(
            bvid = "BV1paused", cid = 2L, title = "暂停", status = OfflineStatus.Paused, createdAtMillis = 2L,
        )
        val failed = OfflineItem(
            bvid = "BV1fail", cid = 3L, title = "失败", status = OfflineStatus.Failed, error = "err", createdAtMillis = 3L,
        )
        store.write(done)
        store.videoFile("BV1done", 1L).writeBytes(ByteArray(2048))
        store.write(paused)
        store.videoFile("BV1paused", 2L).writeBytes(ByteArray(1024))
        store.write(failed)
        store.videoFile("BV1fail", 3L).writeBytes(ByteArray(512))

        val queuedEntered = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloader = newDownloader(
                scope = scope,
                store = store,
                hasNetwork = { false },
                awaitNetwork = {
                    queuedEntered.complete(Unit)
                    CompletableDeferred<Unit>().await()
                },
            )
            withTimeout(5_000) { downloader.items.first { it.size == 3 } }
            downloader.enqueue(listOf(request("BV1queued", 4L)))
            withTimeout(5_000) { queuedEntered.await() }
            store.videoFile("BV1queued", 4L).apply { parentFile?.mkdirs(); writeBytes(ByteArray(256)) }

            downloader.clearQueueAndAwait()

            assertEquals(listOf("BV1done"), downloader.items.value.map { it.bvid })
            assertTrue("已完成视频文件必须保留", store.videoFile("BV1done", 1L).isFile)
            assertFalse("已暂停分片应被清掉", store.videoFile("BV1paused", 2L).exists())
            assertFalse("已失败分片应被清掉", store.videoFile("BV1fail", 3L).exists())
            assertFalse("排队中分片应被清掉", store.videoFile("BV1queued", 4L).exists())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `deleteAndAwait 返回后磁盘文件已清除,usedBytes 统计无竞态`() = runBlocking {
        val store = newStore()
        val done = OfflineItem(
            bvid = "BV1del", cid = 5L, title = "待删", status = OfflineStatus.Completed, createdAtMillis = 1L,
        )
        store.write(done)
        store.videoFile("BV1del", 5L).writeBytes(ByteArray(8192))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val downloader = newDownloader(scope = scope, store = store)
            withTimeout(5_000) { downloader.items.first { it.size == 1 } }
            assertTrue(store.usedBytes() >= 8192L)

            downloader.deleteAndAwait(done)

            assertEquals(0L, store.usedBytes())
            assertTrue(downloader.items.value.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
