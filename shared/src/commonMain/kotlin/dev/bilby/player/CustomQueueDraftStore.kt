package dev.bilby.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 尚未打开播放页时用户在列表页积攒的待播自定义队列。
 *
 * 与服务内活队列严格分离:一旦用户从待播栏点击播放,条目即一次性交给新建的视频页帧接管,
 * 此处同步清空,避免同一份队列出现两个写入源。
 */
class CustomQueueDraftStore {

    private val _items = MutableStateFlow<List<QueueItem>>(emptyList())
    val items: StateFlow<List<QueueItem>> = _items.asStateFlow()

    fun enqueue(item: QueueItem, playNext: Boolean) {
        if (item.bvid.isBlank()) return
        _items.update { current ->
            val rest = current.filterNot { it.bvid == item.bvid }
            if (playNext) listOf(item) + rest else rest + item
        }
    }

    fun move(bvid: String, toIndex: Int) {
        _items.update { current ->
            val from = current.indexOfFirst { it.bvid == bvid }
            if (from < 0 || current.size <= 1) return@update current
            val target = toIndex.coerceIn(0, current.lastIndex)
            if (from == target) return@update current
            current.toMutableList().apply { add(target, removeAt(from)) }
        }
    }

    fun remove(bvid: String) {
        _items.update { current -> current.filterNot { it.bvid == bvid } }
    }

    fun clear() {
        _items.value = emptyList()
    }

    fun consumeAll(): List<QueueItem> {
        var taken = emptyList<QueueItem>()
        _items.update { current ->
            taken = current
            emptyList()
        }
        return taken
    }
}
