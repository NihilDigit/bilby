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

const val MIN_VERTICAL_ZONE_FRACTION = 0.20f
const val MAX_VERTICAL_ZONE_FRACTION = 0.50f
const val DEFAULT_VERTICAL_ZONE_FRACTION = 0.40f
const val MIN_DOUBLE_TAP_ZONE_FRACTION = 0.15f
const val MAX_DOUBLE_TAP_ZONE_FRACTION = 0.40f
const val DEFAULT_DOUBLE_TAP_ZONE_FRACTION = 0.25f

enum class PlayerLayoutMode(val isFullscreen: Boolean) {
    Embedded(isFullscreen = false),
    Fullscreen(isFullscreen = true),
    ;

    companion object {
        fun of(isLargeOrFullscreen: Boolean): PlayerLayoutMode =
            if (isLargeOrFullscreen) Fullscreen else Embedded
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
    data class Control(
        val id: PlayerControlId,
        val showBackground: Boolean = true,
    ) : PlayerPanelItem

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
    Large(0.78f),
    Medium(0.64f),
    Small(0.52f),
    ExtraSmall(0.42f),
}

@Immutable
@Serializable
data class CenterSlotConfig(
    val action: CenterSlotAction = CenterSlotAction.None,
    val scale: CenterSlotScale = CenterSlotScale.Medium,
    val showBackground: Boolean = true,
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

    /**
     * 当触控中央控件簇激活时，已在中央簇展示的播放/切集/步进键在顶栏与底栏中自动去重隐藏；
     * 键鼠模式或关闭触控中央控件时，栏位中的对应控件正常显示。
     */
    fun isHiddenByCenter(id: PlayerControlId, centerActive: Boolean): Boolean =
        centerActive && center.hasControl(id)

    fun removeFromBars(id: PlayerControlId): PlayerFormFactorLayout = copy(
        topBar = topBar.filterNot { it is PlayerPanelItem.Control && it.id == id },
        bottomBar = bottomBar.filterNot { it is PlayerPanelItem.Control && it.id == id },
    )

    fun withCenterPlay(show: Boolean, isFullscreen: Boolean): PlayerFormFactorLayout =
        copy(center = center.copy(showCenterPlay = show)).sanitize(isFullscreen)

    fun withCenterSlot(
        position: CenterSlotPosition,
        config: CenterSlotConfig,
        isFullscreen: Boolean,
    ): PlayerFormFactorLayout {
        if (position == CenterSlotPosition.Center) return this
        val sanitizedSlot = config.sanitized()
        val controlId = sanitizedSlot.action.toControlIdOrNull()
        val clearedCenter = if (controlId != null) center.removeControl(controlId) else center
        return copy(center = clearedCenter.withSlot(position, sanitizedSlot)).sanitize(isFullscreen)
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
            showBackground = source.showBackground,
        )
        return withCenterSlot(opposite, targetConfig, isFullscreen)
    }

    fun withBarItemBackground(
        bar: PlayerBarSlot,
        index: Int,
        showBackground: Boolean,
        isFullscreen: Boolean,
    ): PlayerFormFactorLayout {
        val list = itemsFor(bar)
        val item = list.getOrNull(index) as? PlayerPanelItem.Control ?: return this
        val mutable = list.toMutableList()
        mutable[index] = item.copy(showBackground = showBackground)
        return when (bar) {
            PlayerBarSlot.Top -> copy(topBar = mutable)
            PlayerBarSlot.Bottom -> copy(bottomBar = mutable)
        }.sanitize(isFullscreen)
    }

    fun addToBar(
        bar: PlayerBarSlot,
        item: PlayerPanelItem,
        isFullscreen: Boolean,
    ): PlayerFormFactorLayout {
        val base = if (item is PlayerPanelItem.Control) removeFromBars(item.id) else this
        val updated = when (bar) {
            PlayerBarSlot.Top -> base.copy(topBar = base.topBar + item)
            PlayerBarSlot.Bottom -> base.copy(bottomBar = base.bottomBar + item)
        }
        return updated.sanitize(isFullscreen)
    }

