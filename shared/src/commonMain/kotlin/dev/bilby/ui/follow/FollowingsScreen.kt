package dev.bilby.ui.follow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import dev.bilby.data.DEFAULT_GROUP_ID
import dev.bilby.data.FollowGroup
import dev.bilby.data.FollowOrder
import dev.bilby.data.RelationRepository
import dev.bilby.data.SPECIAL_GROUP_ID
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.components.SearchField
import dev.bilby.ui.components.UnfollowConfirmDialog
import androidx.compose.material3.Surface
import dev.bilby.ui.components.fadingRightEdge
import dev.bilby.ui.theme.Spacing
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.bilby.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.appendDistinctBy
import dev.bilby.resources.*
import dev.bilby.ui.AdaptiveContent
import dev.bilby.api.BiliResult
import dev.bilby.api.map
import dev.bilby.data.FollowRepository
import dev.bilby.data.FollowingsPage
import dev.bilby.data.UpBrief
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.ListItemPersonSkeleton
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 这一页在看谁。**分组和排序是两件事**:分组决定"取哪一批人",排序决定"这批人怎么排"。
 * 接口本身就是这么分的 —— 取分组成员的 `x/relation/tag` 根本没有排序参数,而
 * `x/relation/followings` 的两种 `order_type` 是同一批人的两种顺序。
 */
sealed interface FollowSource {
    /** 全部关注。只有这一档能换排序(见 [FollowOrder])。 */
    data object All : FollowSource

    /** 一个分组。「特别关注」也是分组(tagid = -10),不单开一条路。 */
    data class Group(val group: FollowGroup) : FollowSource
}

