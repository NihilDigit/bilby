package dev.bilby

import android.content.Context
import dev.bilby.agent.AgentLoop
import dev.bilby.agent.LlmClient
import dev.bilby.agent.ToolRegistry
import dev.bilby.agent.createBiliTools
import dev.bilby.api.BiliClient
import dev.bilby.api.DeviceFingerprint
import dev.bilby.api.WbiSigner
import dev.bilby.data.AccountRepository
import dev.bilby.data.DynamicRepository
import dev.bilby.data.FingerprintStore
import dev.bilby.danmaku.DanmakuRepository
import dev.bilby.data.CommentRepository
import dev.bilby.data.FavRepository
import dev.bilby.data.FollowRepository
import dev.bilby.data.HeartbeatReporter
import dev.bilby.data.HistoryRepository
import dev.bilby.data.QueueSourceRepository
import dev.bilby.data.UpdateRepository
import dev.bilby.data.SearchRepository
import dev.bilby.data.SettingsStore
import dev.bilby.data.RelationRepository
import dev.bilby.data.SpaceRepository
import dev.bilby.data.SponsorBlockRepository
import dev.bilby.data.SubtitleRepository
import dev.bilby.data.ToViewRepository
import dev.bilby.data.TvLoginRepository
import dev.bilby.data.VideoActionRepository
import dev.bilby.data.VideoRepository
import dev.bilby.data.db.BilbyDatabase
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

    private val fingerprintStore: FingerprintStore by lazy { FingerprintStore(appContext) }

    /** 设备指纹。缺了它写接口会被风控判成"账号异常",读接口则一切正常。 */
    val deviceFingerprint: DeviceFingerprint by lazy {
        DeviceFingerprint(fingerprintStore, httpClient, json)
    }

    // 签名器要发请求、客户端要签名,循环依赖靠 keyProvider 这个惰性 lambda 打断:
    // 构造 WbiSigner 时不会调用它,真正取 key 时 biliClient 早已就绪。
    val wbiSigner: WbiSigner by lazy { WbiSigner { biliClient.fetchWbiKeys() } }

    val biliClient: BiliClient by lazy { BiliClient(httpClient, settings, wbiSigner, deviceFingerprint) }

    /**
     * **唯一**的登录方式:TV 扫码,一次扫码同时拿 Cookie 和 access_key。网页扫码只给 Cookie,
     * 而写操作(点赞/投币)必须走 app 端接口、必须有 access_key(notes/auth-model.md §2.5)。
     *
     * 没有 cookie 刷新这一环:凭据过期就重新扫码,理由见 notes/auth-model.md §7。
     */
    val tvLoginRepository: TvLoginRepository by lazy { TvLoginRepository(biliClient, settings) }


    val dynamicRepository: DynamicRepository by lazy { DynamicRepository(biliClient) }

    val database: BilbyDatabase by lazy { BilbyDatabase.create(appContext) }

    val videoRepository: VideoRepository by lazy { VideoRepository(biliClient) }

    val searchRepository: SearchRepository by lazy { SearchRepository(biliClient) }

    val commentRepository: CommentRepository by lazy { CommentRepository(biliClient, settings) }

    val spaceRepository: SpaceRepository by lazy { SpaceRepository(biliClient) }

    val relationRepository: RelationRepository by lazy { RelationRepository(biliClient) }

    val toViewRepository: ToViewRepository by lazy { ToViewRepository(biliClient) }

    val historyRepository: HistoryRepository by lazy { HistoryRepository(biliClient) }

    /** 个人页头部的账号身份(头像/名字/等级/签名),来自 `x/web-interface/nav` + `SpaceRepository`。 */
    val accountRepository: AccountRepository by lazy { AccountRepository(biliClient, spaceRepository) }

    val followRepository: FollowRepository by lazy { FollowRepository(biliClient, settings) }

    val favRepository: FavRepository by lazy { FavRepository(biliClient, settings) }

    val videoActionRepository: VideoActionRepository by lazy { VideoActionRepository(biliClient) }

    val subtitleRepository: SubtitleRepository by lazy { SubtitleRepository(biliClient) }

    val danmakuRepository: DanmakuRepository by lazy { DanmakuRepository(biliClient) }

    val heartbeatReporter: HeartbeatReporter by lazy { HeartbeatReporter(biliClient) }

    /** 第三方服务,不走 BiliClient(它带 B 站的 Cookie 与 Referer,发给别人既无必要也不合适)。 */
    val sponsorBlockRepository: SponsorBlockRepository by lazy { SponsorBlockRepository(httpClient, json) }

    /** 手动更新只在设置页点一下时才用,懒到那一刻再建。 */
    val updateRepository: UpdateRepository by lazy { UpdateRepository(httpClient, json) }

    val queueSourceRepository: QueueSourceRepository by lazy {
        QueueSourceRepository(spaceRepository, videoRepository)
    }

    val llmClient: LlmClient by lazy {
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
