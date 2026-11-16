package dev.bilby.ui.night

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.data.CustomQueueEntry
import dev.bilby.data.QueueContext
import dev.bilby.data.SettingsStore
import dev.bilby.data.SpaceArchiveOrder
import dev.bilby.data.SpaceRepository
import dev.bilby.data.SpaceVideoItem
import dev.bilby.data.ToViewRepository
import dev.bilby.data.WhitelistedUp
import dev.bilby.parseDurationSeconds
import dev.bilby.resources.Res
import dev.bilby.resources.feed_toview_added
import dev.bilby.resources.feed_toview_failed
import dev.bilby.ui.errorTextRes
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

private const val BATCH_SIZE = 30
private const val MAX_REFETCH_ROUNDS = 4
private const val MAX_CUSTOM_QUEUE_ITEMS = 100

/** 夜间模式下的一条点播投稿，附带所属白名单 UP 主信息与来源页号。 */
data class NightVideoItem(
    val up: WhitelistedUp,
    val video: SpaceVideoItem,
    val page: Int,
) {
    val bvid: String get() = video.bvid
}

data class NightToViewNotice(
    val id: Long,
    val message: StringResource,
)

data class NightModeUiState(
    val allowedUps: List<WhitelistedUp> = emptyList(),
    val selectedMid: Long? = null,
    val items: List<NightVideoItem> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val appending: Boolean = false,
    val hasMore: Boolean = false,
    val error: StringResource? = null,
    val singleUpTotalCount: Int = 0,
    val toViewNotice: NightToViewNotice? = null,
)

/**
 * 夜间极简视图的状态管理：仅从白名单 UP 主的点播投稿拉取，不支持动态、直播或全局推荐。
 */
