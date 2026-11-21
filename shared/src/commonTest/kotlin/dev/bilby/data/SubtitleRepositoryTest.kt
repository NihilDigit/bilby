package dev.bilby.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.bilby.BvidCodec
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.DeviceFingerprint
import dev.bilby.api.WbiKeys
import dev.bilby.api.WbiSigner
import dev.bilby.api.dto.SubtitleBodyDto
import dev.bilby.api.dto.SubtitleLineDto
import dev.bilby.api.dto.SubtitleTrackDto
import dev.bilby.api.frameGrpcMessage
import dev.bilby.api.unframeGrpcMessage
import dev.bilby.danmaku.DanmakuProtoReader
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SubtitleRepositoryTest {

    private val testJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `frameGrpcMessage 与 unframeGrpcMessage 支持未压缩与 gzip 响应并拒绝畸形帧`() {
        val raw = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val framed = frameGrpcMessage(raw)
        assertEquals(0.toByte(), framed[0])
        assertEquals(5 + raw.size, framed.size)
        assertArrayEquals(raw, unframeGrpcMessage(framed))

        val gzBytes = ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(raw) }
            out.toByteArray()
        }
        val compressedFrame = ByteArray(5 + gzBytes.size).apply {
            this[0] = 1
            this[1] = ((gzBytes.size ushr 24) and 0xFF).toByte()
            this[2] = ((gzBytes.size ushr 16) and 0xFF).toByte()
            this[3] = ((gzBytes.size ushr 8) and 0xFF).toByte()
            this[4] = (gzBytes.size and 0xFF).toByte()
            gzBytes.copyInto(this, destinationOffset = 5)
        }
        assertArrayEquals(raw, unframeGrpcMessage(compressedFrame))

        assertIllegalState { unframeGrpcMessage(byteArrayOf(0, 0, 0)) }
        assertIllegalState { unframeGrpcMessage(byteArrayOf(2, 0, 0, 0, 0)) }
        assertIllegalState { unframeGrpcMessage(byteArrayOf(0, 0, 0, 0, 10, 1, 2)) }
    }

    @Test
    fun `encodeDmViewRequest 正确编码 64 位 pid oid 与 type`() {
        val aid = 117_648_029_387_345L
        val cid = 43_763_633_946L
        val encoded = encodeDmViewRequest(aid = aid, cid = cid)

        val reader = DanmakuProtoReader(encoded)
        var gotPid = 0L
        var gotOid = 0L
        var gotType = 0L
        while (reader.hasNext()) {
            val (field, wireType) = reader.readTag()
            assertEquals(DanmakuProtoReader.WIRE_VARINT, wireType)
            when (field) {
                1 -> gotPid = reader.readVarint()
                2 -> gotOid = reader.readVarint()
                3 -> gotType = reader.readVarint()
                else -> fail("unexpected field $field")
            }
        }
        assertEquals(aid, gotPid)
        assertEquals(cid, gotOid)
        assertEquals(1L, gotType)
    }

    @Test
    fun `parseDmViewSubtitles 提取 CC 与 AI 轨道并跳过未知字段和空链接项`() {
        val reply = buildDmViewReplyBytes(
            listOf(
                DmViewTrackFixture("ai-zh", "中文（自动生成）", "http://aisubtitle.hdslb.com/bfs/ai.json", type = 1),
                DmViewTrackFixture("zh-CN", "中文（中国）", "http://aisubtitle.hdslb.com/bfs/cc.json", type = 0),
                DmViewTrackFixture("ai-en", "英语（自动翻译）", "", type = 1),
            ),
        )
        val parsed = parseDmViewSubtitles(reply)
        assertEquals(2, parsed.size)
        assertEquals("ai-zh", parsed[0].lan)
        assertEquals(1, parsed[0].type)
        assertEquals("http://aisubtitle.hdslb.com/bfs/ai.json", parsed[0].subtitleUrl)
        assertEquals("zh-CN", parsed[1].lan)
        assertEquals(0, parsed[1].type)
    }

    @Test
    fun `toDomainTracks 过滤空链接 升级 http 与协议相对链接 去重并按中文优先非 AI 优先排序`() {
        val tracks = listOf(
            SubtitleTrackDto("en-US", "英语", type = 0, subtitleUrl = "//aisubtitle.hdslb.com/en.json"),
            SubtitleTrackDto("ai-zh", "中文（自动生成）", type = 1, subtitleUrl = "http://aisubtitle.hdslb.com/ai-zh.json"),
            SubtitleTrackDto("ai-ja", "日文", type = 1, subtitleUrl = "https://aisubtitle.hdslb.com/ai-ja.json"),
            SubtitleTrackDto("zh-CN", "中文（中国）", type = 0, subtitleUrl = "http://aisubtitle.hdslb.com/zh.json"),
            SubtitleTrackDto("zh-CN", "重复项", type = 0, subtitleUrl = "https://aisubtitle.hdslb.com/dup.json"),
            SubtitleTrackDto("ai-ko", "韩文", type = 1, subtitleUrl = "   "),
        ).toDomainTracks()

        assertEquals(listOf("zh-CN", "ai-zh", "en-US", "ai-ja"), tracks.map { it.lan })
        assertEquals("https://aisubtitle.hdslb.com/zh.json", tracks[0].subtitleUrl)
        assertEquals("中文（中国）", tracks[0].displayName)
        assertEquals("https://aisubtitle.hdslb.com/ai-zh.json", tracks[1].subtitleUrl)
        // 已有"自动"字样不重复追加"（AI）"
        assertEquals("中文（自动生成）", tracks[1].displayName)
        assertEquals("https://aisubtitle.hdslb.com/en.json", tracks[2].subtitleUrl)
        assertEquals("日文（AI）", tracks[3].displayName)
    }

    @Test
    fun `parseSubtitleCues 过滤空白句 夹取异常时间戳并按起时升序排列`() {
        val cues = parseSubtitleCues(
            SubtitleBodyDto(
                body = listOf(
                    SubtitleLineDto(from = 5.0, to = 4.0, content = "  后一句  "),
                    SubtitleLineDto(from = 1.5, to = 3.0, content = "   "),
                    SubtitleLineDto(from = -1.0, to = 2.5, content = "第一句"),
                ),
            ),
        )
        assertEquals(2, cues.size)
        assertEquals(0L, cues[0].fromMillis)
        assertEquals(2_500L, cues[0].toMillis)
        assertEquals("第一句", cues[0].text)
        assertEquals(5_000L, cues[1].fromMillis)
        assertEquals(5_000L, cues[1].toMillis)
        assertEquals("后一句", cues[1].text)
    }

    @Test
    fun `x player wbi v2 命中 -412 时立刻回退 DmView gRPC 且携带正确 app 头并不带 Cookie`() = runTest {
        val bvid = "BV1GJ411x7h7"
        val cid = 137_649_199L
        val expectedAid = BvidCodec.toAid(bvid)
        var v2Calls = 0
        var dmViewCalls = 0
        var dmViewRequest: HttpRequestData? = null
        var dmViewRequestBody: ByteArray? = null

        val repo = createRepository { request ->
            when {
                request.url.encodedPath == "/x/player/wbi/v2" -> {
                    v2Calls++
                    jsonResponse("""{"code":-412,"message":"request was banned"}""")
                }
                request.url.encodedPath == "/bilibili.community.service.dm.v1.DM/DmView" -> {
                    dmViewCalls++
                    dmViewRequest = request
                    dmViewRequestBody = request.body.toByteArray()
                    val reply = buildDmViewReplyBytes(
                        listOf(
                            DmViewTrackFixture("ai-zh", "中文（自动生成）", "http://aisubtitle.hdslb.com/bfs/ai.json", type = 1),
                        ),
                    )
                    grpcResponse(reply, gzip = true)
                }
                else -> error("unexpected url ${request.url}")
            }
        }

        val tracks = repo.getTracks(bvid, cid)
        assertEquals(1, v2Calls)
        assertEquals(1, dmViewCalls)
        assertEquals(1, tracks.size)
        assertEquals("ai-zh", tracks[0].lan)
        assertEquals("https://aisubtitle.hdslb.com/bfs/ai.json", tracks[0].subtitleUrl)

        val grpcReq = requireNotNull(dmViewRequest)
        assertEquals("app.bilibili.com", grpcReq.url.host)
        assertEquals(BiliConstants.APP_USER_AGENT, grpcReq.headers[HttpHeaders.UserAgent])
        assertEquals("identify_v1 test-access-key", grpcReq.headers[HttpHeaders.Authorization])
        assertEquals("TESTBUVID3", grpcReq.headers["buvid"])
        assertNull(grpcReq.headers[HttpHeaders.Cookie])
        assertEquals("application/grpc", grpcReq.body.contentType?.toString())

        val protoReq = unframeGrpcMessage(requireNotNull(dmViewRequestBody))
        assertArrayEquals(encodeDmViewRequest(expectedAid, cid), protoReq)
    }

    @Test
    fun `x player wbi v2 返回空字幕或空 subtitle_url 时回退 DmView`() = runTest {
        val bvid = "BV1GJ411x7h7"
        val cid = 137_649_199L
        var dmViewCalls = 0

        val repo = createRepository { request ->
            when (request.url.encodedPath) {
                "/x/player/wbi/v2" -> jsonResponse(
                    """
                    {
                      "code": 0,
                      "data": {
                        "subtitle": {
                          "subtitles": [
                            {"lan": "ai-zh", "lan_doc": "中文（自动生成）", "type": 1, "subtitle_url": ""}
                          ]
                        }
                      }
                    }
                    """.trimIndent(),
                )
                "/bilibili.community.service.dm.v1.DM/DmView" -> {
                    dmViewCalls++
                    grpcResponse(
                        buildDmViewReplyBytes(
                            listOf(
                                DmViewTrackFixture("ai-zh", "中文（自动生成）", "http://aisubtitle.hdslb.com/bfs/fallback.json", type = 1),
                            ),
                        ),
                        gzip = false,
                    )
                }
                else -> error("unexpected url ${request.url}")
            }
        }

        val tracks = repo.getTracks(bvid, cid)
        assertEquals(1, dmViewCalls)
        assertEquals(1, tracks.size)
        assertEquals("https://aisubtitle.hdslb.com/bfs/fallback.json", tracks[0].subtitleUrl)
    }

    @Test
    fun `lastPlayed 与 getTracks 并发请求同一 bvid cid 归并成单次 x player wbi v2`() = runTest {
        val bvid = "BV1GJ411x7h7"
        val cid = 137_649_199L
        var v2Calls = 0
        var dmViewCalls = 0
        val enteredV2 = CompletableDeferred<Unit>()
        val releaseV2 = CompletableDeferred<Unit>()

        val repo = createRepository { request ->
            when (request.url.encodedPath) {
                "/x/player/wbi/v2" -> {
                    v2Calls++
                    enteredV2.complete(Unit)
                    releaseV2.await()
                    jsonResponse(
                        """
                        {
                          "code": 0,
                          "data": {
                            "last_play_cid": $cid,
                            "last_play_time": 42000,
                            "subtitle": {
                              "subtitles": [
                                {"lan": "zh-CN", "lan_doc": "中文", "type": 0, "subtitle_url": "//aisubtitle.hdslb.com/bfs/ok.json"}
                              ]
                            }
                          }
                        }
                        """.trimIndent(),
                    )
                }
                "/bilibili.community.service.dm.v1.DM/DmView" -> {
                    dmViewCalls++
                    grpcResponse(ByteArray(0), gzip = false)
                }
                else -> error("unexpected url ${request.url}")
            }
        }

        val playedDeferred = async { repo.lastPlayed(bvid, cid) }
        enteredV2.await()
        val tracksDeferred = async { repo.getTracks(bvid, cid) }
        yield()
        releaseV2.complete(Unit)

        val played = playedDeferred.await()
        val tracks = tracksDeferred.await()

        assertEquals(1, v2Calls)
        assertEquals(0, dmViewCalls)
        assertEquals(LastPlayed(cid = cid, positionMillis = 42_000L), played)
        assertEquals(1, tracks.size)
        assertEquals("https://aisubtitle.hdslb.com/bfs/ok.json", tracks[0].subtitleUrl)
    }

    @Test
    fun `DmView 遭遇瞬时 503 时重试一次并成功`() = runTest {
        val bvid = "BV1GJ411x7h7"
        val cid = 137_649_199L
        var dmViewAttempts = 0

        val repo = createRepository { request ->
            when (request.url.encodedPath) {
                "/x/player/wbi/v2" -> jsonResponse("""{"code":-352,"message":"risk"}""")
                "/bilibili.community.service.dm.v1.DM/DmView" -> {
                    dmViewAttempts++
                    if (dmViewAttempts == 1) {
                        respond("", status = HttpStatusCode.ServiceUnavailable)
                    } else {
                        grpcResponse(
                            buildDmViewReplyBytes(
                                listOf(
                                    DmViewTrackFixture("zh-CN", "中文", "http://aisubtitle.hdslb.com/bfs/retry.json", type = 0),
                                ),
                            ),
                            gzip = true,
                        )
                    }
                }
                else -> error("unexpected url ${request.url}")
            }
        }

        val tracks = repo.getTracks(bvid, cid)
        assertEquals(2, dmViewAttempts)
        assertEquals(1, tracks.size)
        assertEquals("https://aisubtitle.hdslb.com/bfs/retry.json", tracks[0].subtitleUrl)
    }

    @Test
    fun `getCues 强制升级 https 且不向 CDN 发送 Cookie 与账号头并在 502 时重试一次`() = runTest {
        var attempts = 0
        var seenRequest: HttpRequestData? = null

        val repo = createRepository { request ->
            attempts++
            seenRequest = request
            if (attempts == 1) {
                respond("bad gateway", status = HttpStatusCode.BadGateway)
            } else {
                jsonResponse("""{"body":[{"from":1.2,"to":3.4,"content":"你好世界"}]}""")
            }
        }

        val cues = repo.getCues("http://aisubtitle.hdslb.com/bfs/sub.json?auth_key=abc")
        assertEquals(2, attempts)
        assertEquals(1, cues.size)
        assertEquals("你好世界", cues[0].text)
        assertEquals(1_200L, cues[0].fromMillis)
        assertEquals(3_400L, cues[0].toMillis)

        val req = requireNotNull(seenRequest)
        assertEquals("https", req.url.protocol.name)
        assertNull(req.headers[HttpHeaders.Cookie])
        assertNull(req.headers["x-bili-mid"])
        assertNull(req.headers["x-bili-aurora-eid"])
        assertNull(req.headers["app-key"])
    }

    @Test
    fun `getCues 遇到 404 不重试且不吞掉 CancellationException`() = runTest {
        var attempts404 = 0
        val repo404 = createRepository {
            attempts404++
            respond("not found", status = HttpStatusCode.NotFound)
        }
        assertTrue(repo404.getCues("//aisubtitle.hdslb.com/bfs/missing.json").isEmpty())
        assertEquals(1, attempts404)

        val repoCancel = createRepository {
            throw CancellationException("caller cancelled")
        }
        var cancelled = false
        try {
            repoCancel.getCues("https://aisubtitle.hdslb.com/bfs/cancel.json")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    private suspend fun createRepository(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SubtitleRepository {
        val http = HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(testJson) }
        }
        val settings = SettingsStore(inMemoryPreferencesStore()).apply {
            saveLogin(
                Credentials(
                    sessdata = "test-sessdata",
                    biliJct = "test-jct",
                    dedeUserId = "123456",
                    dedeUserIdCkMd5 = "test-md5",
                    accessKey = "test-access-key",
                ),
            )
        }
        val fpStore = FingerprintStore(inMemoryPreferencesStore()).apply {
            saveBuvid("TESTBUVID3")
        }
        val fingerprint = DeviceFingerprint(fpStore, http, testJson)
        val wbiSigner = WbiSigner { WbiKeys(imgKey = "a".repeat(32), subKey = "b".repeat(32)) }
        val client = BiliClient(http, settings, wbiSigner, fingerprint)
        return SubtitleRepository(
            client = client,
            dmViewRetryDelayMillis = 0L,
            cueRetryDelayMillis = 0L,
        )
    }

    private fun MockRequestHandleScope.jsonResponse(body: String): HttpResponseData = respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun MockRequestHandleScope.grpcResponse(proto: ByteArray, gzip: Boolean): HttpResponseData {
        val payload = if (gzip) {
            ByteArrayOutputStream().use { out ->
                GZIPOutputStream(out).use { it.write(proto) }
                out.toByteArray()
            }
        } else {
            proto
        }
        val framed = ByteArray(5 + payload.size).apply {
            this[0] = if (gzip) 1 else 0
            this[1] = ((payload.size ushr 24) and 0xFF).toByte()
            this[2] = ((payload.size ushr 16) and 0xFF).toByte()
            this[3] = ((payload.size ushr 8) and 0xFF).toByte()
            this[4] = (payload.size and 0xFF).toByte()
            payload.copyInto(this, destinationOffset = 5)
        }
        return respond(
            content = framed,
            status = HttpStatusCode.OK,
            headers = headersOf(
                HttpHeaders.ContentType to listOf("application/grpc"),
                "Grpc-Status" to listOf("0"),
            ),
        )
    }

    private data class DmViewTrackFixture(
        val lan: String,
        val lanDoc: String,
        val url: String,
        val type: Int,
    )

    private fun buildDmViewReplyBytes(tracks: List<DmViewTrackFixture>): ByteArray {
        val videoSubtitle = ByteArrayOutputStream().apply {
            writeStringField(1, "zh-CN")
            writeStringField(2, "中文")
            tracks.forEach { t ->
                val item = ByteArrayOutputStream().apply {
                    writeVarintField(1, 999L)
                    writeStringField(3, t.lan)
                    writeStringField(4, t.lanDoc)
                    writeStringField(5, t.url)
                    if (t.type != 0) writeVarintField(7, t.type.toLong())
                }.toByteArray()
                writeBytesField(3, item)
            }
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            writeVarintField(1, 0L) // closed = false
            writeBytesField(3, videoSubtitle) // subtitle
            writeStringField(4, "special-dm-url") // unknown field to skip
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeVarintField(field: Int, value: Long) {
        writeVarint(((field shl 3) or DanmakuProtoReader.WIRE_VARINT).toLong())
        writeVarint(value)
    }

    private fun ByteArrayOutputStream.writeStringField(field: Int, value: String) {
        writeBytesField(field, value.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArrayOutputStream.writeBytesField(field: Int, bytes: ByteArray) {
        writeVarint(((field shl 3) or DanmakuProtoReader.WIRE_LEN).toLong())
        writeVarint(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeVarint(raw: Long) {
        var v = raw
        while ((v and 0x7FL.inv()) != 0L) {
            write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    private fun assertIllegalState(block: () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (_: IllegalStateException) {
            thrown = true
        }
        assertTrue("expected IllegalStateException", thrown)
    }

    private fun inMemoryPreferencesStore(initial: Preferences = emptyPreferences()): DataStore<Preferences> =
        object : DataStore<Preferences> {
            private val mutex = Mutex()
            private val state = MutableStateFlow(initial)
            override val data: Flow<Preferences> = state
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                mutex.withLock {
                    val updated = transform(state.value)
                    state.value = updated
                    updated
                }
        }
}
