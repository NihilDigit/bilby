package dev.bilby.data

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.LiveEmotePackageDto
import dev.bilby.api.dto.LiveEmotePanelDto
import dev.bilby.api.dto.ReplyEmotePackageDto
import dev.bilby.api.dto.ReplyEmotePanelDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.toHttpsUrl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class EmoteTabKind {
    SmallImage,
    LargeImage,
    Kaomoji,
}

sealed interface EmoteAction {
    data class InsertText(val text: String) : EmoteAction
    data class SendLiveSticker(val emoticonUnique: String) : EmoteAction
}

@Immutable
data class EmoteEntry(
    val key: String,
    val label: String,
    val imageUrl: String,
    val action: EmoteAction,
    val enabled: Boolean = true,
    val lockHint: String = "",
)

@Immutable
data class EmoteTab(
    val id: String,
    val name: String,
    val iconUrl: String,
    val kind: EmoteTabKind,
    val items: List<EmoteEntry>,
)

/**
 * 评论区、私信与直播间的表情包加载与内存缓存。
 *
 * - 评论区与私信共用 `x/emote/user/panel/web?business=reply`(见 `notes/comment-toview-history.md`
 *   §1.9 与 `notes/private-message.md` §9),按当前账号 mid 在进程内缓存一份。
 * - 直播间走 `xlive/web-ucenter/v2/emoticon/GetEmoticons`(见 `notes/live.md` §4.1),每个房间有
 *   各自的专属表情与粉丝勋章解锁状态,按 `(mid, roomId)` 缓存最后几个房间。
 */
class EmoteRepository(
    private val client: BiliClient,
    private val settings: SettingsStore,
) {
    private val mutex = Mutex()
    private var replyCachedMid: String? = null
    private var replyCachedTabs: List<EmoteTab>? = null
    private val liveCache = LinkedHashMap<Pair<String, Long>, List<EmoteTab>>()

    suspend fun loadReplyEmotes(forceRefresh: Boolean = false): BiliResult<List<EmoteTab>> {
        val mid = settings.credentials.first().dedeUserId
        if (!forceRefresh) {
            mutex.withLock {
                if (replyCachedMid == mid && replyCachedTabs != null) {
                    return BiliResult.Ok(replyCachedTabs.orEmpty())
                }
            }
        }
        // 接口事实见 notes/comment-toview-history.md §1.9。
        val result: BiliResult<ReplyEmotePanelDto> = client.getData(
            url = "${BiliConstants.WEB_HOST}/x/emote/user/panel/web",
            params = mapOf(
                "business" to "reply",
                "web_location" to REPLY_EMOTE_WEB_LOCATION,
            ),
        )
        return result.map { panel ->
            panel.packages.mapNotNull { it.toEmoteTab() }
        }.also { mapped ->
            if (mapped is BiliResult.Ok) {
                mutex.withLock {
                    replyCachedMid = mid
                    replyCachedTabs = mapped.value
                }
            }
        }
    }

    suspend fun loadLiveEmotes(roomId: Long, forceRefresh: Boolean = false): BiliResult<List<EmoteTab>> {
        val mid = settings.credentials.first().dedeUserId
        val cacheKey = mid to roomId
        if (!forceRefresh) {
            mutex.withLock {
                liveCache[cacheKey]?.let { return BiliResult.Ok(it) }
            }
        }
        // 接口事实见 notes/live.md §4.1。
        val result: BiliResult<LiveEmotePanelDto> = client.getData(
            url = "${BiliConstants.LIVE_HOST}/xlive/web-ucenter/v2/emoticon/GetEmoticons",
            params = mapOf(
                "platform" to "pc",
                "room_id" to roomId.toString(),
            ),
            referer = "${BiliConstants.LIVE_REFERER}/$roomId",
        )
        return result.map { panel ->
            panel.packages.mapNotNull { it.toEmoteTab() }
        }.also { mapped ->
            if (mapped is BiliResult.Ok) {
                mutex.withLock {
                    if (liveCache.size >= MAX_LIVE_ROOM_CACHE && cacheKey !in liveCache) {
                        liveCache.keys.firstOrNull()?.let(liveCache::remove)
                    }
                    liveCache[cacheKey] = mapped.value
                }
            }
        }
    }

    private companion object {
        const val REPLY_EMOTE_WEB_LOCATION = "333.1245"
        const val MAX_LIVE_ROOM_CACHE = 6
    }
}

