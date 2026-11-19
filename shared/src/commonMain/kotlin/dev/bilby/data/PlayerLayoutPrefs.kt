package dev.bilby.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val PLAYER_LAYOUT_PREFS_VERSION = 1
const val MIN_SEEK_SECONDS = 1
const val MAX_SEEK_SECONDS = 300
const val DEFAULT_SEEK_SECONDS = 15
const val DEFAULT_DOUBLE_TAP_SEEK_SECONDS = 10
val SEEK_SECONDS_PRESETS = listOf(5, 10, 15, 30)

enum class PlayerFormFactor(val isTouch: Boolean, val isFullscreen: Boolean) {
    TouchEmbedded(isTouch = true, isFullscreen = false),
    TouchFullscreen(isTouch = true, isFullscreen = true),
    PointerEmbedded(isTouch = false, isFullscreen = false),
    PointerFullscreen(isTouch = false, isFullscreen = true),
    ;

    companion object {
        fun of(isTouch: Boolean, isFullscreen: Boolean): PlayerFormFactor = when {
            isTouch && isFullscreen -> TouchFullscreen
            isTouch -> TouchEmbedded
            isFullscreen -> PointerFullscreen
            else -> PointerEmbedded
        }
    }
}

enum class PlayerBarSlot {
    Top,
    Bottom,
    ;

    fun other(): PlayerBarSlot = if (this == Top) Bottom else Top
}

enum class CenterSlotPosition {
    OuterLeft,
    InnerLeft,
    Center,
    InnerRight,
    OuterRight,
    ;

    fun symmetricOpposite(): CenterSlotPosition = when (this) {
        OuterLeft -> OuterRight
        InnerLeft -> InnerRight
        Center -> Center
        InnerRight -> InnerLeft
        OuterRight -> OuterLeft
    }
}

@Serializable
enum class PlayerControlId {
    Back,
    Title,
    SystemStatus,
    PlayPause,
    Previous,
    Next,
    SeekBackward,
    SeekForward,
    TimeLabel,
    Volume,
    DanmakuToggle,
    DanmakuInput,
    Speed,
    Quality,
    Audio,
    Subtitle,
    SleepTimer,
    Pip,
    Listen,
    Share,
    Settings,
    Lock,
    Fullscreen,
}

@Immutable
@Serializable
sealed interface PlayerPanelItem {
    @Immutable
    @Serializable
    @SerialName("control")
    data class Control(val id: PlayerControlId) : PlayerPanelItem

    @Immutable
    @Serializable
    @SerialName("spacer")
    data object Spacer : PlayerPanelItem
}

@Immutable
@Serializable
sealed interface CenterSlotAction {
    @Immutable
    @Serializable
    @SerialName("none")
    data object None : CenterSlotAction

    @Immutable
    @Serializable
    @SerialName("previous")
    data object Previous : CenterSlotAction

    @Immutable
    @Serializable
    @SerialName("next")
    data object Next : CenterSlotAction

    @Immutable
    @Serializable
    @SerialName("seek_backward")
    data class SeekBackward(val seconds: Int = DEFAULT_SEEK_SECONDS) : CenterSlotAction

    @Immutable
    @Serializable
    @SerialName("seek_forward")
    data class SeekForward(val seconds: Int = DEFAULT_SEEK_SECONDS) : CenterSlotAction

    fun sanitized(): CenterSlotAction = when (this) {
        None, Previous, Next -> this
        is SeekBackward -> SeekBackward(seconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS))
        is SeekForward -> SeekForward(seconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS))
    }

    fun toControlIdOrNull(): PlayerControlId? = when (this) {
        None -> null
        Previous -> PlayerControlId.Previous
        Next -> PlayerControlId.Next
        is SeekBackward -> PlayerControlId.SeekBackward
        is SeekForward -> PlayerControlId.SeekForward
    }

    fun symmetricOpposite(): CenterSlotAction = when (this) {
        None -> None
        Previous -> Next
        Next -> Previous
        is SeekBackward -> SeekForward(seconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS))
        is SeekForward -> SeekBackward(seconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS))
    }

    fun seekSecondsOrNull(): Int? = when (this) {
        is SeekBackward -> seconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
        is SeekForward -> seconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
        None, Previous, Next -> null
    }
}