class NightModeViewModel(
    private val settings: SettingsStore,
    private val spaceRepository: SpaceRepository,
    private val toViewRepository: ToViewRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NightModeUiState())
    val state: StateFlow<NightModeUiState> = _state.asStateFlow()

    private var userPinnedMid: Long? = null
    private var generation = 0L
    private var loadJob: Job? = null
    private var session: MergeSession? = null
    private var noticeId = 0L

    init {
        viewModelScope.launch {
            settings.nightModePrefs
                .map { it.allowedUps }
                .distinctUntilChanged()
                .collect { ups -> onAllowedUpsChanged(ups) }
        }
    }

    fun selectUp(mid: Long?) {
        val ups = _state.value.allowedUps
        if (ups.isEmpty()) return
        val normalized = if (ups.size == 1) {
            ups.first().mid
        } else {
            mid?.takeIf { target -> ups.any { it.mid == target } }
        }
        userPinnedMid = normalized
        if (normalized == _state.value.selectedMid && _state.value.items.isNotEmpty()) return
        if (normalized == _state.value.selectedMid && (_state.value.loading || _state.value.error == null)) return
        _state.update { it.copy(selectedMid = normalized) }
        startFreshLoad(ups, normalized)
    }

    fun retry() {
        val current = _state.value
        if (current.allowedUps.isEmpty()) return
        if (current.items.isEmpty()) {
            startFreshLoad(current.allowedUps, current.selectedMid)
        } else {
            loadMore()
        }
    }

    fun refresh() {
        val current = _state.value
        if (current.allowedUps.isEmpty()) return
        val activeUps = current.selectedMid?.let { mid ->
            current.allowedUps.filter { it.mid == mid }
        } ?: current.allowedUps
        if (activeUps.isEmpty()) return

        generation += 1
        val reqGen = generation
        loadJob?.cancel()
        _state.update { it.copy(refreshing = true, appending = false, error = null) }
        val freshSession = createSession(activeUps, singleUpMode = current.selectedMid != null)
        loadJob = viewModelScope.launch {
            val outcome = freshSession.loadNextBatch()
            if (reqGen != generation) return@launch
            when {
                outcome.items.isNotEmpty() || outcome.anySuccess -> {
                    session = freshSession
                    _state.update {
                        it.copy(
                            items = outcome.items,
                            loading = false,
                            refreshing = false,
                            appending = false,
                            hasMore = outcome.hasMore,
                            error = outcome.lastError,
                            singleUpTotalCount = freshSession.singleTotalCount,
                        )
                    }
                }

                else -> {
                    val err = outcome.lastError
                    _state.update { state ->
                        state.copy(
                            refreshing = false,
                            loading = false,
                            appending = false,
                            error = if (state.items.isEmpty()) err else state.error,
                        )
                    }
                }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        val activeSession = session ?: return
        if (current.loading || current.refreshing || current.appending || !current.hasMore) return
        val reqGen = generation
        _state.update { it.copy(appending = true, error = null) }
        loadJob = viewModelScope.launch {
            val outcome = activeSession.loadNextBatch()
            if (reqGen != generation) return@launch
            if (outcome.items.isEmpty() && !outcome.anySuccess && outcome.lastError != null) {
                _state.update {
                    it.copy(
                        appending = false,
                        hasMore = outcome.hasMore,
                        error = outcome.lastError,
                    )
                }
            } else {
                _state.update { state ->
                    val existingBvids = state.items.mapTo(HashSet()) { it.bvid }
                    val added = outcome.items.filter { existingBvids.add(it.bvid) }
                    state.copy(
                        items = state.items + added,
                        appending = false,
                        hasMore = outcome.hasMore,
                        error = outcome.lastError,
                        singleUpTotalCount = activeSession.singleTotalCount,
                    )
                }
            }
        }
    }

    fun addToView(item: NightVideoItem) {
        viewModelScope.launch {
            val message = when (toViewRepository.add(item.bvid)) {
                is BiliResult.Ok -> Res.string.feed_toview_added
                else -> Res.string.feed_toview_failed
            }
            noticeId += 1
            _state.update { it.copy(toViewNotice = NightToViewNotice(noticeId, message)) }
        }
    }

    fun clearToViewNotice() {
        _state.update { it.copy(toViewNotice = null) }
    }

    fun buildQueueContext(clicked: NightVideoItem): QueueContext {
        val current = _state.value
        if (current.selectedMid != null) {
            return QueueContext.UpArchive(
                mid = clicked.up.mid,
                order = SpaceArchiveOrder.Pubdate,
                keyword = "",
                page = clicked.page,
            )
        }
        val list = current.items
        val idx = list.indexOfFirst { it.bvid == clicked.bvid }.coerceAtLeast(0)
        val window = if (list.size <= MAX_CUSTOM_QUEUE_ITEMS) {
            list
        } else {
            val start = (idx - MAX_CUSTOM_QUEUE_ITEMS / 2)
                .coerceIn(0, list.size - MAX_CUSTOM_QUEUE_ITEMS)
            list.subList(start, start + MAX_CUSTOM_QUEUE_ITEMS)
        }
        val entries = window.map { item ->
            CustomQueueEntry(
                bvid = item.video.bvid,
                title = item.video.title,
                upName = item.up.name,
                coverUrl = item.video.coverUrl,
                durationSeconds = parseDurationSeconds(item.video.durationText),
            )
        }
        return QueueContext.Custom(items = entries)
    }

    private fun onAllowedUpsChanged(ups: List<WhitelistedUp>) {
        val prev = _state.value
        val nextSelected = when {
            ups.isEmpty() -> null
            ups.size == 1 -> ups.first().mid
            else -> userPinnedMid?.takeIf { target -> ups.any { it.mid == target } }
        }
        val midsChanged = prev.allowedUps.map { it.mid } != ups.map { it.mid } ||
            prev.selectedMid != nextSelected
        val upByMid = ups.associateBy { it.mid }

        if (!midsChanged && ups.isNotEmpty() && (prev.items.isNotEmpty() || prev.loading)) {
            _state.update { state ->
                state.copy(
                    allowedUps = ups,
                    items = state.items.map { item -> upByMid[item.up.mid]?.let { item.copy(up = it) } ?: item },
                )
            }
            return
        }

        _state.update { it.copy(allowedUps = ups, selectedMid = nextSelected) }
        if (ups.isEmpty()) {
            generation += 1
            loadJob?.cancel()
            session = null
            _state.update {
                it.copy(
                    items = emptyList(),
                    loading = false,
                    refreshing = false,
                    appending = false,
                    hasMore = false,
                    error = null,
                    singleUpTotalCount = 0,
                )
            }
        } else {
            startFreshLoad(ups, nextSelected)
        }
    }

    private fun startFreshLoad(ups: List<WhitelistedUp>, selectedMid: Long?) {
        val activeUps = selectedMid?.let { mid -> ups.filter { it.mid == mid } } ?: ups
        if (activeUps.isEmpty()) return

        generation += 1
        val reqGen = generation
        loadJob?.cancel()
        val freshSession = createSession(activeUps, singleUpMode = selectedMid != null)
        session = freshSession
        _state.update {
            it.copy(
                items = emptyList(),
                loading = true,
                refreshing = false,
                appending = false,
                hasMore = true,
                error = null,
                singleUpTotalCount = 0,
            )
        }
        loadJob = viewModelScope.launch {
            val outcome = freshSession.loadNextBatch()
            if (reqGen != generation) return@launch
            if (outcome.items.isEmpty() && !outcome.anySuccess && outcome.lastError != null) {
                _state.update {
                    it.copy(
                        loading = false,
                        hasMore = outcome.hasMore,
                        error = outcome.lastError,
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        items = outcome.items,
                        loading = false,
                        hasMore = outcome.hasMore,
                        error = outcome.lastError,
                        singleUpTotalCount = freshSession.singleTotalCount,
                    )
                }
            }
        }
    }

    private fun createSession(ups: List<WhitelistedUp>, singleUpMode: Boolean): MergeSession {
        return MergeSession(
            cursors = ups.map { UpCursor(it) },
            singleUpMode = singleUpMode,
            spaceRepository = spaceRepository,
        )
    }

    private class UpCursor(
        val up: WhitelistedUp,
        var nextPage: Int = 1,
        var hasMore: Boolean = true,
        var fetchedCount: Int = 0,
        var total: Int = 0,
        val buffer: ArrayDeque<Pair<SpaceVideoItem, Int>> = ArrayDeque(),
    )

    private class BatchOutcome(
        val items: List<NightVideoItem>,
        val hasMore: Boolean,
        val anySuccess: Boolean,
        val lastError: StringResource?,
    )

    private class MergeSession(
        private val cursors: List<UpCursor>,
        private val singleUpMode: Boolean,
        private val spaceRepository: SpaceRepository,
    ) {
        private val seenBvids = HashSet<String>()

        val singleTotalCount: Int
            get() = if (singleUpMode) cursors.firstOrNull()?.total ?: 0 else 0

        suspend fun loadNextBatch(): BatchOutcome {
            if (singleUpMode) {
                val cursor = cursors.firstOrNull() ?: return BatchOutcome(emptyList(), false, false, null)
                if (!cursor.hasMore) return BatchOutcome(emptyList(), false, true, null)
                val requestedPage = cursor.nextPage
                return when (
                    val result = spaceRepository.loadArchives(
                        mid = cursor.up.mid,
                        page = requestedPage,
                        order = SpaceArchiveOrder.Pubdate,
                    )
                ) {
                    is BiliResult.Ok -> {
                        val pageItems = result.value.items
                        cursor.total = result.value.total
                        cursor.fetchedCount += pageItems.size
                        if (pageItems.isNotEmpty()) {
                            cursor.nextPage = requestedPage + 1
                        }
                        cursor.hasMore = pageItems.isNotEmpty() && cursor.fetchedCount < result.value.total
                        val items = pageItems
                            .filter { seenBvids.add(it.bvid) }
                            .map { NightVideoItem(cursor.up, it, requestedPage) }
                        BatchOutcome(
                            items = items,
                            hasMore = cursor.hasMore,
                            anySuccess = true,
                            lastError = null,
                        )
                    }

                    else -> BatchOutcome(
                        items = emptyList(),
                        hasMore = cursor.hasMore,
                        anySuccess = false,
                        lastError = result.errorTextRes("夜览取投稿"),
                    )
                }
            }

            val batch = ArrayList<NightVideoItem>(BATCH_SIZE)
            var anySuccess = false
            var lastError: StringResource? = null
            var refetchRounds = 0

            while (batch.size < BATCH_SIZE) {
                val needFetch = cursors.filter { it.buffer.isEmpty() && it.hasMore }
                var fetchFailed = false
                if (needFetch.isNotEmpty()) {
                    if (refetchRounds >= MAX_REFETCH_ROUNDS) break
                    refetchRounds += 1
                    val responses = coroutineScope {
                        needFetch.map { cursor ->
                            val requestedPage = cursor.nextPage
                            async {
                                Triple(
                                    cursor,
                                    requestedPage,
                                    spaceRepository.loadArchives(
                                        mid = cursor.up.mid,
                                        page = requestedPage,
                                        order = SpaceArchiveOrder.Pubdate,
                                    ),
                                )
                            }
                        }.awaitAll()
                    }
                    for ((cursor, requestedPage, result) in responses) {
                        when (result) {
                            is BiliResult.Ok -> {
                                anySuccess = true
                                val pageItems = result.value.items
                                cursor.total = result.value.total
                                cursor.fetchedCount += pageItems.size
                                pageItems.forEach { video -> cursor.buffer.addLast(video to requestedPage) }
                                if (pageItems.isNotEmpty()) {
                                    cursor.nextPage = requestedPage + 1
                                }
                                cursor.hasMore = pageItems.isNotEmpty() && cursor.fetchedCount < result.value.total
                            }

                            else -> {
                                lastError = result.errorTextRes("夜览取投稿")
                                fetchFailed = true
                            }
                        }
                    }
                }

                // 某个 UP 本页拉取失败时保留其 nextPage 与 hasMore，停在这里交给重试，不越过它继续吐旧视频。
                if (fetchFailed) break
                // 仍有待补页的 UP 还没拿到这一段头部数据时不再继续吐旧视频，保证跨 UP 时间倒序不乱序。
                if (cursors.any { it.buffer.isEmpty() && it.hasMore }) continue

                val bestCursor = cursors
                    .filter { it.buffer.isNotEmpty() }
                    .maxByOrNull { it.buffer.first().first.publishedAtEpochSeconds }
                    ?: break

                val (video, page) = bestCursor.buffer.removeFirst()
                if (seenBvids.add(video.bvid)) {
                    batch.add(NightVideoItem(bestCursor.up, video, page))
                }
            }

            val hasMore = cursors.any { it.buffer.isNotEmpty() || it.hasMore }
            return BatchOutcome(
                items = batch,
                hasMore = hasMore,
                anySuccess = anySuccess || batch.isNotEmpty(),
                lastError = lastError,
            )
        }
    }
}
