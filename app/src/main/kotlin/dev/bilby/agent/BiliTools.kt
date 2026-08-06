package dev.bilby.agent

import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliResult
import dev.bilby.data.CommentRepository
import dev.bilby.data.CommentSort
import dev.bilby.data.SearchRepository
import dev.bilby.data.SearchVideo
import dev.bilby.data.SpaceArchiveOrder
import dev.bilby.data.SpaceCollectionItem
import dev.bilby.data.SpaceRepository
import dev.bilby.data.VideoRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 条数上限硬编码在代码里,模型传多少都不作数(DESIGN 3.3 第 3 条的同类约束)。 */
private const val MAX_HOT_COMMENTS = 10
private const val MAX_RELATED = 20

/**
 * 七个工具:把人在 B 站找内容时的每个动作暴露给 LLM(DESIGN 3.2)。全部复用现有仓库,
 * 不重写接口调用;`get_native_related` 是唯一没有现成仓库覆盖的接口,补在 [RelatedApi.kt]。
 */
fun createBiliTools(
    searchRepository: SearchRepository,
    videoRepository: VideoRepository,
    spaceRepository: SpaceRepository,
    commentRepository: CommentRepository,
    client: BiliClient,
): List<Tool> = listOf(
    searchVideosTool(searchRepository),
    searchUsersTool(searchRepository),
    getVideoTool(videoRepository),
    getUpTool(spaceRepository),
    getUpVideosTool(spaceRepository),
    getCollectionTool(spaceRepository),
    getHotCommentsTool(videoRepository, commentRepository),
    getNativeRelatedTool(client),
)

private fun searchVideosTool(searchRepository: SearchRepository) = object : Tool {
    override val name = "search_videos"
    override val description = "按关键词搜索视频,可选排序、时长、分区筛选,对应用户在搜索框直接搜视频的动作。"
    override val parameters = schema(
        """
        {
          "type": "object",
          "properties": {
            "kw": {"type": "string", "description": "搜索关键词"},
            "order": {
              "type": "string",
              "description": "排序方式,不传或传空字符串为综合排序;可选值:click=播放最多,pubdate=最新发布,dm=弹幕最多,stow=收藏最多,scores=评论最多"
            },
            "duration": {
              "type": "integer",
              "description": "时长筛选:0=全部(默认),1=10分钟以下,2=10-30分钟,3=30-60分钟,4=60分钟以上"
            },
            "tid": {
              "type": "integer",
              "description": "分区id筛选,不传为不限分区。常用:1动画 13番剧 167国创 3音乐 129舞蹈 4游戏 36知识 188科技 234运动 223汽车 160生活 221美食 217动物 119鬼畜 115时尚 202资讯 5娱乐 181影视 177记录 23电影 11电视"
            }
          },
          "required": ["kw"]
        }
        """,
    )

    override fun label(arguments: JsonObject) = "搜索:${arguments.stringArg("kw").orEmpty()}"

    override suspend fun execute(arguments: JsonObject): ToolResult = runCatching {
        val kw = arguments.stringArg("kw").orEmpty()
        val order = arguments.stringArg("order").orEmpty()
        val duration = arguments.intArg("duration")
        val tid = arguments.intArg("tid")
        when (val result = searchRepository.searchVideos(kw, page = 1, order = order, duration = duration, tids = tid)) {
            is BiliResult.Ok -> {
                val items = result.value.items
                if (items.isEmpty()) {
                    ToolResult(forModel = "没搜到相关视频")
                } else {
                    ToolResult(
                        forModel = items.joinToString("\n") { it.toLine() },
                        forUi = items.map { it.toTraceItem() },
                        bvids = items.mapTo(mutableSetOf()) { it.bvid },
                    )
                }
            }
            else -> result.toFailureResult()
        }
    }.getOrElse {
        BiliLog.w("工具 $name 执行异常", it)
        ToolResult(forModel = "查询失败:${it.message}")
    }

    private fun SearchVideo.toLine() =
        "$bvid | $title | $upName | $durationText | 播放${playCount.formatCount()}"

    private fun SearchVideo.toTraceItem() = TraceItem(bvid, title, coverUrl, upName)
}