    fun insertIntoBar(
        bar: PlayerBarSlot,
        index: Int,
        item: PlayerPanelItem,
        isFullscreen: Boolean,
    ): PlayerFormFactorLayout {
        if (item is PlayerPanelItem.Control && item.id == PlayerControlId.Back) return this
        val existingIndexInTarget = if (item is PlayerPanelItem.Control) {
            itemsFor(bar).indexOfFirst { it is PlayerPanelItem.Control && it.id == item.id }
        } else {
            -1
        }
        val base = if (item is PlayerPanelItem.Control) removeFromBars(item.id) else this
        val targetList = base.itemsFor(bar).toMutableList()
        val adjusted = if (existingIndexInTarget in 0 until index) index - 1 else index
        val minIndex = if (
            isFullscreen &&
            bar == PlayerBarSlot.Top &&
            targetList.firstOrNull().let { it is PlayerPanelItem.Control && it.id == PlayerControlId.Back }
        ) {
            1
        } else {
            0
        }
        val clampedIndex = adjusted.coerceIn(minIndex, targetList.size)
        targetList.add(clampedIndex, item)
        val updated = when (bar) {
            PlayerBarSlot.Top -> base.copy(topBar = targetList)
            PlayerBarSlot.Bottom -> base.copy(bottomBar = targetList)
        }
        return updated.sanitize(isFullscreen)
    }

