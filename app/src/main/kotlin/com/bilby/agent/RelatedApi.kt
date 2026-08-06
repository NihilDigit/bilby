package com.bilby.agent

import com.bilby.api.BiliClient
import com.bilby.api.BiliConstants
import com.bilby.api.BiliResult
import com.bilby.api.getData
import com.bilby.api.map
import com.bilby.api.toHttpsUrl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 官方相关推荐 `x/web-interface/archive/related`,仓库里没有,按 notes/space-and-search.md
 * 2.11 节补。只有 `bvid` 一个参数,不需要 WBI,也没有排序/条数控制,拿回来后由调用方自行截断。
 */
data class RelatedVideo(
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val upName: String,
    val durationSeconds: Long,
    val playCount: Long,
    val danmakuCount: Long,
)

suspend fun BiliClient.getRelatedVideos(bvid: String): BiliResult<List<RelatedVideo>> =
    getData<List<RelatedArchiveDto>>(RELATED_URL, mapOf("bvid" to bvid)).map { list ->
        list.map { it.toDomain() }
    }

private const val RELATED_URL = "${BiliConstants.WEB_HOST}/x/web-interface/archive/related"

@Serializable
private data class RelatedArchiveDto(
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    val duration: Long = 0,
    val owner: RelatedOwnerDto = RelatedOwnerDto(),
    val stat: RelatedStatDto = RelatedStatDto(),
)

@Serializable
private data class RelatedOwnerDto(val name: String = "")

@Serializable
private data class RelatedStatDto(val view: Long = 0, val danmaku: Long = 0)

private fun RelatedArchiveDto.toDomain() = RelatedVideo(
    bvid = bvid,
    title = title,
    coverUrl = pic.toHttpsUrl(),
    upName = owner.name,
    durationSeconds = duration,
    playCount = stat.view,
    danmakuCount = stat.danmaku,
)