val LocalEmoteRepository = staticCompositionLocalOf<EmoteRepository?> { null }

private fun ReplyEmotePackageDto.toEmoteTab(): EmoteTab? {
    if (emote.isEmpty()) return null
    val isKaomoji = type == KAOMOJI_PACKAGE_TYPE
    val large = !isKaomoji && emote.any { (it.meta?.size ?: 1) >= 2 }
    val kind = when {
        isKaomoji -> EmoteTabKind.Kaomoji
        large -> EmoteTabKind.LargeImage
        else -> EmoteTabKind.SmallImage
    }
    val items = emote.mapIndexedNotNull { index, item ->
        if (item.text.isEmpty()) return@mapIndexedNotNull null
        val rawUrl = listOf(item.webpUrl, item.gifUrl, item.url).firstOrNull(String::isNotBlank).orEmpty()
        if (!isKaomoji && rawUrl.isEmpty()) return@mapIndexedNotNull null
        val displayLabel = item.meta?.alias?.takeIf { it.isNotBlank() }
            ?: item.text.removePrefix("[").removeSuffix("]")
        EmoteEntry(
            key = "${id}_${item.id}_$index",
            label = if (isKaomoji) item.text else displayLabel,
            imageUrl = if (isKaomoji) "" else rawUrl.toHttpsUrl(),
            action = EmoteAction.InsertText(item.text),
        )
    }
    if (items.isEmpty()) return null
    return EmoteTab(
        id = "reply-$id-$type",
        name = text,
        iconUrl = url.toHttpsUrl(),
        kind = kind,
        items = items,
    )
}

private fun LiveEmotePackageDto.toEmoteTab(): EmoteTab? {
    if (emoticons.isEmpty()) return null
    val isInlineText = pkgType == LIVE_INLINE_PACKAGE_TYPE
    val kind = if (isInlineText) EmoteTabKind.SmallImage else EmoteTabKind.LargeImage
    val items = emoticons.mapIndexedNotNull { index, item ->
        if (item.url.isEmpty()) return@mapIndexedNotNull null
        val action = if (isInlineText) {
            if (item.emoji.isEmpty()) return@mapIndexedNotNull null
            EmoteAction.InsertText(item.emoji)
        } else {
            if (item.emoticonUnique.isEmpty()) return@mapIndexedNotNull null
            EmoteAction.SendLiveSticker(item.emoticonUnique)
        }
        val unlocked = item.perm != 0
        val lockHint = when {
            unlocked -> ""
            item.unlockShowText.isNotBlank() -> item.unlockShowText
            item.unlockNeedLevel > 0 -> "Lv.${item.unlockNeedLevel}"
            else -> ""
        }
        EmoteEntry(
            key = "${pkgId}_${item.emoticonUnique.ifEmpty { item.emoji }}_$index",
            label = item.emoji.removePrefix("[").removeSuffix("]"),
            imageUrl = item.url.toHttpsUrl(),
            action = action,
            enabled = unlocked,
            lockHint = lockHint,
        )
    }
    if (items.isEmpty()) return null
    return EmoteTab(
        id = "live-$pkgId-$pkgType",
        name = pkgName,
        iconUrl = currentCover.toHttpsUrl(),
        kind = kind,
        items = items,
    )
}

private const val KAOMOJI_PACKAGE_TYPE = 4
private const val LIVE_INLINE_PACKAGE_TYPE = 3
