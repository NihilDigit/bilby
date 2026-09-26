package dev.bilby.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.contains
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/**
 * 宽窗口里列表页与它打开的详情并排:私信会话列表在左、对话在右。
 *
 * **两栏的状态就是返回栈本身**,不是列表页自己记一个选中项。栈是 [列表, 详情] 时,宽窗口并排画,
 * 窄窗口照旧只画详情,返回回到列表;转屏、拖窄窗口都不用把"选中了谁"翻译成一次导航。
 *
 * 场景的 key 取列表那一条的 contentKey:只有详情那一栏在换(打开、换一个、关掉)时 NavDisplay
 * 认作同一个场景,只重组,不把整页滑出去再滑进来。
 */
private class ListDetailScene(
    override val key: Any,
    override val previousEntries: List<NavEntry<NavKey>>,
    private val listEntry: NavEntry<NavKey>,
    private val detailEntry: NavEntry<NavKey>?,
    private val placeholder: @Composable () -> Unit,
) : Scene<NavKey> {
    override val entries: List<NavEntry<NavKey>> = listOfNotNull(listEntry, detailEntry)

    override val content: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.width(ListPaneWidth).fillMaxHeight()) { listEntry.Content() }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (detailEntry != null) detailEntry.Content() else placeholder()
            }
        }
    }

    // 文档要求自定义场景按内容判等:NavDisplay 靠它决定要不要重新过一遍转场。
    override fun equals(other: Any?): Boolean =
        other is ListDetailScene && key == other.key && entries == other.entries &&
            previousEntries == other.previousEntries

    override fun hashCode(): Int = (key.hashCode() * 31 + entries.hashCode()) * 31 + previousEntries.hashCode()
}

/**
 * 列表页挂的元数据:它此刻要不要把右栏空出来,以及空着时画什么。
 *
 * [showsDetail] 是函数而不是值:「消息」页只有停在私信那一格时才分栏,其余四格是整页的通知
 * 列表,而元数据在定义入口时就定下了。
 *
 * **它变了,调用方要换一个新的策略实例。** NavDisplay 把场景按(策略列表, 返回栈)remember
 * 起来(读 navigation3-ui 的 SceneState.rememberSceneState 核实过),计算时读到的快照状态不被
 * 追踪;只有策略实例变了才重算。
 */
class ListPane(val showsDetail: () -> Boolean, val placeholder: @Composable () -> Unit)

/**
 * 见 [ListDetailScene]。窗口不到 expanded 时不出场景,交给默认的单页。
 *
 * [wide] 由调用方按窗口宽度给,判据与 [rememberBilbyWindowSize] 相同。
 */
class ListDetailSceneStrategy(private val wide: Boolean) : SceneStrategy<NavKey> {
    override fun SceneStrategyScope<NavKey>.calculateScene(entries: List<NavEntry<NavKey>>): Scene<NavKey>? {
        if (!wide) return null
        val last = entries.lastOrNull() ?: return null
        if (last.metadata.contains(DetailKey)) {
            // 只认紧挨着的上一条:从对话里再点进空间、再点私信,那一条对话不该跑回别的列表旁边。
            val list = entries.getOrNull(entries.lastIndex - 1)?.takeIf { it.metadata.contains(ListKey) }
                ?: return null
            return ListDetailScene(
                key = list.contentKey,
                previousEntries = entries.dropLast(1),
                listEntry = list,
                detailEntry = last,
                placeholder = {},
            )
        }
        val pane = last.metadata[ListKey] ?: return null
        if (!pane.showsDetail()) return null
        return ListDetailScene(
            key = last.contentKey,
            previousEntries = entries.dropLast(1),
            listEntry = last,
            detailEntry = null,
            placeholder = pane.placeholder,
        )
    }

    private object ListKey : NavMetadataKey<ListPane>
    private object DetailKey : NavMetadataKey<Boolean>

    companion object {
        fun listPane(pane: ListPane): Map<String, Any> = metadata { put(ListKey, pane) }
        fun detailPane(): Map<String, Any> = metadata { put(DetailKey, true) }
    }
}

/**
 * 左栏定宽:一行只有头像、名字和一行摘要,再宽也只是摘要长一点;对话那一栏才要宽度。
 */
private val ListPaneWidth = 380.dp
