package dev.bilby.ui.profile

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.data.AccountInfo
import dev.bilby.data.AccountRepository
import dev.bilby.data.FavFolder
import dev.bilby.data.FavRepository
import dev.bilby.data.HistoryItem
import dev.bilby.data.HistoryRepository
import dev.bilby.data.SettingsStore
import dev.bilby.data.ToViewItem
import dev.bilby.data.ToViewRepository
import dev.bilby.offline.OfflineDownloader
import dev.bilby.offline.OfflineItem
import dev.bilby.ui.offline.toRowUi
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.LevelBadge
import dev.bilby.ui.components.SectionHeader
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.history.toRowUi
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.toview.toRowUi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 历史记录 / 稍后再看 / 收藏夹三个预览区共用的形状:各自独立加载、独立失败。 */
data class ProfilePreviewState<T>(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<T> = emptyList(),
)

data class ProfileUiState(
    val accountLoading: Boolean = true,
    val accountError: String? = null,
    val account: AccountInfo? = null,
    val history: ProfilePreviewState<HistoryItem> = ProfilePreviewState(),
    val toView: ProfilePreviewState<ToViewItem> = ProfilePreviewState(),
    val favFolders: ProfilePreviewState<FavFolder> = ProfilePreviewState(),
    /**
     * 已缓存的视频。**没有 loading 也没有 error** —— 它读的是本地目录,由
     * [dev.bilby.offline.OfflineDownloader] 常驻持有,不存在"正在拉"这个状态。
     */
    val offline: List<OfflineItem> = emptyList(),
)

/**
 * 账号信息、历史记录预览、稍后再看预览、收藏夹列表 —— 四样各自独立加载,一个失败
 * 不连坐其它三个:收藏夹拉不到就只显示那一节的失败态,不影响历史记录或稍后再看照常展示。
 *
 * 历史记录与稍后再看只取**第一页的前 5 条**,不做翻页:这一页是概览,不是列表,
 * 要看全部就走"查看全部"进各自的完整页面(DESIGN 1.1:有限集合,不在概览页再造一个
 * 能无限滚的地方)。收藏夹本身条数不多,列表不设上限。
 */