@Serializable
enum class CenterSlotScale(val ratio: Float) {
    Large(0.92f),
    Medium(0.78f),
    Small(0.62f),
    ExtraSmall(0.50f),
}

@Immutable
@Serializable
data class CenterSlotConfig(
    val action: CenterSlotAction = CenterSlotAction.None,
    val scale: CenterSlotScale = CenterSlotScale.Medium,
) {
    fun sanitized(): CenterSlotConfig = copy(action = action.sanitized())
}

@Immutable
@Serializable
data class CenterClusterConfig(
    val showCenterPlay: Boolean = true,
    val outerLeft: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
    val innerLeft: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
    val innerRight: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Medium),
    val outerRight: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
) {
    fun slot(position: CenterSlotPosition): CenterSlotConfig = when (position) {
        CenterSlotPosition.OuterLeft -> outerLeft
        CenterSlotPosition.InnerLeft -> innerLeft
        CenterSlotPosition.Center -> CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Large)
        CenterSlotPosition.InnerRight -> innerRight
        CenterSlotPosition.OuterRight -> outerRight
    }

    fun withSlot(position: CenterSlotPosition, config: CenterSlotConfig): CenterClusterConfig = when (position) {
        CenterSlotPosition.OuterLeft -> copy(outerLeft = config.sanitized())
        CenterSlotPosition.InnerLeft -> copy(innerLeft = config.sanitized())
        CenterSlotPosition.Center -> this
        CenterSlotPosition.InnerRight -> copy(innerRight = config.sanitized())
        CenterSlotPosition.OuterRight -> copy(outerRight = config.sanitized())
    }

    fun hasControl(id: PlayerControlId): Boolean {
        if (id == PlayerControlId.PlayPause) return showCenterPlay
        return outerLeft.action.toControlIdOrNull() == id ||
            innerLeft.action.toControlIdOrNull() == id ||
            innerRight.action.toControlIdOrNull() == id ||
            outerRight.action.toControlIdOrNull() == id
    }

    fun removeControl(id: PlayerControlId): CenterClusterConfig {
        if (id == PlayerControlId.PlayPause) return copy(showCenterPlay = false)
        return copy(
            outerLeft = if (outerLeft.action.toControlIdOrNull() == id) outerLeft.copy(action = CenterSlotAction.None) else outerLeft,
            innerLeft = if (innerLeft.action.toControlIdOrNull() == id) innerLeft.copy(action = CenterSlotAction.None) else innerLeft,
            innerRight = if (innerRight.action.toControlIdOrNull() == id) innerRight.copy(action = CenterSlotAction.None) else innerRight,
            outerRight = if (outerRight.action.toControlIdOrNull() == id) outerRight.copy(action = CenterSlotAction.None) else outerRight,
        )
    }
}