private fun searchUsersTool(searchRepository: SearchRepository) = object : Tool {
    override val name = "search_users"
    override val description = "按关键词搜索用户(UP主),对应用户在搜索框搜人的动作。"
    override val parameters = schema(
        """{"type": "object", "properties": {"kw": {"type": "string", "description": "搜索关键词(用户昵称)"}}, "required": ["kw"]}""",
    )

    override fun label(arguments: JsonObject) = "搜人:${arguments.stringArg("kw").orEmpty()}"

    override suspend fun execute(arguments: JsonObject): ToolResult = runCatching {
        val kw = arguments.stringArg("kw").orEmpty()
        when (val result = searchRepository.searchUsers(kw)) {
            is BiliResult.Ok -> {
                val users = result.value
                if (users.isEmpty()) {
                    ToolResult(forModel = "没搜到相关用户")
                } else {
                    ToolResult(
                        forModel = users.joinToString("\n") {
                            "mid=${it.mid} | ${it.name} | 粉丝${it.fansCount.formatCount()} | ${it.signature.truncate(100)}"
                        },
                    )
                }
            }
            else -> result.toFailureResult()
        }
    }.getOrElse {
        BiliLog.w("工具 $name 执行异常", it)
        ToolResult(forModel = "查询失败:${it.message}")
    }
}

private fun getVideoTool(videoRepository: VideoRepository) = object : Tool {
    override val name = "get_video"
    override val description = "打开一个视频看详情:简介、数据、分P、所属合集,对应用户点进视频详情页的动作。"
    override val parameters = schema(
        """{"type": "object", "properties": {"bvid": {"type": "string", "description": "视频BV号"}}, "required": ["bvid"]}""",
    )

    override fun label(arguments: JsonObject) = "看了 ${arguments.stringArg("bvid").orEmpty()} 的详情"

    override suspend fun execute(arguments: JsonObject): ToolResult = runCatching {
        val bvid = arguments.stringArg("bvid").orEmpty()
        when (val result = videoRepository.getVideoDetail(bvid)) {
            is BiliResult.Ok -> {
                val d = result.value
                val lines = mutableListOf(
                    "${d.bvid} | ${d.title}",
                    "UP: ${d.up.name}(mid=${d.up.mid})",
                    "简介: ${d.description.truncate(100)}",
                    "时长${d.durationSeconds.formatDuration()} 播放${d.stat.view.formatCount()} 弹幕${d.stat.danmaku.formatCount()} " +
                        "评论${d.stat.reply.formatCount()} 点赞${d.stat.like.formatCount()} 投币${d.stat.coin.formatCount()} 收藏${d.stat.favorite.formatCount()}",
                    "分P数: ${d.pages.size}",
                )
                val bvids = mutableSetOf(d.bvid)
                val traceItems = mutableListOf(TraceItem(d.bvid, d.title, d.coverUrl, d.up.name))
                if (d.seasonEpisodes.isNotEmpty()) {
                    lines += "合集《${d.seasonTitle}》共${d.seasonEpisodes.size}集:"
                    d.seasonEpisodes.forEach { ep ->
                        lines += "  ${ep.bvid} | ${ep.title}"
                        bvids += ep.bvid
                        // 分集没有单独的 up 信息(SeasonEpisode 不带 upName),同一合集通常同一 UP,用主视频的名字近似。
                        traceItems += TraceItem(ep.bvid, ep.title, ep.coverUrl, d.up.name)
                    }
                }
                ToolResult(forModel = lines.joinToString("\n"), forUi = traceItems, bvids = bvids)
            }
            else -> result.toFailureResult()
        }
    }.getOrElse {
        BiliLog.w("工具 $name 执行异常", it)
        ToolResult(forModel = "查询失败:${it.message}")
    }
}

private fun getUpTool(spaceRepository: SpaceRepository) = object : Tool {
    override val name = "get_up"
    override val description = "进入UP主的空间看简介,对应用户点进个人空间首页的动作。"
    override val parameters = schema(
        """{"type": "object", "properties": {"mid": {"type": "integer", "description": "UP主的用户id(mid)"}}, "required": ["mid"]}""",
    )

    override fun label(arguments: JsonObject) = "进了 ${arguments.longArg("mid") ?: 0} 的空间"

    override suspend fun execute(arguments: JsonObject): ToolResult = runCatching {
        val mid = arguments.longArg("mid") ?: 0L
        when (val result = spaceRepository.loadProfile(mid)) {
            is BiliResult.Ok -> {
                val p = result.value
                ToolResult(
                    forModel = "mid=${p.mid} | ${p.name} | Lv${p.level} | 粉丝${p.follower.formatCount()} | 签名:${p.sign.truncate(100)}",
                )
            }
            else -> result.toFailureResult()
        }
    }.getOrElse {
        BiliLog.w("工具 $name 执行异常", it)
        ToolResult(forModel = "查询失败:${it.message}")
    }
}