data class FollowingsUiState(
    val items: List<UpBrief> = emptyList(),
    val groups: List<FollowGroup> = emptyList(),
    val source: FollowSource = FollowSource.All,
    val order: FollowOrder = FollowOrder.Frequent,
    /** 关注总数,写在「全部关注」芯片上。第一次取到全部关注那一页之前是 null,那时不写数。 */
    val total: Int? = null,
    /** 搜索词。非空时列表来自搜索接口,分组和排序都不参与。 */
    val query: String = "",
    /** 分组管理面板开着没有。 */
    val managingGroups: Boolean = false,
    /** 上一次分组读写的失败原因,显示在对应的面板里。 */
    val groupError: String? = null,
    /** 非空时正在给某个人设置分组。 */
    val picker: GroupPickerState? = null,
    // false 而不是 true:loadMore 的并发守卫现在直接读这个字段(见 FollowingsViewModel),
    // 默认 true 会让 init{} 里的第一次调用把自己挡在门外。首屏 loading 由 loadMore 显式置位。
    val loading: Boolean = false,
    val appending: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

/**
 * 关注列表。
 *
 * 默认排序是「最常访问」,与动态页顶上那排一致 —— 从那排点进来却换一种排法,会让人以为
 * 进错了地方。切到别的排法、切分组、搜索都会把列表整个换掉并从第一页重来。
 */
class FollowingsViewModel(
    private val repository: FollowRepository,
    private val relationRepository: RelationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FollowingsUiState())
    val state: StateFlow<FollowingsUiState> = _state.asStateFlow()

    /** 分组面板与分组名单都归它,空间页用的是同一份实现,见 [GroupPickerController]。 */
    private val groupPicker =
        GroupPickerController(repository, relationRepository, viewModelScope)

    private var page = 0

    /**
     * refresh 与 append 共用同一个 page 游标,不能并发改(性能计划 7.2):refresh 把 page
     * 清零重来,但保留屏上的旧列表直到第一页成功。这时一条还在飞的旧 append 落地必须
     * 当作过期丢弃,否则它会把清零后的 page 又向前推一格,或混入新列表。
     */
    private var generation = 0
    private var job: Job? = null

    init {
        loadMore()
        groupPicker.loadGroups()
        // 分组名单和面板都由 controller 持有,这一页的 UI 状态只是把它们抄进来,免得屏幕
        // 那侧要同时收三个 flow。
        viewModelScope.launch {
            groupPicker.groups.collect { groups -> _state.update { it.copy(groups = groups) } }
        }
        viewModelScope.launch {
            groupPicker.picker.collect { picker -> _state.update { it.copy(picker = picker) } }
        }
    }

    fun openGroupManager() = _state.update { it.copy(managingGroups = true, groupError = null) }

    fun closeGroupManager() = _state.update { it.copy(managingGroups = false, groupError = null) }

    fun createGroup(name: String) = groupAction { repository.createGroup(name) }

    fun renameGroup(id: Long, name: String) = groupAction { repository.renameGroup(id, name) }

    fun deleteGroup(id: Long) = groupAction { repository.deleteGroup(id) }

    /**
     * 分组的三个写动作长得一样:成功就重拉一次分组名单,失败把原因留在面板上。
     *
     * **重拉而不是本地增删一条。** chip 上除了名字还有人数,而人数只有列表接口给得出;
     * 新建那条接口虽然回了 tagid,拿它自己拼一条记录等于把人数猜一遍(notes 1.2)。
     *
     * 这里重拉是对的,和 [GroupPickerController.save] 那边不重拉不矛盾:那边动的是**成员**,
     * 写完立刻读会拿到旧人数;这里动的是**分组本身**,新建或删掉一个分组之后,名单里多没多
     * 那一行是立刻就能读到的。
     */
    private fun groupAction(action: suspend () -> BiliResult<Unit>) {
        viewModelScope.launch {
            _state.update { it.copy(groupError = null) }
            when (val result = action()) {
                is BiliResult.Ok -> groupPicker.loadGroups()
                is BiliResult.ApiError -> groupFail("${result.message}(${result.code})")
                is BiliResult.Failure -> groupFail(result.cause.message ?: "网络错误")
            }
        }
    }

    private fun groupFail(message: String) {
        BiliLog.w("分组操作失败: $message")
        _state.update { it.copy(groupError = message) }
    }

    fun openGroupPicker(up: UpBrief) = groupPicker.open(up)

    fun closeGroupPicker() = groupPicker.close()

    fun toggleGroup(groupId: Long) = groupPicker.toggle(groupId)

    /** 保存成功后把这一行的特别关注星标改成刚提交的那一份,不重拉名单。 */
    fun saveGroups() {
        val picker = _state.value.picker ?: return
        groupPicker.save {
            _state.update { state ->
                state.copy(
                    items = state.items.map { up ->
                        if (up.mid == picker.up.mid) up.copy(special = picker.special) else up
                    },
                )
            }
        }
    }

    fun block(mid: Long) = removeFromList(mid, "拉黑") { relationRepository.block(it) }

    fun unfollow(mid: Long) = removeFromList(mid, "取消关注") { relationRepository.unfollow(it) }

    /**
     * 拉黑与取关。**乐观更新、失败回滚、不重拉**,与关注/取关同一套规矩。
     *
     * 两者都会让这个人离开关注名单(拉黑连带解除关注),所以这一行直接拿掉,总数跟着减一,
     * 不等一个来回,也不重拉整份名单。
     */
    private fun removeFromList(mid: Long, action: String, write: suspend (Long) -> BiliResult<Unit>) {
        val before = _state.value
        if (before.items.none { it.mid == mid }) return
        _state.update {
            it.copy(items = it.items.filterNot { up -> up.mid == mid }, total = it.total?.minus(1))
        }
        viewModelScope.launch {
            val result = write(mid)
            if (result !is BiliResult.Ok) {
                BiliLog.w("${action}失败: $result")
                _state.update { it.copy(items = before.items, total = before.total) }
            }
        }
    }

    /** 切分组。同一档再点一次不重来 —— 那只会把已经读到的位置清掉。 */
    fun selectSource(source: FollowSource) {
        if (_state.value.source == source && _state.value.query.isEmpty()) return
        _state.update { it.copy(source = source, query = "") }
        restart()
    }

    fun selectOrder(order: FollowOrder) {
        if (_state.value.order == order && _state.value.query.isEmpty()) return
        _state.update { it.copy(order = order, query = "") }
        restart()
    }

    /**
     * 搜索词变了。**不做防抖**:这一次请求由输入法的"搜索"键触发,不是每敲一个字发一次 ——
     * 关注列表可以有几百人,边打边搜等于一路发请求,而搜到一半的词几乎不会命中想找的人。
     */
    fun search(query: String) {
        if (_state.value.query == query) return
        _state.update { it.copy(query = query) }
        restart()
    }

    private fun restart() {
        generation++
        job?.cancel()
        page = 0
        _state.update {
            it.copy(items = emptyList(), error = null, loading = false, appending = false, hasMore = true)
        }
        loadMore(replace = true)
    }

    fun loadMore(replace: Boolean = false) {
        val current = _state.value
        if (current.loading || current.appending || !current.hasMore) return
        val next = if (replace) 1 else page + 1
        val gen = generation
        _state.update {
            it.copy(
                loading = replace || next == 1,
                appending = !replace && next > 1,
            )
        }
        job = viewModelScope.launch {
            try {
                when (val result = fetch(current, next)) {
                    is BiliResult.Ok -> {
                        if (gen != generation) return@launch
                        page = next
                        val fetched = result.value.items
                        _state.update {
                            it.copy(
                                items = if (replace) {
                                    fetched.distinctBy { up -> up.mid }
                                } else {
                                    it.items.appendDistinctBy(fetched) { up -> up.mid }
                                },
                                // 分组与搜索不给总数,切过去时保留上一次读到的那个。
                                total = result.value.total ?: it.total,
                                // 接口不给 has_more,按"这一页没满就是最后一页"判断。
                                hasMore = fetched.isNotEmpty(),
                                error = null,
                            )
                        }
                    }

                    is BiliResult.ApiError -> if (gen == generation) fail("${result.message}(${result.code})")
                    is BiliResult.Failure -> if (gen == generation) fail(result.cause.message ?: "网络错误")
                }
            } finally {
                if (gen == generation) _state.update { it.copy(loading = false, appending = false, refreshing = false) }
            }
        }
    }

    fun refresh() {
        generation++
        job?.cancel()
        page = 0
        // loading 留 false:loadMore 的并发守卫要看到它才会真的发请求,这里置 true
        // 反而会把紧跟着的 loadMore() 自己挡在门外(见 loadMore 顶部的守卫)。
        _state.update {
            it.copy(error = null, loading = false, appending = false, hasMore = true, refreshing = true)
        }
        loadMore(replace = true)
    }

    fun retry() {
        if (_state.value.items.isNotEmpty()) loadMore(replace = page == 0) else refresh()
    }

    /**
     * 这一页当前该问哪条接口。三条路互斥:搜索词非空时就是搜索,否则看分组还是全部。
     *
     * 入参用发起这次请求时的那份 state,不用 `_state.value` —— 请求飞在半路时用户又切了
     * 一档的话,落地的应该是它自己那一档的结果,而"是不是过期了"由 generation 判。
     */
    private suspend fun fetch(state: FollowingsUiState, page: Int): BiliResult<FollowingsPage> = when {
        state.query.isNotBlank() ->
            repository.searchFollowings(state.query, page).map { FollowingsPage(it, total = null) }

        state.source is FollowSource.Group ->
            repository.groupMembers(state.source.group.id, page).map { FollowingsPage(it, total = null) }

        else -> repository.followings(page, state.order)
    }

    private fun fail(message: String) {
        BiliLog.w("取关注列表失败: $message")
        _state.update { it.copy(loading = false, appending = false, refreshing = false, error = message) }
    }
}