@Immutable
@Serializable
data class PlayerFormFactorLayout(
    val center: CenterClusterConfig,
    val topBar: List<PlayerPanelItem>,
    val bottomBar: List<PlayerPanelItem>,
) {
    fun itemsFor(bar: PlayerBarSlot): List<PlayerPanelItem> = when (bar) {
        PlayerBarSlot.Top -> topBar
        PlayerBarSlot.Bottom -> bottomBar
    }

    fun hasInTopBar(id: PlayerControlId): Boolean =
        topBar.any { it is PlayerPanelItem.Control && it.id == id }

    fun hasInBottomBar(id: PlayerControlId): Boolean =
        bottomBar.any { it is PlayerPanelItem.Control && it.id == id }

    fun hasControl(id: PlayerControlId): Boolean =
        center.hasControl(id) || hasInTopBar(id) || hasInBottomBar(id)

    fun removeControl(id: PlayerControlId): PlayerFormFactorLayout = copy(
        center = center.removeControl(id),
        topBar = topBar.filterNot { it is PlayerPanelItem.Control && it.id == id },
        bottomBar = bottomBar.filterNot { it is PlayerPanelItem.Control && it.id == id },
    )

    fun withCenterPlay(show: Boolean, isFullscreen: Boolean): PlayerFormFactorLayout {
        val cleared = if (show) removeControl(PlayerControlId.PlayPause) else this
        return cleared.copy(center = cleared.center.copy(showCenterPlay = show)).sanitize(isFullscreen)
    }

    fun withCenterSlot(
        position: CenterSlotPosition,
        config: CenterSlotConfig,
        isFullscreen: Boolean,
    ): PlayerFormFactorLayout {
        if (position == CenterSlotPosition.Center) return this
        val sanitizedSlot = config.sanitized()
        val controlId = sanitizedSlot.action.toControlIdOrNull()
        val cleared = if (controlId != null) removeControl(controlId) else this
        return cleared.copy(center = cleared.center.withSlot(position, sanitizedSlot)).sanitize(isFullscreen)
    }

    fun withSymmetricCenterSlot(
        position: CenterSlotPosition,
        isFullscreen: Boolean,
    ): PlayerFormFactorLayout {
        if (position == CenterSlotPosition.Center) return this
        val source = center.slot(position)
        val opposite = position.symmetricOpposite()
        val targetConfig = CenterSlotConfig(
            action = source.action.symmetricOpposite(),
            scale = source.scale,
        )
        return withCenterSlot(opposite, targetConfig, isFullscreen)
    }

    fun addToBar(
        bar: PlayerBarSlot,
        item: PlayerPanelItem,
        isFullscreen: Boolean,
    ): PlayerFormFactorLayout {
        val base = if (item is PlayerPanelItem.Control) removeControl(item.id) else this
        val updated = when (bar) {
            PlayerBarSlot.Top -> base.copy(topBar = base.topBar + item)
            PlayerBarSlot.Bottom -> base.copy(bottomBar = base.bottomBar + item)
        }
        return updated.sanitize(isFullscreen)
    }

    fun moveBarItem(
        bar: PlayerBarSlot,
        fromIndex: Int,
        toIndex: Int,
        isFullscreen: Boolean,
    ): PlayerFormFactorLayout {
        val list = itemsFor(bar)
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return this
        // 全屏顶栏的返回键固定在最左端。
        if (isFullscreen && bar == PlayerBarSlot.Top) {
            val moving = list[fromIndex]
            val target = list[toIndex]
            if (moving == PlayerPanelItem.Control(PlayerControlId.Back) ||
                target == PlayerPanelItem.Control(PlayerControlId.Back)
            ) {
                return this
            }
        }
        val mutable = list.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        return when (bar) {
            PlayerBarSlot.Top -> copy(topBar = mutable)
            PlayerBarSlot.Bottom -> copy(bottomBar = mutable)
        }.sanitize(isFullscreen)
    }

    fun moveBarItemToOtherBar(
        fromBar: PlayerBarSlot,
        index: Int,
        isFullscreen: Boolean,
    ): PlayerFormFactorLayout {
        val list = itemsFor(fromBar)
        if (index !in list.indices) return this
        val item = list[index]
        if (item is PlayerPanelItem.Control && item.id == PlayerControlId.Back) return this
        val removed = list.toMutableList().apply { removeAt(index) }
        val updated = when (fromBar) {
            PlayerBarSlot.Top -> copy(topBar = removed, bottomBar = bottomBar + item)
            PlayerBarSlot.Bottom -> copy(bottomBar = removed, topBar = topBar + item)
        }
        return updated.sanitize(isFullscreen)
    }

    fun canRemoveBarItem(bar: PlayerBarSlot, index: Int, isFullscreen: Boolean): Boolean {
        val list = itemsFor(bar)
        val item = list.getOrNull(index) ?: return false
        if (item is PlayerPanelItem.Spacer) return true
        val id = (item as PlayerPanelItem.Control).id
        return when (id) {
            PlayerControlId.Back -> !isFullscreen
            PlayerControlId.Fullscreen -> false
            PlayerControlId.PlayPause -> center.showCenterPlay
            else -> true
        }
    }

    fun removeBarItem(
        bar: PlayerBarSlot,
        index: Int,
        isFullscreen: Boolean,
    ): PlayerFormFactorLayout {
        if (!canRemoveBarItem(bar, index, isFullscreen)) return this
        val list = itemsFor(bar)
        val updated = list.toMutableList().apply { removeAt(index) }
        return when (bar) {
            PlayerBarSlot.Top -> copy(topBar = updated)
            PlayerBarSlot.Bottom -> copy(bottomBar = updated)
        }.sanitize(isFullscreen)
    }

    /**
     * 强校验与修复护栏：
     * 1. 秒数限制在 [MIN_SEEK_SECONDS]..[MAX_SEEK_SECONDS]；
     * 2. 单一规范入口：同一 [PlayerControlId] 在中央簇、顶栏、底栏间仅出现一次；
     * 3. 基本可用性：全屏顶栏保留返回键，至少存在一处播放/暂停，且保留全屏切换键。
     */
    fun sanitize(isFullscreen: Boolean): PlayerFormFactorLayout {
        val seen = mutableSetOf<PlayerControlId>()
        if (center.showCenterPlay) {
            seen += PlayerControlId.PlayPause
        }
        fun sanitizeSlot(slot: CenterSlotConfig): CenterSlotConfig {
            val clamped = slot.sanitized()
            val id = clamped.action.toControlIdOrNull() ?: return clamped
            return if (seen.add(id)) clamped else clamped.copy(action = CenterSlotAction.None)
        }
        val cleanCenter = CenterClusterConfig(
            showCenterPlay = center.showCenterPlay,
            innerLeft = sanitizeSlot(center.innerLeft),
            innerRight = sanitizeSlot(center.innerRight),
            outerLeft = sanitizeSlot(center.outerLeft),
            outerRight = sanitizeSlot(center.outerRight),
        )

        val cleanTop = mutableListOf<PlayerPanelItem>()
        if (isFullscreen) {
            seen += PlayerControlId.Back
            cleanTop += PlayerPanelItem.Control(PlayerControlId.Back)
        }
        for (item in topBar) {
            when (item) {
                PlayerPanelItem.Spacer -> cleanTop += item
                is PlayerPanelItem.Control -> {
                    if (!isFullscreen && (item.id == PlayerControlId.Back || item.id == PlayerControlId.Title)) {
                        continue
                    }
                    if (seen.add(item.id)) {
                        cleanTop += item
                    }
                }
            }
        }

        val cleanBottom = mutableListOf<PlayerPanelItem>()
        for (item in bottomBar) {
            when (item) {
                PlayerPanelItem.Spacer -> cleanBottom += item
                is PlayerPanelItem.Control -> {
                    if (item.id == PlayerControlId.Back) continue
                    if (seen.add(item.id)) {
                        cleanBottom += item
                    }
                }
            }
        }

        if (PlayerControlId.PlayPause !in seen) {
            cleanBottom.add(0, PlayerPanelItem.Control(PlayerControlId.PlayPause))
            seen += PlayerControlId.PlayPause
        }
        if (PlayerControlId.Fullscreen !in seen) {
            cleanBottom += PlayerPanelItem.Control(PlayerControlId.Fullscreen)
            seen += PlayerControlId.Fullscreen
        }

        return PlayerFormFactorLayout(
            center = cleanCenter,
            topBar = cleanTop,
            bottomBar = cleanBottom,
        )
    }
}

