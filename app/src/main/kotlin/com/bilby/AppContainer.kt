package com.bilby

import android.content.Context
import com.bilby.agent.AgentLoop
import com.bilby.agent.LlmClient
import com.bilby.agent.ToolRegistry
import com.bilby.agent.createBiliTools
import com.bilby.api.BiliClient
import com.bilby.api.WbiSigner
import com.bilby.data.AuthRepository
import com.bilby.data.CookieRefresher
import com.bilby.data.DynamicRepository
import com.bilby.data.CommentRepository
import com.bilby.data.HeartbeatReporter
import com.bilby.data.SearchRepository
import com.bilby.data.SettingsStore
import com.bilby.data.SpaceRepository
import com.bilby.data.ToViewRepository
import com.bilby.data.VideoActionRepository
import com.bilby.data.VideoRepository
import com.bilby.data.db.BilbyDatabase
import kotlinx.coroutines.flow.first
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
        // kotlinx 默认不序列化"值等于默认值"的字段。OpenAI 协议里 tools[].type="function"
        // 和 stream=true 恰恰都是常量默认值,不开这个开关它们会整个消失,服务端回
        // 400 missing field `type`。
        encodeDefaults = true
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

    val database: BilbyDatabase by lazy { BilbyDatabase.create(appContext) }

    val videoRepository: VideoRepository by lazy { VideoRepository(biliClient) }

    val searchRepository: SearchRepository by lazy { SearchRepository(biliClient) }

    val commentRepository: CommentRepository by lazy { CommentRepository(biliClient, settings) }

    val spaceRepository: SpaceRepository by lazy { SpaceRepository(biliClient) }

    val toViewRepository: ToViewRepository by lazy { ToViewRepository(biliClient) }

    val videoActionRepository: VideoActionRepository by lazy { VideoActionRepository(biliClient) }

    val heartbeatReporter: HeartbeatReporter by lazy { HeartbeatReporter(biliClient) }

    private val llmClient: LlmClient by lazy {
        LlmClient(httpClient, json) { settings.llmConfig.first() }
    }

    private val toolRegistry: ToolRegistry by lazy {
        ToolRegistry(
            createBiliTools(searchRepository, videoRepository, spaceRepository, commentRepository, biliClient)
        )
    }

    /** 无状态:每次调用都是新的一轮,不跨轮携带任何东西(DESIGN 3.1)。 */
    val agentLoop: AgentLoop by lazy { AgentLoop(llmClient, toolRegistry, json) }
}
