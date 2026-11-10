package dev.bilby

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.util.DebugLogger
import dev.bilby.app.BuildConfig

class BilbyApplication : Application(), AppContainerOwner, SingletonImageLoader.Factory {

    override lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppBuild.init(
            debug = BuildConfig.DEBUG,
            versionName = BuildConfig.VERSION_NAME,
            applicationId = BuildConfig.APPLICATION_ID,
            llmBaseUrl = BuildConfig.LLM_BASE_URL,
            llmApiKey = BuildConfig.LLM_API_KEY,
        )
        container = AppContainer(AndroidPlatform(this))
    }

    /**
     * Coil 3 不会自动接上网络加载器:光有 coil-network-okhttp 依赖而不注册 fetcher,
     * 所有 http(s) 图片会静默不加载(logcat 里一条错误都没有),表现为封面永远空白。
     * 动图解码器同理:显式挂上 [AnimatedImageDecoder] (API 28+ ImageDecoder,同时支持 GIF 与动态 WebP)。
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory())
                add(AnimatedImageDecoder.Factory())
            }
            .crossfade(true)
            .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
            .build()
}