class ProfileViewModel(
    private val settings: SettingsStore,
    private val accountRepository: AccountRepository,
    private val historyRepository: HistoryRepository,
    private val toViewRepository: ToViewRepository,
    private val favRepository: FavRepository,
    offlineDownloader: OfflineDownloader,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private var lastRefreshedAtMillis = 0L

    init {
        refresh()
        // 缓存列表是本地状态,跟着下载器走就行 —— 它不参与上面那四块的"重进就重拉"。
        viewModelScope.launch {
            offlineDownloader.items.collect { items -> _state.update { it.copy(offline = items) } }
        }
    }

    /** 每次重新进入「我的」都重新取四块概览,保证跨页面操作(删稍后再看、看新视频)后能及时反映。 */
    fun refresh() {
        lastRefreshedAtMillis = SystemClock.elapsedRealtime()
        retryAccount()
        retryHistory()
        retryToView()
        retryFavFolders()
    }

    /**
     * 重新进入这一页时补一次,**刚拉过就跳过**。底栏来回点、或者进子页面看一眼就退回来,
     * 每次都打四个请求既没有新内容可显示,又让那四块各闪一次骨架。
     *
     * 用 elapsedRealtime 而不是墙钟:后者会被对时和时区调整拨动,拨回去就再也刷不了。
     */
    fun refreshIfStale() {
        if (SystemClock.elapsedRealtime() - lastRefreshedAtMillis < RefreshDebounceMillis) return
        refresh()
    }

    fun retryAccount() {
        _state.update { it.copy(accountLoading = true, accountError = null) }
        viewModelScope.launch {
            when (val result = accountRepository.loadInfo()) {
                is BiliResult.Ok -> _state.update { it.copy(accountLoading = false, account = result.value) }
                is BiliResult.ApiError -> {
                    BiliLog.w("取账号信息失败(${result.code}): ${result.message}")
                    _state.update { it.copy(accountLoading = false, accountError = "${result.message}(${result.code})") }
                }

                is BiliResult.Failure -> {
                    BiliLog.w("取账号信息异常", result.cause)
                    _state.update { it.copy(accountLoading = false, accountError = result.cause.message ?: "网络错误") }
                }
            }
        }
    }

    fun retryHistory() {
        _state.update { it.copy(history = it.history.copy(loading = true, error = null)) }
        viewModelScope.launch {
            when (val result = historyRepository.loadPage(0L, 0L)) {
                is BiliResult.Ok -> _state.update {
                    it.copy(history = it.history.copy(loading = false, items = result.value.items.take(PreviewCount)))
                }

                else -> _state.update {
                    it.copy(history = it.history.copy(loading = false, error = result.errorText()))
                }
            }
        }
    }

    fun retryToView() {
        _state.update { it.copy(toView = it.toView.copy(loading = true, error = null)) }
        viewModelScope.launch {
            when (val result = toViewRepository.loadList()) {
                is BiliResult.Ok -> _state.update {
                    it.copy(toView = it.toView.copy(loading = false, items = result.value.items.take(PreviewCount)))
                }

                else -> _state.update {
                    it.copy(toView = it.toView.copy(loading = false, error = result.errorText()))
                }
            }
        }
    }

    fun retryFavFolders() {
        _state.update { it.copy(favFolders = it.favFolders.copy(loading = true, error = null)) }
        viewModelScope.launch {
            when (val result = favRepository.folders()) {
                is BiliResult.Ok -> _state.update {
                    it.copy(favFolders = it.favFolders.copy(loading = false, items = result.value))
                }

                else -> _state.update {
                    it.copy(favFolders = it.favFolders.copy(loading = false, error = result.errorText()))
                }
            }
        }
    }

    /**
     * 同 `SettingsViewModel.logout`(已删除,搬到这里):清凭据必须扛得住页面本身随时被
     * 销毁(`NonCancellable`),停播放服务要 Context,由调用方在拿到 `onDone` 之后做。
     */
    fun logout(onDone: () -> Unit) {
        viewModelScope.launch(NonCancellable) {
            settings.clearCredentials()
            onDone()
        }
    }

    private fun BiliResult<*>.errorText(): String = when (this) {
        is BiliResult.ApiError -> "$message($code)"
        is BiliResult.Failure -> cause.message ?: "网络错误"
        is BiliResult.Ok -> ""
    }

    private companion object {
        /**
         * 每一节预览几条。**硬编码,不做"加载更多"**(DESIGN 2.7):这一页是概览,要看全的进那一页。
         * 一个能在这里无限往下滚的地方,和 1.1 表里"无限滚动"那一格没有区别,哪怕滚的是自己看过的。
         *
         * 取 3 不取 5:两节预览加上收藏夹要一起放进一屏,5 条时光历史记录就吃掉整屏,
         * 底下两节要滚很久才见得到,那就不再是概览了。
         */
        const val PreviewCount = 3

        /** 概览的重进刷新间隔,见 [refreshIfStale]。 */
        private const val RefreshDebounceMillis = 30_000L
    }
}