@Composable
fun FollowingsScreen(
    state: FollowingsUiState,
    onUpClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onSelectSource: (FollowSource) -> Unit,
    onSearch: (String) -> Unit,
    onOpenGroupManager: () -> Unit,
    onCloseGroupManager: () -> Unit,
    onCreateGroup: (String) -> Unit,
    onRenameGroup: (Long, String) -> Unit,
    onDeleteGroup: (Long) -> Unit,
    onOpenGroupPicker: (UpBrief) -> Unit,
    onCloseGroupPicker: () -> Unit,
    onToggleGroup: (Long) -> Unit,
    onSaveGroups: () -> Unit,
    onBlock: (Long) -> Unit,
    onUnfollow: (Long) -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    AdaptiveContent(modifier = modifier) {
        RefreshBox(
            refreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            PagedColumn(
                items = state.items,
                key = { it.mid },
                skeletonRow = { ListItemPersonSkeleton() },
                loading = state.loading,
                appending = state.appending,
                hasMore = state.hasMore,
                error = state.error,
                emptyText = stringResource(
                    if (state.query.isBlank()) Res.string.followings_empty else Res.string.followings_search_empty,
                ),
                onLoadMore = onLoadMore,
                onRetry = onRetry,
                contentPadding = contentPadding,
                header = {
                    item(key = "controls") {
                        FollowingsControls(
                            state,
                            onSelectSource,
                            onSearch,
                            onOpenGroupManager,
                        )
                    }
                },
            ) { up ->
                FollowingRow(
                    up,
                    onClick = { onUpClick(up.mid) },
                    onSetGroups = { onOpenGroupPicker(up) },
                    onUnfollow = { onUnfollow(up.mid) },
                    onBlock = { onBlock(up.mid) },
                )
            }
        }
    }

    if (state.managingGroups) {
        GroupManagerSheet(
            groups = state.groups,
            error = state.groupError,
            onCreate = onCreateGroup,
            onRename = onRenameGroup,
            onDelete = onDeleteGroup,
            onDismiss = onCloseGroupManager,
        )
    }
    state.picker?.let { picker ->
        GroupPickerSheet(
            state = picker,
            groups = state.groups,
            onToggle = onToggleGroup,
            onSave = onSaveGroups,
            onDismiss = onCloseGroupPicker,
        )
    }
}

