package dev.bilby

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.TimeUnit

private const val COIL_DISK_CACHE_BYTES = 256L * 1024L * 1024L
private const val COIL_MIN_MEMORY_BYTES = 32L * 1024L * 1024L
private const val COIL_MAX_MEMORY_BYTES = 128L * 1024L * 1024L

/**
 * 构建带有限内存缓存、256 MB 磁盘缓存与复用连接池的 Coil 3 [ImageLoader]。
 *
 * 桌面端与 Android 共用同一套缓存预算与网络并发上限，避免不限磁盘路径导致落进系统临时目录，
 * 并使信息流封面与头像缩略图跨重启可复用本地磁盘缓存。
 */
fun buildBilbyImageLoader(
    context: PlatformContext,
    diskCacheDir: File,
    extraComponents: ComponentRegistry.Builder.() -> Unit = {},
    configure: ImageLoader.Builder.() -> Unit = {},
): ImageLoader {
    val maxMemoryBytes = (Runtime.getRuntime().maxMemory() / 8L)
        .coerceIn(COIL_MIN_MEMORY_BYTES, COIL_MAX_MEMORY_BYTES)
    val okHttpClient = OkHttpClient.Builder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            },
        )
        .connectionPool(ConnectionPool(16, 2, TimeUnit.MINUTES))
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    return ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(maxMemoryBytes)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(diskCacheDir.toOkioPath())
                .maxSizeBytes(COIL_DISK_CACHE_BYTES)
                .build()
        }
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            extraComponents()
        }
        .crossfade(true)
        .apply(configure)
        .build()
}