/**
 * 「我的」:账号头部 + 历史记录/稍后再看预览 + 收藏夹列表。
 *
 * 这一页是底部导航的根 tab。三个根 tab 都不再有共用顶栏(标题和底栏标签逐字重复,
 * M3 对 top app bar 的定义是"显示信息与操作",两样都没有就是死高度)——设置是这一页
 * 唯一的页级操作,挂在账号头部那一行的末尾,紧跟在"登出"右边:两者都是"这个账号相关的
 * 操作",摆在一起比孤零零挂在屏幕角上更说得通(`IconButton` 自带 48dp 触摸区,见
 * [AccountHeader])。
 */
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onVideoClick: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenToView: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenFavFolder: (FavFolder) -> Unit,
    onSettingsClick: () -> Unit,
    /** 点账号那一块进自己的空间。 */
    onOpenSelf: (Long) -> Unit,
    onRetryAccount: () -> Unit,
    onRetryHistory: () -> Unit,
    onRetryToView: () -> Unit,
    onRetryFavFolders: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        AccountHeader(
            state = state,
            onSettingsClick = onSettingsClick,
            onOpenSelf = onOpenSelf,
            onRetry = onRetryAccount,
        )

        // 账号与下面三节之间**不画分割线**。divider 页说 full-width 用于分隔"larger sections
        // of unrelated content",并要求 sparingly;而下面每一节自己都顶着一个 SectionHeader,
        // 那行标题已经说明"换了一节"了,再加一条线是同一件事说两遍。
        if (rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)) {
            // Expanded 之后把三个低密度预览区分成两个可扫读的 pane：历史和稍后再看是
            // 同一类视频清单，收藏夹是另一类用户整理内容。Medium 仍保持单列，避免在
            // 信息密度已经很高的概览页过早拆栏。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Comfortable),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Loose),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    HistorySection(state.history, onVideoClick, onOpenHistory, onRetryHistory)
                    ToViewSection(state.toView, onVideoClick, onOpenToView, onRetryToView)
                }
                Column(modifier = Modifier.weight(1f)) {
                    // 缓存跟着收藏走:两者都是"我自己存下来的东西",而上面两块是"我看过/打算看的"。
                    FavFoldersSection(state.favFolders, onOpenFavFolder, onRetryFavFolders)
                    OfflineSection(state.offline, onOpenOffline)
                }
            }
        } else {
            HistorySection(state.history, onVideoClick, onOpenHistory, onRetryHistory)
            ToViewSection(state.toView, onVideoClick, onOpenToView, onRetryToView)
            FavFoldersSection(state.favFolders, onOpenFavFolder, onRetryFavFolders)
            OfflineSection(state.offline, onOpenOffline)
        }

        Spacer(Modifier.height(Spacing.Comfortable))
    }
}

/**
 * 头像 → 名字 + 等级徽章 → 设置,再加一行个性签名。**整块可点,进自己的空间** ——
 * 这一页看得到的是"我攒了什么"(历史、稍后再看、收藏夹),而"我发了什么"在空间页,
 * 原先从这个 app 里根本走不到自己的空间。
 *
 * **不用 `ListItem`。** 它的内边距叠在这个 Column 自己的 padding 上,和下面第一节之间
 * 撑出一段空隙;三行形态还有各自的最小高度,签名那一行也被它的排布挤掉了宽度,于是签名
 * 和右边的设置图标之间空出一大块。手写成一行 Row 之后,签名拿的是 `weight(1f)`,
 * 一直铺到设置按钮跟前。
 *
 * **签名为空时整行不画**——
 * 不给"这个人很懒"一类的占位文案,那是空间页(`SpaceHeader`)的处理,这里没有那个理由:
 * 签名本来就可能没填,不是"数据还没到"。
 *
 * **登出不在这里,在设置页。** 它一年用不到一次,而这一页每天都要经过;摆在这一行上既占着
 * 名字和签名的宽度,又和旁边"看看我攒了什么"完全不是一类事。设置入口留着 —— 它不属于
 * `account != null` 那个分支——账号信息拉不到甚至还在读的时候,设置入口也不该跟着消失,
 * 所以三条分支各自只负责把中段(名字/错误文案/空白)撑到 `weight(1f)`,`IconButton`
 * 本身写在 `when` 外面,始终是这一行最后一个元素。
 *
 * 三态都不是整屏级别的:失败或还在读的时候,下面的三个板块照常能加载 ——
 * 它们不依赖账号信息,不该被这一次请求的成败连坐。
 */
@Composable
private fun AccountHeader(
    state: ProfileUiState,
    onSettingsClick: () -> Unit,
    onOpenSelf: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    // **上下留白归里面那一行,不归这个 Column。** 它是可点区域,留白算在里面涟漪才盖得住;
    // 两边各留一份的话上下就是 12 + 8。这里只负责左右两侧的对齐。
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Comfortable),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        state.account?.let { account ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onOpenSelf(account.mid) }
                    .padding(vertical = Spacing.Cozy),
            ) {
                Avatar(url = account.faceUrl, size = Dimens.AvatarLarge)
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    ) {
                        Text(
                            text = account.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        LevelBadge(
                            level = account.level,
                            senior = account.isSeniorMember,
                            height = Dimens.LevelBadgeHeight,
                        )
                    }
                    if (account.sign.isNotBlank()) {
                        Text(
                            text = account.sign,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.settings_title),
                    )
                }
            }
        } ?: Row(
            // 失败/加载态没有上面那块可点区域,自己补上同一份上下留白,免得账号一拉到
            // 整页内容就往下跳一截。
            modifier = Modifier
                .heightIn(min = Dimens.AvatarLarge)
                .padding(vertical = Spacing.Cozy),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        ) {
            when {
                state.accountError != null -> {
                    Text(
                        text = state.accountError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                }
                // loading:留白,不放骨架屏(DESIGN:骨架屏是在假装内容马上就到),
                // 但设置图标要照样钉在行尾,所以这里垫一个 Spacer 吃掉左边的空间。
                else -> Spacer(Modifier.weight(1f))
            }
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
            }
        }
    }
}