/**
 * 这一页的排序能不能用。只在「全部关注」下 —— 取分组成员那条接口(`x/relation/tag`)没有
 * 排序参数,画一个切了没反应的开关比不画更糟。搜索时同理:结果的顺序由搜索接口定。
 */
val FollowingsUiState.canSort: Boolean get() = source is FollowSource.All && query.isBlank()

/** 排序的选项,顶栏那颗 [dev.bilby.ui.components.SortMenu] 用。 */
val FollowOrderOptions = listOf(
    FollowOrder.Frequent to Res.string.followings_order_frequent,
    FollowOrder.Recent to Res.string.followings_order_recent,
)

/**
 * 搜索框 + 分组。两者跟着列表一起滚,不吸顶:这一页的主体是那份名单,
 * 一条常驻的控制条会一直占着一屏里最上面那几十 dp。
 *
 * **排序不在这里,在顶栏右端**(见 MainActivity 的 FollowingsRoute)。它原先单独占着分组
 * 下面一整行,名单要到第四行才露出来。它也不和分组挤一排:两者回答的是两个问题,分组是
 * "看哪一批人",排序是"这批人怎么排",摆成一排之后「特别关注」和「最近关注」并列,读起来像
 * 同一组互斥选项,而实际上前者会换掉整份名单、后者只换顺序。
 */
@Composable
private fun FollowingsControls(
    state: FollowingsUiState,
    onSelectSource: (FollowSource) -> Unit,
    onSearch: (String) -> Unit,
    onOpenGroupManager: () -> Unit,
) {
    var input by rememberSaveable(state.query) { mutableStateOf(state.query) }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.Tight)) {
        SearchField(
            value = input,
            // 清空输入立刻回到当前分组,不用再按一次搜索键 —— 清空的意思就是"不搜了"。
            onValueChange = { text ->
                input = text
                if (text.isEmpty()) onSearch("")
            },
            placeholder = stringResource(Res.string.followings_search_hint),
            // 输入法的搜索键才真的发请求,不边打边搜:关注可以有几百人,那样是一路发请求,
            // 而搜到一半的词几乎不会命中要找的人。
            onSearch = { onSearch(input) },
            modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        )

        // 分组可以有任意多个(用户自己在 B 站建),所以这一排横滚 —— 与首页那排头像不同,
        // 那一排后面藏着入口,这一排藏的是并列的筛选项,滚到哪里都不影响别的东西。
        //
        // **管理分组的按钮钉在右端,不跟着横滚。** 它不是一个筛选项,混进那一排之后既会被滚出
        // 视野,也会被读成"还有一个叫管理分组的分组"。
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 右边沿渐隐,和动态页那排头像共用一份(见 [fadingRightEdge])。这一排的右端
            // 钉着「管理分组」,滚动时被它切一半的是一枚圆角芯片。
            //
            // 渐隐挂在 horizontalScroll 之前:它作用于"这一排量出来的那个视口",挂在滚动之后
            // 会跟着内容一起滚,渐变落在某一枚芯片上不动了。
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fadingRightEdge()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.Comfortable),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                // 芯片视觉高度 32dp,触控区要撑到 48dp:material3 的 Chip 不像 IconButton
                // 那样自带这层强制(查 1.5.0-alpha25 的 aar 核实过,ChipKt 里没有引用)。
                // 芯片本身的样子不变,长高的是它占的那格。
                FilterChip(
                    selected = state.source is FollowSource.All && state.query.isBlank(),
                    onClick = { onSelectSource(FollowSource.All) },
                    // 人数的写法同分组芯片;总数还没读到时只写名字。
                    label = {
                        val name = stringResource(Res.string.followings_all)
                        Text(state.total?.let { "$name$MetaSeparator$it" } ?: name)
                    },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                )
                state.groups.forEach { group ->
                    FilterChip(
                        selected = (state.source as? FollowSource.Group)?.group?.id == group.id &&
                            state.query.isBlank(),
                        onClick = { onSelectSource(FollowSource.Group(group)) },
                        // 分组名是用户自己起的,原样显示;人数跟在后面,不用中点分隔(见 MetaSeparator)。
                        label = { Text("${group.name}$MetaSeparator${group.count}") },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    )
                }
            }
            IconButton(onClick = onOpenGroupManager) {
                Icon(
                    Icons.Outlined.Tune,
                    contentDescription = stringResource(Res.string.follow_groups_manage),
                )
            }
        }
    }
}