@Serializable
enum class ListenCoverStyle {
    VinylDisc,
    StaticCover,
}

@Serializable
enum class ListenSideAction {
    Episode,
    SeekDelta,
    None,
}

@Serializable
enum class ListenQuickChip {
    Speed,
    SleepTimer,
    Quality,
    Subtitle,
}

@Immutable
@Serializable
data class ListenLayoutConfig(
    val coverStyle: ListenCoverStyle = ListenCoverStyle.VinylDisc,
    val sideAction: ListenSideAction = ListenSideAction.Episode,
    val visibleChips: List<ListenQuickChip> = ListenQuickChip.entries,
) {
    fun hasChip(chip: ListenQuickChip): Boolean = chip in visibleChips

    fun toggleChip(chip: ListenQuickChip, visible: Boolean): ListenLayoutConfig {
        val updated = if (visible) {
            if (chip in visibleChips) visibleChips else visibleChips + chip
        } else {
            visibleChips.filterNot { it == chip }
        }
        return copy(visibleChips = updated).sanitize()
    }

    fun sanitize(): ListenLayoutConfig = copy(
        visibleChips = visibleChips.distinct(),
    )

    companion object {
        val Default = ListenLayoutConfig(
            coverStyle = ListenCoverStyle.VinylDisc,
            sideAction = ListenSideAction.Episode,
            visibleChips = ListenQuickChip.entries,
        )
    }
}