    fun moveBarItemToBarAt(
        fromBar: PlayerBarSlot,
        fromIndex: Int,
        toBar: PlayerBarSlot,
        toIndex: Int,
        isFullscreen: Boolean,
    ): PlayerFormFactorLayout {
        val sourceList = itemsFor(fromBar)
        val item = sourceList.getOrNull(fromIndex) ?: return this
        if (item is PlayerPanelItem.Control && item.id == PlayerControlId.Back) return this
        if (fromBar == toBar) {
            val mutable = sourceList.toMutableList()
            val moved = mutable.removeAt(fromIndex)
            val adjusted = if (fromIndex < toIndex) toIndex - 1 else toIndex
            val minIndex = if (
                isFullscreen &&
                toBar == PlayerBarSlot.Top &&
                mutable.firstOrNull().let { it is PlayerPanelItem.Control && it.id == PlayerControlId.Back }
            ) {
                1
            } else {
                0
            }
            val clamped = adjusted.coerceIn(minIndex, mutable.size)
            mutable.add(clamped, moved)
            return when (toBar) {
                PlayerBarSlot.Top -> copy(topBar = mutable)
                PlayerBarSlot.Bottom -> copy(bottomBar = mutable)
            }.sanitize(isFullscreen)
        }
        val removedSource = sourceList.toMutableList().apply { removeAt(fromIndex) }
        val targetList = itemsFor(toBar).toMutableList()
        val minIndex = if (
            isFullscreen &&
            toBar == PlayerBarSlot.Top &&
            targetList.firstOrNull().let { it is PlayerPanelItem.Control && it.id == PlayerControlId.Back }
        ) {
            1
        } else {
            0
        }
        val clamped = toIndex.coerceIn(minIndex, targetList.size)
        targetList.add(clamped, item)
        val updated = when (fromBar) {
            PlayerBarSlot.Top -> copy(topBar = removedSource, bottomBar = targetList)
            PlayerBarSlot.Bottom -> copy(bottomBar = removedSource, topBar = targetList)
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
            if ((moving is PlayerPanelItem.Control && moving.id == PlayerControlId.Back) ||
                (target is PlayerPanelItem.Control && target.id == PlayerControlId.Back)
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
            PlayerControlId.PlayPause -> false
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
     * 2. 中央簇 4 个侧槽位内去重，顶栏与底栏之间去重；
     * 3. 基本可用性：全屏顶栏保留返回键，栏位中保留播放/暂停（保障键鼠及关闭中央控件时可用）与全屏切换键。
     */
    fun sanitize(isFullscreen: Boolean): PlayerFormFactorLayout {
        val seenCenter = mutableSetOf<PlayerControlId>()
        fun sanitizeSlot(slot: CenterSlotConfig): CenterSlotConfig {
            val clamped = slot.sanitized()
            val id = clamped.action.toControlIdOrNull() ?: return clamped
            return if (seenCenter.add(id)) clamped else clamped.copy(action = CenterSlotAction.None)
        }
        val cleanCenter = CenterClusterConfig(
            showCenterPlay = center.showCenterPlay,
            innerLeft = sanitizeSlot(center.innerLeft),
            innerRight = sanitizeSlot(center.innerRight),
            outerLeft = sanitizeSlot(center.outerLeft),
            outerRight = sanitizeSlot(center.outerRight),
        )

        val seenBars = mutableSetOf<PlayerControlId>()
        val cleanTop = mutableListOf<PlayerPanelItem>()
        if (isFullscreen) {
            val existingBack = topBar.firstOrNull {
                it is PlayerPanelItem.Control && it.id == PlayerControlId.Back
            } as? PlayerPanelItem.Control
            seenBars += PlayerControlId.Back
            cleanTop += existingBack ?: PlayerPanelItem.Control(PlayerControlId.Back)
        }
        for (item in topBar) {
            when (item) {
                PlayerPanelItem.Spacer -> cleanTop += item
                is PlayerPanelItem.Control -> {
                    if (!isFullscreen && (item.id == PlayerControlId.Back || item.id == PlayerControlId.Title)) {
                        continue
                    }
                    if (seenBars.add(item.id)) {
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
                    if (seenBars.add(item.id)) {
                        cleanBottom += item
                    }
                }
            }
        }

        if (PlayerControlId.PlayPause !in seenBars) {
            cleanBottom.add(0, PlayerPanelItem.Control(PlayerControlId.PlayPause))
            seenBars += PlayerControlId.PlayPause
        }
        if (PlayerControlId.Fullscreen !in seenBars) {
            cleanBottom += PlayerPanelItem.Control(PlayerControlId.Fullscreen)
            seenBars += PlayerControlId.Fullscreen
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
    val outerLeft: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
    val innerLeft: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
    val innerRight: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Medium),
    val outerRight: CenterSlotConfig = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
    val visibleChips: List<ListenQuickChip> = ListenQuickChip.entries,
    // 兼容旧版 JSON 的单侧动作字段；仅在读取旧配置时迁移为 4 槽位并清零。
    val sideAction: ListenSideAction? = null,
) {
    fun slot(position: CenterSlotPosition): CenterSlotConfig = when (position) {
        CenterSlotPosition.OuterLeft -> outerLeft
        CenterSlotPosition.InnerLeft -> innerLeft
        CenterSlotPosition.Center -> CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Large)
        CenterSlotPosition.InnerRight -> innerRight
        CenterSlotPosition.OuterRight -> outerRight
    }

    fun withSlot(position: CenterSlotPosition, config: CenterSlotConfig): ListenLayoutConfig {
        if (position == CenterSlotPosition.Center) return this
        val sanitizedSlot = config.sanitized()
        val id = sanitizedSlot.action.toControlIdOrNull()
        fun clearIfSame(existing: CenterSlotConfig): CenterSlotConfig =
            if (id != null && existing.action.toControlIdOrNull() == id) {
                existing.copy(action = CenterSlotAction.None)
            } else {
                existing
            }
        val cleared = copy(
            outerLeft = clearIfSame(outerLeft),
            innerLeft = clearIfSame(innerLeft),
            innerRight = clearIfSame(innerRight),
            outerRight = clearIfSame(outerRight),
            sideAction = null,
        )
        val updated = when (position) {
            CenterSlotPosition.OuterLeft -> cleared.copy(outerLeft = sanitizedSlot)
            CenterSlotPosition.InnerLeft -> cleared.copy(innerLeft = sanitizedSlot)
            CenterSlotPosition.Center -> cleared
            CenterSlotPosition.InnerRight -> cleared.copy(innerRight = sanitizedSlot)
            CenterSlotPosition.OuterRight -> cleared.copy(outerRight = sanitizedSlot)
        }
        return updated.sanitize()
    }

    fun withSymmetricSlot(position: CenterSlotPosition): ListenLayoutConfig {
        if (position == CenterSlotPosition.Center) return this
        val source = slot(position)
        val opposite = position.symmetricOpposite()
        val targetConfig = CenterSlotConfig(
            action = source.action.symmetricOpposite(),
            scale = source.scale,
            showBackground = source.showBackground,
        )
        return withSlot(opposite, targetConfig)
    }

    fun hasChip(chip: ListenQuickChip): Boolean = chip in visibleChips

    fun toggleChip(chip: ListenQuickChip, visible: Boolean): ListenLayoutConfig {
        val updated = if (visible) {
            if (chip in visibleChips) visibleChips else visibleChips + chip
        } else {
            visibleChips.filterNot { it == chip }
        }
        return copy(visibleChips = updated, sideAction = null).sanitize()
    }

    fun sanitize(fallbackSeekSeconds: Int = DEFAULT_SEEK_SECONDS): ListenLayoutConfig {
        val migrated = when (sideAction) {
            ListenSideAction.Episode -> copy(
                outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
                innerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
                innerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Medium),
                outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
                sideAction = null,
            )
            ListenSideAction.SeekDelta -> {
                val sec = fallbackSeekSeconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
                copy(
                    outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
                    innerLeft = CenterSlotConfig(CenterSlotAction.SeekBackward(sec), CenterSlotScale.Medium),
                    innerRight = CenterSlotConfig(CenterSlotAction.SeekForward(sec), CenterSlotScale.Medium),
                    outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
                    sideAction = null,
                )
            }
            ListenSideAction.None -> copy(
                outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
                innerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Medium),
                innerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Medium),
                outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
                sideAction = null,
            )
            null -> this
        }

        val seen = mutableSetOf<PlayerControlId>()
        fun sanitizeSlot(slot: CenterSlotConfig): CenterSlotConfig {
            val clamped = slot.sanitized()
            val id = clamped.action.toControlIdOrNull() ?: return clamped
            return if (seen.add(id)) clamped else clamped.copy(action = CenterSlotAction.None)
        }
        val cleanInnerLeft = sanitizeSlot(migrated.innerLeft)
        val cleanInnerRight = sanitizeSlot(migrated.innerRight)
        val cleanOuterLeft = sanitizeSlot(migrated.outerLeft)
        val cleanOuterRight = sanitizeSlot(migrated.outerRight)

        return migrated.copy(
            outerLeft = cleanOuterLeft,
            innerLeft = cleanInnerLeft,
            innerRight = cleanInnerRight,
            outerRight = cleanOuterRight,
            visibleChips = migrated.visibleChips.distinct(),
            sideAction = null,
        )
    }

    companion object {
        val Default = ListenLayoutConfig(
            coverStyle = ListenCoverStyle.VinylDisc,
            outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
            innerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
            innerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Medium),
            outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
            visibleChips = ListenQuickChip.entries,
        )
    }
}