/**
 * 一行一个人。**分组、取关和拉黑收在行尾的菜单里**,不各占一个按钮:这一页的主要动作是点进
 * 空间,而一行摆三个可点的东西之后,最常做的那件事反而最难认出来。
 *
 * 名字后面按需跟「特别关注」「互相关注」两枚标记。特别关注不画星:星在播放页是收藏,同一个
 * 字形在两处说两件事。
 */
@Composable
private fun FollowingRow(
    up: UpBrief,
    onClick: () -> Unit,
    onSetGroups: () -> Unit,
    onUnfollow: () -> Unit,
    onBlock: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingBlock by remember { mutableStateOf(false) }
    var confirmingUnfollow by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                Text(
                    up.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // fill = false:名字短时标记紧跟在后面,名字长时先截名字,标记不被挤掉。
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (up.special) RelationTag(stringResource(Res.string.follow_special_tag), emphasized = true)
                if (up.mutual) RelationTag(stringResource(Res.string.follow_mutual), emphasized = false)
            }
        },
        supportingContent = up.sign.takeIf { it.isNotBlank() }?.let { sign ->
            {
                Text(
                    sign,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = { Avatar(url = up.faceUrl, size = Dimens.AvatarRow) },
        trailingContent = {
            // 往右挪到和上面「管理分组」那颗图标同一条竖线上:那颗按钮离右缘 Tight,ListItem 的
            // 行尾内边距是 16(查 1.5.0-alpha25 ListItemKt 的 ListItemEndPadding),两者差这么多。
            Box(modifier = Modifier.offset(x = ListItemEndPadding - Spacing.Tight)) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(Res.string.follow_row_actions, up.name),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.follow_set_groups)) },
                        onClick = {
                            menuOpen = false
                            onSetGroups()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.follow_unfollow_confirm_title)) },
                        onClick = {
                            menuOpen = false
                            confirmingUnfollow = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.blacklist_block)) },
                        onClick = {
                            menuOpen = false
                            confirmingBlock = true
                        },
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
    if (confirmingBlock) {
        BlockConfirmDialog(
            name = up.name,
            onConfirm = onBlock,
            onDismiss = { confirmingBlock = false },
        )
    }
    if (confirmingUnfollow) {
        UnfollowConfirmDialog(
            name = up.name,
            onConfirm = onUnfollow,
            onDismiss = { confirmingUnfollow = false },
        )
    }
}

/** material3 ListItem 的行尾内边距,库里是私有常量,抄一份。 */
private val ListItemEndPadding = 16.dp

/**
 * 名字后面的关系标记,外形同动态的「置顶」(风格指南里「置顶」那一段):一小块带底色的字,
 * `extraSmall` 圆角。特别关注是用户自己划的,取 secondaryContainer;互相关注只是一个事实,
 * 取更安静的 surfaceContainerHighest。
 */
@Composable
private fun RelationTag(text: String, emphasized: Boolean) {
    Surface(
        color = if (emphasized) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (emphasized) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Spacing.Tight / 2, vertical = Spacing.Hair / 2),
        )
    }
}