enum class PlayerLayoutPreset {
    Default,
    Minimal,
    Podcast,
}

@Immutable
@Serializable
data class PlayerLayoutPrefs(
    val version: Int = PLAYER_LAYOUT_PREFS_VERSION,
    val touchEmbedded: PlayerFormFactorLayout = DefaultTouchEmbedded,
    val touchFullscreen: PlayerFormFactorLayout = DefaultTouchFullscreen,
    val pointerEmbedded: PlayerFormFactorLayout = DefaultPointerEmbedded,
    val pointerFullscreen: PlayerFormFactorLayout = DefaultPointerFullscreen,
    val seekButtonSeconds: Int = DEFAULT_SEEK_SECONDS,
    val doubleTapSeekSeconds: Int = DEFAULT_DOUBLE_TAP_SEEK_SECONDS,
    val syncDoubleTapWithSeekButton: Boolean = true,
    val listen: ListenLayoutConfig = ListenLayoutConfig.Default,
) {
    val effectiveDoubleTapSeekSeconds: Int
        get() = (if (syncDoubleTapWithSeekButton) seekButtonSeconds else doubleTapSeekSeconds)
            .coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)

    fun layoutFor(formFactor: PlayerFormFactor): PlayerFormFactorLayout = when (formFactor) {
        PlayerFormFactor.TouchEmbedded -> touchEmbedded
        PlayerFormFactor.TouchFullscreen -> touchFullscreen
        PlayerFormFactor.PointerEmbedded -> pointerEmbedded
        PlayerFormFactor.PointerFullscreen -> pointerFullscreen
    }

    fun updateLayout(
        formFactor: PlayerFormFactor,
        transform: (PlayerFormFactorLayout) -> PlayerFormFactorLayout,
    ): PlayerLayoutPrefs {
        val updated = when (formFactor) {
            PlayerFormFactor.TouchEmbedded -> copy(touchEmbedded = transform(touchEmbedded))
            PlayerFormFactor.TouchFullscreen -> copy(touchFullscreen = transform(touchFullscreen))
            PlayerFormFactor.PointerEmbedded -> copy(pointerEmbedded = transform(pointerEmbedded))
            PlayerFormFactor.PointerFullscreen -> copy(pointerFullscreen = transform(pointerFullscreen))
        }
        return updated.sanitize()
    }

    fun updateListen(
        transform: (ListenLayoutConfig) -> ListenLayoutConfig,
    ): PlayerLayoutPrefs = copy(listen = transform(listen)).sanitize()

    fun sanitize(): PlayerLayoutPrefs = copy(
        version = PLAYER_LAYOUT_PREFS_VERSION,
        touchEmbedded = touchEmbedded.sanitize(isFullscreen = false),
        touchFullscreen = touchFullscreen.sanitize(isFullscreen = true),
        pointerEmbedded = pointerEmbedded.sanitize(isFullscreen = false),
        pointerFullscreen = pointerFullscreen.sanitize(isFullscreen = true),
        seekButtonSeconds = seekButtonSeconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS),
        doubleTapSeekSeconds = doubleTapSeekSeconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS),
        listen = listen.sanitize(),
    )

    companion object {
        private fun c(id: PlayerControlId): PlayerPanelItem = PlayerPanelItem.Control(id)
        private val sp: PlayerPanelItem = PlayerPanelItem.Spacer

        val DefaultTouchCenter = CenterClusterConfig(
            showCenterPlay = true,
            outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
            innerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
            innerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Medium),
            outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
        )

        val DefaultPointerCenter = CenterClusterConfig(
            showCenterPlay = false,
            outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
            innerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Medium),
            innerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Medium),
            outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
        )

        val DefaultTouchEmbedded = PlayerFormFactorLayout(
            center = DefaultTouchCenter,
            topBar = listOf(sp, c(PlayerControlId.Pip), c(PlayerControlId.Share)),
            bottomBar = listOf(
                c(PlayerControlId.TimeLabel),
                sp,
                c(PlayerControlId.DanmakuInput),
                c(PlayerControlId.SleepTimer),
                c(PlayerControlId.Settings),
                c(PlayerControlId.Fullscreen),
            ),
        )

        val DefaultTouchFullscreen = PlayerFormFactorLayout(
            center = DefaultTouchCenter,
            topBar = listOf(
                c(PlayerControlId.Back),
                c(PlayerControlId.Title),
                sp,
                c(PlayerControlId.SystemStatus),
                c(PlayerControlId.Pip),
                c(PlayerControlId.Settings),
            ),
            bottomBar = listOf(
                c(PlayerControlId.TimeLabel),
                c(PlayerControlId.DanmakuInput),
                sp,
                c(PlayerControlId.Speed),
                c(PlayerControlId.Quality),
                c(PlayerControlId.Audio),
                c(PlayerControlId.Subtitle),
                c(PlayerControlId.SleepTimer),
                c(PlayerControlId.DanmakuToggle),
                c(PlayerControlId.Fullscreen),
            ),
        )

        val DefaultPointerEmbedded = PlayerFormFactorLayout(
            center = DefaultPointerCenter,
            topBar = listOf(sp, c(PlayerControlId.Pip), c(PlayerControlId.Share)),
            bottomBar = listOf(
                c(PlayerControlId.PlayPause),
                c(PlayerControlId.Previous),
                c(PlayerControlId.Next),
                c(PlayerControlId.TimeLabel),
                c(PlayerControlId.Volume),
                sp,
                c(PlayerControlId.DanmakuInput),
                c(PlayerControlId.SleepTimer),
                c(PlayerControlId.Settings),
                c(PlayerControlId.Fullscreen),
            ),
        )

        val DefaultPointerFullscreen = PlayerFormFactorLayout(
            center = DefaultPointerCenter,
            topBar = listOf(
                c(PlayerControlId.Back),
                c(PlayerControlId.Title),
                sp,
                c(PlayerControlId.SystemStatus),
                c(PlayerControlId.Pip),
                c(PlayerControlId.Settings),
            ),
            bottomBar = listOf(
                c(PlayerControlId.PlayPause),
                c(PlayerControlId.Previous),
                c(PlayerControlId.Next),
                c(PlayerControlId.TimeLabel),
                c(PlayerControlId.Volume),
                c(PlayerControlId.DanmakuInput),
                sp,
                c(PlayerControlId.Speed),
                c(PlayerControlId.Quality),
                c(PlayerControlId.Audio),
                c(PlayerControlId.Subtitle),
                c(PlayerControlId.SleepTimer),
                c(PlayerControlId.DanmakuToggle),
                c(PlayerControlId.Fullscreen),
            ),
        )

        val Default = PlayerLayoutPrefs(
            version = PLAYER_LAYOUT_PREFS_VERSION,
            touchEmbedded = DefaultTouchEmbedded,
            touchFullscreen = DefaultTouchFullscreen,
            pointerEmbedded = DefaultPointerEmbedded,
            pointerFullscreen = DefaultPointerFullscreen,
            seekButtonSeconds = DEFAULT_SEEK_SECONDS,
            doubleTapSeekSeconds = DEFAULT_DOUBLE_TAP_SEEK_SECONDS,
            syncDoubleTapWithSeekButton = true,
            listen = ListenLayoutConfig.Default,
        ).sanitize()

        val Minimal = PlayerLayoutPrefs(
            version = PLAYER_LAYOUT_PREFS_VERSION,
            touchEmbedded = PlayerFormFactorLayout(
                center = DefaultPointerCenter.copy(showCenterPlay = true),
                topBar = listOf(sp),
                bottomBar = listOf(
                    c(PlayerControlId.TimeLabel),
                    sp,
                    c(PlayerControlId.Settings),
                    c(PlayerControlId.Fullscreen),
                ),
            ),
            touchFullscreen = PlayerFormFactorLayout(
                center = DefaultPointerCenter.copy(showCenterPlay = true),
                topBar = listOf(
                    c(PlayerControlId.Back),
                    c(PlayerControlId.Title),
                    sp,
                    c(PlayerControlId.Settings),
                ),
                bottomBar = listOf(
                    c(PlayerControlId.TimeLabel),
                    sp,
                    c(PlayerControlId.Fullscreen),
                ),
            ),
            pointerEmbedded = PlayerFormFactorLayout(
                center = DefaultPointerCenter,
                topBar = listOf(sp),
                bottomBar = listOf(
                    c(PlayerControlId.PlayPause),
                    c(PlayerControlId.TimeLabel),
                    c(PlayerControlId.Volume),
                    sp,
                    c(PlayerControlId.Settings),
                    c(PlayerControlId.Fullscreen),
                ),
            ),
            pointerFullscreen = PlayerFormFactorLayout(
                center = DefaultPointerCenter,
                topBar = listOf(
                    c(PlayerControlId.Back),
                    c(PlayerControlId.Title),
                    sp,
                    c(PlayerControlId.Settings),
                ),
                bottomBar = listOf(
                    c(PlayerControlId.PlayPause),
                    c(PlayerControlId.TimeLabel),
                    c(PlayerControlId.Volume),
                    sp,
                    c(PlayerControlId.Fullscreen),
                ),
            ),
            seekButtonSeconds = DEFAULT_SEEK_SECONDS,
            doubleTapSeekSeconds = DEFAULT_DOUBLE_TAP_SEEK_SECONDS,
            syncDoubleTapWithSeekButton = true,
            listen = ListenLayoutConfig.Default,
        ).sanitize()

        private val PodcastTouchCenter = CenterClusterConfig(
            showCenterPlay = true,
            outerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Small),
            innerLeft = CenterSlotConfig(CenterSlotAction.SeekBackward(15), CenterSlotScale.Medium),
            innerRight = CenterSlotConfig(CenterSlotAction.SeekForward(15), CenterSlotScale.Medium),
            outerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Small),
        )

        val Podcast = PlayerLayoutPrefs(
            version = PLAYER_LAYOUT_PREFS_VERSION,
            touchEmbedded = PlayerFormFactorLayout(
                center = PodcastTouchCenter,
                topBar = listOf(sp, c(PlayerControlId.Pip), c(PlayerControlId.Share)),
                bottomBar = listOf(
                    c(PlayerControlId.TimeLabel),
                    sp,
                    c(PlayerControlId.Speed),
                    c(PlayerControlId.SleepTimer),
                    c(PlayerControlId.Settings),
                    c(PlayerControlId.Fullscreen),
                ),
            ),
            touchFullscreen = PlayerFormFactorLayout(
                center = PodcastTouchCenter,
                topBar = listOf(
                    c(PlayerControlId.Back),
                    c(PlayerControlId.Title),
                    sp,
                    c(PlayerControlId.SystemStatus),
                    c(PlayerControlId.Settings),
                ),
                bottomBar = listOf(
                    c(PlayerControlId.TimeLabel),
                    sp,
                    c(PlayerControlId.Speed),
                    c(PlayerControlId.SleepTimer),
                    c(PlayerControlId.DanmakuToggle),
                    c(PlayerControlId.Fullscreen),
                ),
            ),
            pointerEmbedded = PlayerFormFactorLayout(
                center = DefaultPointerCenter,
                topBar = listOf(sp, c(PlayerControlId.Pip), c(PlayerControlId.Share)),
                bottomBar = listOf(
                    c(PlayerControlId.PlayPause),
                    c(PlayerControlId.SeekBackward),
                    c(PlayerControlId.SeekForward),
                    c(PlayerControlId.Previous),
                    c(PlayerControlId.Next),
                    c(PlayerControlId.TimeLabel),
                    c(PlayerControlId.Volume),
                    sp,
                    c(PlayerControlId.Speed),
                    c(PlayerControlId.SleepTimer),
                    c(PlayerControlId.Settings),
                    c(PlayerControlId.Fullscreen),
                ),
            ),
            pointerFullscreen = PlayerFormFactorLayout(
                center = DefaultPointerCenter,
                topBar = listOf(
                    c(PlayerControlId.Back),
                    c(PlayerControlId.Title),
                    sp,
                    c(PlayerControlId.SystemStatus),
                    c(PlayerControlId.Settings),
                ),
                bottomBar = listOf(
                    c(PlayerControlId.PlayPause),
                    c(PlayerControlId.SeekBackward),
                    c(PlayerControlId.SeekForward),
                    c(PlayerControlId.Previous),
                    c(PlayerControlId.Next),
                    c(PlayerControlId.TimeLabel),
                    c(PlayerControlId.Volume),
                    sp,
                    c(PlayerControlId.Speed),
                    c(PlayerControlId.SleepTimer),
                    c(PlayerControlId.DanmakuToggle),
                    c(PlayerControlId.Fullscreen),
                ),
            ),
            seekButtonSeconds = 15,
            doubleTapSeekSeconds = 15,
            syncDoubleTapWithSeekButton = true,
            listen = ListenLayoutConfig(
                coverStyle = ListenCoverStyle.VinylDisc,
                sideAction = ListenSideAction.SeekDelta,
                visibleChips = ListenQuickChip.entries,
            ),
        ).sanitize()

        fun preset(preset: PlayerLayoutPreset): PlayerLayoutPrefs = when (preset) {
            PlayerLayoutPreset.Default -> Default
            PlayerLayoutPreset.Minimal -> Minimal
            PlayerLayoutPreset.Podcast -> Podcast
        }
    }
}

private val PlayerLayoutJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun encodePlayerLayoutPrefs(prefs: PlayerLayoutPrefs): String =
    PlayerLayoutJson.encodeToString(PlayerLayoutPrefs.serializer(), prefs.sanitize())

fun decodePlayerLayoutPrefs(raw: String?): PlayerLayoutPrefs {
    if (raw.isNullOrBlank()) return PlayerLayoutPrefs.Default
    return runCatching {
        PlayerLayoutJson.decodeFromString(PlayerLayoutPrefs.serializer(), raw).sanitize()
    }.getOrElse { PlayerLayoutPrefs.Default }
}