@Serializable
enum class VerticalSwipeAction {
    Brightness,
    Volume,
    Episode,
    None,
}

@Serializable
enum class SwipeSeekSensitivity(val spanMultiplier: Float) {
    Low(0.6f),
    Standard(1.0f),
    High(1.6f),
}

@Immutable
@Serializable
data class PlayerGestureConfig(
    val leftVerticalSwipe: VerticalSwipeAction = VerticalSwipeAction.Brightness,
    val rightVerticalSwipe: VerticalSwipeAction = VerticalSwipeAction.Volume,
    val verticalZoneFraction: Float = DEFAULT_VERTICAL_ZONE_FRACTION,
    val swipeSeekSensitivity: SwipeSeekSensitivity = SwipeSeekSensitivity.Standard,
    val swipeSeekCancelZone: Boolean = true,
    val doubleTapZoneFraction: Float = DEFAULT_DOUBLE_TAP_ZONE_FRACTION,
) {
    fun sanitize(): PlayerGestureConfig = copy(
        verticalZoneFraction = if (verticalZoneFraction.isFinite()) {
            verticalZoneFraction.coerceIn(MIN_VERTICAL_ZONE_FRACTION, MAX_VERTICAL_ZONE_FRACTION)
        } else {
            DEFAULT_VERTICAL_ZONE_FRACTION
        },
        doubleTapZoneFraction = if (doubleTapZoneFraction.isFinite()) {
            doubleTapZoneFraction.coerceIn(MIN_DOUBLE_TAP_ZONE_FRACTION, MAX_DOUBLE_TAP_ZONE_FRACTION)
        } else {
            DEFAULT_DOUBLE_TAP_ZONE_FRACTION
        },
    )

    companion object {
        val Default = PlayerGestureConfig()
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
    val embedded: PlayerFormFactorLayout = DefaultEmbedded,
    val fullscreen: PlayerFormFactorLayout = DefaultFullscreen,
    val showCenterControlsOnTouch: Boolean = true,
    val seekButtonSeconds: Int = DEFAULT_SEEK_SECONDS,
    val doubleTapSeekSeconds: Int = DEFAULT_DOUBLE_TAP_SEEK_SECONDS,
    val syncDoubleTapWithSeekButton: Boolean = true,
    val listen: ListenLayoutConfig = ListenLayoutConfig.Default,
    val gestures: PlayerGestureConfig = PlayerGestureConfig.Default,
) {
    val effectiveDoubleTapSeekSeconds: Int
        get() = (if (syncDoubleTapWithSeekButton) seekButtonSeconds else doubleTapSeekSeconds)
            .coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)

    fun isCenterControlsActive(isTouch: Boolean): Boolean = isTouch && showCenterControlsOnTouch

    fun layoutFor(mode: PlayerLayoutMode): PlayerFormFactorLayout = when (mode) {
        PlayerLayoutMode.Embedded -> embedded
        PlayerLayoutMode.Fullscreen -> fullscreen
    }

    fun layoutFor(isLargeOrFullscreen: Boolean): PlayerFormFactorLayout =
        layoutFor(PlayerLayoutMode.of(isLargeOrFullscreen))

    fun updateLayout(
        mode: PlayerLayoutMode,
        transform: (PlayerFormFactorLayout) -> PlayerFormFactorLayout,
    ): PlayerLayoutPrefs {
        val updated = when (mode) {
            PlayerLayoutMode.Embedded -> copy(embedded = transform(embedded))
            PlayerLayoutMode.Fullscreen -> copy(fullscreen = transform(fullscreen))
        }
        return updated.sanitize()
    }

    fun updateListen(
        transform: (ListenLayoutConfig) -> ListenLayoutConfig,
    ): PlayerLayoutPrefs = copy(listen = transform(listen)).sanitize()

    fun updateGestures(
        transform: (PlayerGestureConfig) -> PlayerGestureConfig,
    ): PlayerLayoutPrefs = copy(gestures = transform(gestures)).sanitize()

    fun sanitize(): PlayerLayoutPrefs {
        val clampedSeek = seekButtonSeconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
        val clampedDoubleTap = doubleTapSeekSeconds.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
        return copy(
            version = PLAYER_LAYOUT_PREFS_VERSION,
            embedded = embedded.sanitize(isFullscreen = false),
            fullscreen = fullscreen.sanitize(isFullscreen = true),
            seekButtonSeconds = clampedSeek,
            doubleTapSeekSeconds = clampedDoubleTap,
            listen = listen.sanitize(fallbackSeekSeconds = clampedSeek),
            gestures = gestures.sanitize(),
        )
    }

    companion object {
        private fun c(id: PlayerControlId, showBackground: Boolean = true): PlayerPanelItem =
            PlayerPanelItem.Control(id, showBackground)

        private val sp: PlayerPanelItem = PlayerPanelItem.Spacer

        val DefaultCenter = CenterClusterConfig(
            showCenterPlay = true,
            outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
            innerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
            innerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Medium),
            outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
        )

        val MinimalCenter = CenterClusterConfig(
            showCenterPlay = true,
            outerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
            innerLeft = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Medium),
            innerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Medium),
            outerRight = CenterSlotConfig(CenterSlotAction.None, CenterSlotScale.Small),
        )

        private val PodcastCenter = CenterClusterConfig(
            showCenterPlay = true,
            outerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Small),
            innerLeft = CenterSlotConfig(CenterSlotAction.SeekBackward(15), CenterSlotScale.Medium),
            innerRight = CenterSlotConfig(CenterSlotAction.SeekForward(15), CenterSlotScale.Medium),
            outerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Small),
        )

        val DefaultEmbedded = PlayerFormFactorLayout(
            center = DefaultCenter,
            topBar = listOf(
                sp,
                c(PlayerControlId.Pip),
                c(PlayerControlId.SleepTimer),
                c(PlayerControlId.Share),
            ),
            bottomBar = listOf(
                c(PlayerControlId.PlayPause),
                c(PlayerControlId.TimeLabel),
                c(PlayerControlId.DanmakuToggle),
                c(PlayerControlId.DanmakuInput),
                c(PlayerControlId.Volume),
                c(PlayerControlId.Quality),
                c(PlayerControlId.Settings),
                c(PlayerControlId.Fullscreen),
            ),
        )

        val DefaultFullscreen = PlayerFormFactorLayout(
            center = DefaultCenter,
            topBar = listOf(
                c(PlayerControlId.Back),
                c(PlayerControlId.Title),
                sp,
                c(PlayerControlId.SystemStatus, showBackground = false),
                c(PlayerControlId.Pip),
                c(PlayerControlId.SleepTimer),
                c(PlayerControlId.Settings),
            ),
            bottomBar = listOf(
                c(PlayerControlId.PlayPause),
                c(PlayerControlId.TimeLabel),
                c(PlayerControlId.DanmakuToggle),
                c(PlayerControlId.DanmakuInput),
                c(PlayerControlId.Volume),
                c(PlayerControlId.Speed),
                c(PlayerControlId.Quality),
                c(PlayerControlId.Audio),
                c(PlayerControlId.Subtitle),
                c(PlayerControlId.Fullscreen),
            ),
        )

        val Default = PlayerLayoutPrefs(
            version = PLAYER_LAYOUT_PREFS_VERSION,
            embedded = DefaultEmbedded,
            fullscreen = DefaultFullscreen,
            showCenterControlsOnTouch = true,
            seekButtonSeconds = DEFAULT_SEEK_SECONDS,
            doubleTapSeekSeconds = DEFAULT_DOUBLE_TAP_SEEK_SECONDS,
            syncDoubleTapWithSeekButton = true,
            listen = ListenLayoutConfig.Default,
        ).sanitize()

        val Minimal = PlayerLayoutPrefs(
            version = PLAYER_LAYOUT_PREFS_VERSION,
            embedded = PlayerFormFactorLayout(
                center = MinimalCenter,
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
            fullscreen = PlayerFormFactorLayout(
                center = MinimalCenter,
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
            showCenterControlsOnTouch = true,
            seekButtonSeconds = DEFAULT_SEEK_SECONDS,
            doubleTapSeekSeconds = DEFAULT_DOUBLE_TAP_SEEK_SECONDS,
            syncDoubleTapWithSeekButton = true,
            listen = ListenLayoutConfig.Default,
        ).sanitize()

        val Podcast = PlayerLayoutPrefs(
            version = PLAYER_LAYOUT_PREFS_VERSION,
            embedded = PlayerFormFactorLayout(
                center = PodcastCenter,
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
            fullscreen = PlayerFormFactorLayout(
                center = PodcastCenter,
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
            showCenterControlsOnTouch = true,
            seekButtonSeconds = 15,
            doubleTapSeekSeconds = 15,
            syncDoubleTapWithSeekButton = true,
            listen = ListenLayoutConfig(
                coverStyle = ListenCoverStyle.VinylDisc,
                outerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Small),
                innerLeft = CenterSlotConfig(CenterSlotAction.SeekBackward(15), CenterSlotScale.Medium),
                innerRight = CenterSlotConfig(CenterSlotAction.SeekForward(15), CenterSlotScale.Medium),
                outerRight = CenterSlotConfig(CenterSlotAction.Next, CenterSlotScale.Small),
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