private fun getUpVideosTool(spaceRepository: SpaceRepository) = object : Tool {
    override val name = "get_up_videos"
    override val description = "看UP主的投稿列表,可按播放量排序找代表作,也可在空间内搜索,对应用户翻空间投稿tab的动作。"
    override val parameters = schema(
        """
        {
          "type": "object",
          "properties": {
            "mid": {"type": "integer", "description": "UP主的用户id(mid)"},
            "order": {"type": "string", "description": "排序方式,可选值:pubdate=最新发布(默认),click=最多播放"},
            "kw": {"type": "string", "description": "可选,空间内搜索关键词;不传则列出全部投稿"}
          },
          "required": ["mid"]
        }
        """,
    )

    override fun label(arguments: JsonObject) = "翻了 ${arguments.longArg("mid") ?: 0} 的投稿"

    override suspend fun execute(arguments: JsonObject): ToolResult = runCatching {
        val mid = arguments.longArg("mid") ?: 0L
        val order = if (arguments.stringArg("order") == "click") SpaceArchiveOrder.Click else SpaceArchiveOrder.Pubdate
        val kw = arguments.stringArg("kw")
        when (val result = spaceRepository.loadArchives(mid, page = 1, order = order, keyword = kw)) {
            is BiliResult.Ok -> {
                val page = result.value
                if (page.items.isEmpty()) {
                    ToolResult(forModel = "该UP没有符合条件的投稿")
                } else {
                    ToolResult(
                        forModel = "共${page.total}个投稿,以下前${page.items.size}条:\n" +
                            page.items.joinToString("\n") {
                                "${it.bvid} | ${it.title} | ${it.durationText} | 播放${it.playCountText}"
                            },
                        // SpaceVideoItem 不带 upName(它本身就是某个 mid 下的列表),TraceItem 的 upName 留空。
                        forUi = page.items.map { TraceItem(it.bvid, it.title, it.coverUrl, upName = "") },
                        bvids = page.items.mapTo(mutableSetOf()) { it.bvid },
                    )
                }
            }
            else -> result.toFailureResult()
        }
    }.getOrElse {
        BiliLog.w("工具 $name 执行异常", it)
        ToolResult(forModel = "查询失败:${it.message}")
    }
}

private fun getCollectionTool(spaceRepository: SpaceRepository) = object : Tool {
    override val name = "get_collection"
    override val description = "看合集/系列目录,对应用户点进UP主合集页的动作。"
    override val parameters = schema(
        """
        {
          "type": "object",
          "properties": {
            "sid": {"type": "integer", "description": "合集或系列的id"},
            "mid": {"type": "integer", "description": "该合集所属UP主的mid,通常从 get_up_videos 或 get_video 里获得"},
            "is_season": {"type": "boolean", "description": "true=合集(season),false=系列(series);不确定时先按true尝试"}
          },
          "required": ["sid", "mid", "is_season"]
        }
        """,
    )

    override fun label(arguments: JsonObject) = "看了合集 ${arguments.longArg("sid") ?: 0} 的目录"

    override suspend fun execute(arguments: JsonObject): ToolResult = runCatching {
        val sid = arguments.longArg("sid") ?: 0L
        val mid = arguments.longArg("mid") ?: 0L
        val isSeason = arguments.boolArg("is_season") ?: true
        // loadCollectionDetail 要的是完整 SpaceCollectionItem,这里只有 id/isSeason 是请求真正用到的字段,
        // 名字/封面/总数等展示字段对这次请求无意义,填占位值。
        val collection = SpaceCollectionItem(id = sid, isSeason = isSeason, name = "", coverUrl = "", total = 0, ptimeEpochSeconds = 0)
        when (val result = spaceRepository.loadCollectionDetail(mid, collection, page = 1)) {
            is BiliResult.Ok -> {
                val page = result.value
                if (page.items.isEmpty()) {
                    ToolResult(forModel = "该合集没有视频,或 mid/is_season 传错了")
                } else {
                    ToolResult(
                        forModel = "共${page.total}集,以下前${page.items.size}条:\n" +
                            page.items.joinToString("\n") {
                                "${it.bvid} | ${it.title} | ${it.durationText} | 播放${it.playCountText}"
                            },
                        forUi = page.items.map { TraceItem(it.bvid, it.title, it.coverUrl, upName = "") },
                        bvids = page.items.mapTo(mutableSetOf()) { it.bvid },
                    )
                }
            }
            else -> result.toFailureResult()
        }
    }.getOrElse {
        BiliLog.w("工具 $name 执行异常", it)
        ToolResult(forModel = "查询失败:${it.message}")
    }
}