/** 历史记录预览:最近 5 条,"查看全部"进完整的历史记录页。 */
@Composable
private fun HistorySection(
    state: ProfilePreviewState<HistoryItem>,
    onVideoClick: (String) -> Unit,
    onViewAll: () -> Unit,
    onRetry: () -> Unit,
) {
    PreviewSection(
        title = stringResource(R.string.history_title),
        state = state,
        emptyText = stringResource(R.string.history_empty),
        onViewAll = onViewAll,
        onRetry = onRetry,
    ) { item ->
        VideoRow(item = item.toRowUi(), onClick = { onVideoClick(item.bvid) })
    }
}

/** 稍后再看预览:同上,"查看全部"进完整的稍后再看列表。 */
@Composable
private fun ToViewSection(
    state: ProfilePreviewState<ToViewItem>,
    onVideoClick: (String) -> Unit,
    onViewAll: () -> Unit,
    onRetry: () -> Unit,
) {
    PreviewSection(
        title = stringResource(R.string.tab_toview),
        state = state,
        emptyText = stringResource(R.string.toview_empty),
        onViewAll = onViewAll,
        onRetry = onRetry,
        hideWhenEmpty = true,
    ) { item ->
        VideoRow(item = item.toRowUi(), onClick = { onVideoClick(item.bvid) })
    }
}

/**
 * 已缓存的视频预览。**一条都没有时整节不画**,和稍后再看同一条判据:没缓存过东西的人不需要
 * 先认识"离线缓存"这个概念,才能看懂自己页面上多出来的一行字。
 */
@Composable
private fun OfflineSection(items: List<OfflineItem>, onViewAll: () -> Unit) {
    if (items.isEmpty()) return
    // **这里要一条通栏分割线。** 别处不画是因为每一节都顶着自己的 SectionHeader,那行标题
    // 已经说明"换了一节";而这一行没有标题,紧跟在收藏夹的几行下面就会被读成又一个收藏夹。
    // divider 页给的正是这条判据:"To separate a different kind of content, use a full-width
    // divider",以及通栏线用于 "separate larger sections of unrelated content"。
    HorizontalDivider()
    // **一行入口,不是预览列表**,和收藏夹同一个形状。缓存是会长的:攒到几十条之后,在这一页
    // 铺前五条既看不出全貌,又把下面的收藏夹推到屏幕外。这一页是概览,"我缓存了多少"这一个
    // 数字就够,要挑哪一条是缓存页自己的事。
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(R.string.offline_title),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.offline_count, items.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(
                Icons.Outlined.DownloadForOffline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onViewAll),
    )
}

/**
 * 三个预览区共用的骨架:小节标题(+"查看全部")、loading/error/empty 三态、内容。
 * 没有"查看全部"按钮时(条数为 0)不画它——链接到一个空页面没有意义。
 */
