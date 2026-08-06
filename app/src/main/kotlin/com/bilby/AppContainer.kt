package com.bilby

import android.content.Context
import com.bilby.api.BiliClient
import com.bilby.api.WbiSigner
import com.bilby.data.AuthRepository
import com.bilby.data.CookieRefresher
import com.bilby.data.DynamicRepository
import com.bilby.data.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * 手写 DI。单人单 module,依赖图小到一屏能看完,不预付框架成本;
 * 膨胀到看不完时再换 Koin(DESIGN 4 节)。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    // 签名器要发请求、客户端要签名,循环依赖靠 keyProvider 这个惰性 lambda 打断:
    // 构造 WbiSigner 时不会调用它,真正取 key 时 biliClient 早已就绪。
    val wbiSigner: WbiSigner by lazy { WbiSigner { biliClient.fetchWbiKeys() } }

    val biliClient: BiliClient by lazy { BiliClient(httpClient, settings, wbiSigner) }

    val authRepository: AuthRepository by lazy { AuthRepository(biliClient, settings) }

    val cookieRefresher: CookieRefresher by lazy { CookieRefresher(biliClient, settings) }

    val dynamicRepository: DynamicRepository by lazy { DynamicRepository(biliClient) }
}