private fun getHotCommentsTool(videoRepository: VideoRepository, commentRepository: CommentRepository) = object : Tool {
    override val name = "get_hot_comments"
    override val description = "翻一个视频的热门评论验货,热评是内容质量的口碑数据库,对应老手点进评论区按热度排序看的动作。"
    override val parameters = schema(
        """
        {
          "type": "object",
          "properties": {
            "bvid": {"type": "string", "description": "视频BV号"},
            "n": {"type": "integer", "description": "要看的热评条数,最多10条,不传默认5条"}
          },
          "required": ["bvid"]
        }
        """,
    )

    override fun label(arguments: JsonObject) = "读了 ${arguments.stringArg("bvid").orEmpty()} 的热评"

    override suspend fun execute(arguments: JsonObject): ToolResult = runCatching {
        val bvid = arguments.stringArg("bvid").orEmpty()
        val n = (arguments.intArg("n") ?: 5).coerceIn(1, MAX_HOT_COMMENTS)
        // 评论接口按 oid(即 aid)取,不是 bvid,先查详情换 aid,复用 VideoRepository 而不是另开一套映射。
        when (val detail = videoRepository.getVideoDetail(bvid)) {
            is BiliResult.Ok -> {
                when (val page = commentRepository.loadMainPage(detail.value.aid, CommentSort.HOT)) {
                    is BiliResult.Ok -> {
                        val comments = listOfNotNull(page.value.topComment) + page.value.items
                        val picked = comments.distinctBy { it.rpid }.take(n)
                        if (picked.isEmpty()) {
                            ToolResult(forModel = "该视频还没有评论", bvids = setOf(bvid))
                        } else {
                            ToolResult(
                                forModel = picked.joinToString("\n") {
                                    "@${it.uname}: ${it.message.oneLine().truncate(80)} 赞${it.likeCount}"
                                },
                                bvids = setOf(bvid),
                            )
                        }
                    }
                    else -> page.toFailureResult()
                }
            }
            else -> detail.toFailureResult()
        }
    }.getOrElse {
        BiliLog.w("工具 $name 执行异常", it)
        ToolResult(forModel = "查询失败:${it.message}")
    }

}

private fun getNativeRelatedTool(client: BiliClient) = object : Tool {
    override val name = "get_native_related"
    override val description = "瞟一眼官方相关推荐,作为一份可批判阅读的候选数据源,对应用户看播放页相关推荐栏的动作。"
    override val parameters = schema(
        """{"type": "object", "properties": {"bvid": {"type": "string", "description": "视频BV号"}}, "required": ["bvid"]}""",
    )

    override fun label(arguments: JsonObject) = "瞟了一眼 ${arguments.stringArg("bvid").orEmpty()} 的相关推荐"

    override suspend fun execute(arguments: JsonObject): ToolResult = runCatching {
        val bvid = arguments.stringArg("bvid").orEmpty()
        when (val result = client.getRelatedVideos(bvid)) {
            is BiliResult.Ok -> {
                // 接口本身不给排序/数量控制(notes 2.11),这里截一刀避免刷爆上下文。
                val items = result.value.take(MAX_RELATED)
                if (items.isEmpty()) {
                    ToolResult(forModel = "没有相关推荐")
                } else {
                    ToolResult(
                        forModel = items.joinToString("\n") {
                            "${it.bvid} | ${it.title} | ${it.upName} | ${it.durationSeconds.formatDuration()} | 播放${it.playCount.formatCount()}"
                        },
                        forUi = items.map { TraceItem(it.bvid, it.title, it.coverUrl, it.upName) },
                        bvids = items.mapTo(mutableSetOf()) { it.bvid },
                    )
                }
            }
            else -> result.toFailureResult()
        }
    }.getOrElse {
        BiliLog.w("工具 $name 执行异常", it)
        ToolResult(forModel = "查询失败:${it.message}")
    }

}

private fun schema(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

private fun JsonObject.stringArg(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.longArg(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
private fun JsonObject.intArg(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.boolArg(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

/** BiliResult 的错误分支统一转成给模型看的失败文本,调用方只处理 Ok 分支即可。 */
private fun BiliResult<*>.toFailureResult(): ToolResult = when (this) {
    is BiliResult.Ok -> error("Ok 不是失败,不该走到这里")
    is BiliResult.ApiError -> ToolResult(forModel = "查询失败(code=$code):$message")
    is BiliResult.Failure -> ToolResult(forModel = "查询失败:${cause.message}")
}

private fun String.truncate(maxLength: Int): String = if (length <= maxLength) this else take(maxLength) + "…"

private fun String.oneLine(): String = replace("\n", " ").replace("\r", "")

private fun Long.formatCount(): String = when {
    this >= 100_000_000 -> "%.1f亿".format(this / 100_000_000.0)
    this >= 10_000 -> "%.1f万".format(this / 10_000.0)
    else -> toString()
}

private fun Long.formatDuration(): String {
    val minutes = this / 60
    val seconds = this % 60
    return "%d:%02d".format(minutes, seconds)
}