@Composable
private fun <T> PreviewSection(
    title: String,
    state: ProfilePreviewState<T>,
    emptyText: String,
    onViewAll: () -> Unit,
    onRetry: () -> Unit,
    /**
     * 空的时候整节不画,而不是画一行"还没有"。给**用不到这个功能的人**用:没往稍后再看
     * 存过东西的人不需要先认识"稍后再看"这个概念,才能看懂自己页面上多出来的一行字。
     * 和设置页那条"一个 UP 都没排除过就不显示排除名单"是同一条判据。
     *
     * 只对"确实是空的"成立 —— loading 和 error 照常显示,那两种情况下这一节是存在的,
     * 只是还不知道里面有什么。
     */
    hideWhenEmpty: Boolean = false,
    itemRow: @Composable (T) -> Unit,
) {
    if (hideWhenEmpty && !state.loading && state.error == null && state.items.isEmpty()) return
    Column(modifier = Modifier.padding(top = Spacing.Comfortable)) {
        SectionHeader(
            title = title,
            modifier = Modifier.padding(horizontal = Spacing.Comfortable),
        ) {
            if (state.items.isNotEmpty()) {
                TextButton(onClick = onViewAll) { Text(stringResource(R.string.profile_view_all)) }
            }
        }
        when {
            state.loading -> InlineSectionProgress()
            state.error != null -> InlineSectionError(state.error, onRetry)
            state.items.isEmpty() -> InlineSectionMessage(emptyText)
            else -> Column { state.items.forEach { itemRow(it) } }
        }
    }
}

/** 收藏夹列表,不设条数上限(DESIGN:用户自己收的,条数本来就不多)。 */
@Composable
private fun FavFoldersSection(
    state: ProfilePreviewState<FavFolder>,
    onOpenFolder: (FavFolder) -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = Spacing.Comfortable)) {
        SectionHeader(
            title = stringResource(R.string.tab_saved),
            modifier = Modifier.padding(horizontal = Spacing.Comfortable),
        )
        when {
            state.loading -> InlineSectionProgress()
            state.error != null -> InlineSectionError(state.error, onRetry)
            state.items.isEmpty() -> InlineSectionMessage(stringResource(R.string.profile_favorites_empty))
            else -> Column {
                state.items.forEach { folder ->
                    FavFolderRow(folder, onClick = { onOpenFolder(folder) })
                }
            }
        }
    }
}

@Composable
private fun FavFolderRow(folder: FavFolder, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(folder.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.fav_folder_count, folder.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(Icons.Outlined.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun InlineSectionProgress() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text = stringResource(R.string.settings_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InlineSectionError(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

@Composable
private fun InlineSectionMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    )
}

// ---- Preview ----

private fun previewHistoryItem(oid: Long, title: String) = HistoryItem(
    oid = oid,
    bvid = "BV1aa$oid",
    title = title,
    coverUrl = "https://i0.hdslb.com/bfs/archive/preview.jpg",
    durationText = "12:34",
    durationSeconds = 754,
    upName = "某知名UP主",
    viewAtEpochSeconds = 0L,
    progressSeconds = 300,
)

@Preview(showBackground = true, name = "已登录")
@Composable
private fun ProfileScreenPreview() {
    BilbyTheme {
        ProfileScreen(
            state = ProfileUiState(
                accountLoading = false,
                account = AccountInfo(
                    mid = 1L,
                    name = "某知名用户",
                    faceUrl = "",
                    level = 5,
                    isSeniorMember = false,
                    sign = "一句话签名,可能很长也可能没有",
                ),
                history = ProfilePreviewState(
                    loading = false,
                    items = listOf(previewHistoryItem(1, "看到一半的视频"), previewHistoryItem(2, "已经看完的视频")),
                ),
                toView = ProfilePreviewState(loading = false, items = emptyList()),
                favFolders = ProfilePreviewState(
                    loading = false,
                    items = listOf(FavFolder(id = 1, title = "默认收藏夹", containsThis = false, count = 42)),
                ),
            ),
            onVideoClick = {},
            onOpenHistory = {},
            onOpenToView = {},
            onOpenOffline = {},
            onOpenFavFolder = {},
            onSettingsClick = {},
            onOpenSelf = {},
            onRetryAccount = {},
            onRetryHistory = {},
            onRetryToView = {},
            onRetryFavFolders = {},
        )
    }
}

@Preview(showBackground = true, name = "账号信息读取失败")
@Composable
private fun ProfileScreenErrorPreview() {
    BilbyTheme {
        ProfileScreen(
            state = ProfileUiState(accountLoading = false, accountError = "网络错误"),
            onVideoClick = {},
            onOpenHistory = {},
            onOpenToView = {},
            onOpenOffline = {},
            onOpenFavFolder = {},
            onSettingsClick = {},
            onOpenSelf = {},
            onRetryAccount = {},
            onRetryHistory = {},
            onRetryToView = {},
            onRetryFavFolders = {},
        )
    }
}
