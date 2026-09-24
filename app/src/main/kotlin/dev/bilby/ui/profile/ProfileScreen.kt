package dev.bilby.ui.profile

import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.data.AccountInfo
import dev.bilby.data.AccountRepository
import dev.bilby.data.FavFolderDetail
import dev.bilby.data.FavRepository
import dev.bilby.data.HistoryItem
import dev.bilby.data.HistoryRepository
import dev.bilby.data.ToViewItem
import dev.bilby.data.ToViewRepository
import dev.bilby.offline.OfflineDownloader
import dev.bilby.offline.OfflineItem
import dev.bilby.offline.OfflineStatus
import dev.bilby.offline.OfflineStore
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.fav.FavFolderCard
import dev.bilby.ui.fav.FavFolderCover
import dev.bilby.ui.fav.favFolderMeta
import androidx.compose.foundation.layout.width
import dev.bilby.ui.offline.formatBytes
import dev.bilby.ui.offline.toRowUi
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.formatCount
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.theme.Breakpoints
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.bilby.ui.components.InlineProgress
import dev.bilby.ui.components.LevelBadge
import dev.bilby.ui.components.SectionHeader
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.components.SkeletonLine
import dev.bilby.ui.components.SkeletonPulse
import dev.bilby.ui.components.skeleton
import dev.bilby.ui.components.CoverAspectRatio
import dev.bilby.ui.components.CoverCornerRadius
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.PeopleAlt
import dev.bilby.ui.components.CoinGlyph
import androidx.compose.ui.graphics.Color
import dev.bilby.ui.components.ListCover
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.errorTextRes
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.history.toRowUi
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.toview.toRowUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 历史记录 / 稍后再看 / 收藏夹三个预览区共用的形状:各自独立加载、独立失败。 */
data class ProfilePreviewState<T>(
    val loading: Boolean = true,
    /** 失败说哪一句,存的是资源 id。见 [dev.bilby.ui.errorTextRes]。 */
    @StringRes val error: Int? = null,
    val items: List<T> = emptyList(),
)

data class ProfileUiState(
    val accountLoading: Boolean = true,
    @StringRes val accountError: Int? = null,
    val account: AccountInfo? = null,
    val history: ProfilePreviewState<HistoryItem> = ProfilePreviewState(),
    val toView: ProfilePreviewState<ToViewItem> = ProfilePreviewState(),
    val favFolders: ProfilePreviewState<FavFolderDetail> = ProfilePreviewState(),
    /**
     * 已缓存的视频。**没有 loading 也没有 error** —— 它读的是本地目录,由
     * [dev.bilby.offline.OfflineDownloader] 常驻持有,不存在"正在拉"这个状态。
     */
    val offline: List<OfflineItem> = emptyList(),
    /** 缓存目录占了多少字节。一次目录遍历,只在缓存增删时重算,见 [ProfileViewModel] 的 init。 */
    val offlineUsedBytes: Long = 0L,
)

/**
 * 账号信息、历史记录预览、稍后再看预览、收藏夹列表 —— 四样各自独立加载,一个失败
 * 不连坐其它三个:收藏夹拉不到就只显示那一节的失败态,不影响历史记录或稍后再看照常展示。
 *
 * 历史记录与稍后再看只取**第一页的前 [PreviewCount] 条**,不做翻页:这一页是概览,不是列表,
 * 要看全部就点小节标题进各自的完整页面(DESIGN 1.1:有限集合,不在概览页再造一个能无限滚的
 * 地方)。收藏夹取 created/list 的第一页,横排成一行封面卡片,更多的在收藏夹页。
 */
class ProfileViewModel(
    private val accountRepository: AccountRepository,
    private val historyRepository: HistoryRepository,
    private val toViewRepository: ToViewRepository,
    private val favRepository: FavRepository,
    offlineDownloader: OfflineDownloader,
    private val offlineStore: OfflineStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        refresh()
        // 缓存列表是本地状态,跟着下载器走就行 —— 它不参与上面那四块的"重进就重拉"。
        viewModelScope.launch {
            offlineDownloader.items.collect { items -> _state.update { it.copy(offline = items) } }
        }
        // 已用空间只在"有哪些条目、哪些下完了"变的时候重算:下载器每次进度更新都会发一份新列表,
        // 每次都遍历一遍目录不值。
        viewModelScope.launch {
            offlineDownloader.items
                .map { items -> items.map { it.id to (it.status == OfflineStatus.Completed) }.toSet() }
                .distinctUntilChanged()
                .collect {
                    val bytes = offlineStore.usedBytes()
                    _state.update { it.copy(offlineUsedBytes = bytes) }
                }
        }
    }

    /**
     * 每次重新进入「我的」都把四块概览整个重取。
     *
     * **不做去抖,也不比版本号。** 两者都试过:去抖窗口内的改动看不见,于是按仓库记的版本号
     * 补判;但版本号只认得**经这几个仓库发生的写**,而这一页显示的东西在别处也会变 —— 官方
     * 客户端、网页端,以及播放页那个收藏面板(它走 `VideoActionRepository`,压根不碰
     * `FavRepository`)。补不完,而每补一处就多一处"忘了同步"的可能。
     *
     * 代价比想的小:下面每一块在重取期间都原样留着旧内容,不退回骨架屏(见 [retryHistory]
     * 那几处的写法),所以多打这几个请求不会让页面闪。
     */
    fun refresh() {
        retryAccount()
        retryHistory()
        retryToView()
        retryFavFolders()
    }

    fun retryAccount() {
        _state.update { it.copy(accountLoading = true, accountError = null) }
        viewModelScope.launch {
            when (val result = accountRepository.loadInfo()) {
                is BiliResult.Ok -> _state.update { it.copy(accountLoading = false, account = result.value) }
                // 错误码与原文由 errorTextRes 打进 BiliLog,这里不再各写一份日志。
                else -> _state.update {
                    it.copy(accountLoading = false, accountError = result.errorTextRes("取账号信息"))
                }
            }
        }
    }

    fun retryHistory() {
        _state.update { it.copy(history = it.history.copy(loading = true, error = null)) }
        viewModelScope.launch {
            when (val result = historyRepository.loadPage(0L, 0L)) {
                is BiliResult.Ok -> _state.update {
                    it.copy(
                        history = it.history.copy(
                            loading = false,
                            items = result.value.items.take(PreviewCount),
                        ),
                    )
                }

                else -> _state.update {
                    it.copy(history = it.history.copy(loading = false, error = result.errorTextRes("取历史记录预览")))
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
                    it.copy(toView = it.toView.copy(loading = false, error = result.errorTextRes("取稍后再看预览")))
                }
            }
        }
    }

    fun retryFavFolders() {
        _state.update { it.copy(favFolders = it.favFolders.copy(loading = true, error = null)) }
        viewModelScope.launch {
            // 只取第一页:这一节是一行横滑的卡片,不在概览页翻页。
            when (val result = favRepository.folderPage(1)) {
                is BiliResult.Ok -> _state.update {
                    it.copy(favFolders = it.favFolders.copy(loading = false, items = result.value.items))
                }

                else -> _state.update {
                    it.copy(favFolders = it.favFolders.copy(loading = false, error = result.errorTextRes("取收藏夹列表")))
                }
            }
        }
    }

}

/**
 * 每一节预览几条。**硬编码,不做"加载更多"**(DESIGN 2.7):这一页是概览,要看全的进那一页。
 * 一个能在这里无限往下滚的地方,和 1.1 表里"无限滚动"那一格没有区别,哪怕滚的是自己看过的。
 *
 * 取 3 不取 5:两节预览加上收藏夹要一起放进一屏,5 条时光历史记录就吃掉整屏,
 * 底下两节要滚很久才见得到,那就不再是概览了。缓存那一节同一个数,它在本地排序截取。
 */
private const val PreviewCount = 3

/**
 * 「我的」:账号卡 + 历史记录、稍后再看、收藏夹、缓存四节概览。每一节的标题就是进完整页的
 * 入口。这一页是底部导航的根 tab,没有顶栏,见 [AccountCard]。
 */
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onVideoClick: (String) -> Unit,
    /** 稍后再看预览里的一条。队列是稍后再看,与 [onVideoClick] 分开。 */
    onToViewItemClick: (String) -> Unit,
    /** 缓存预览里的一条。一行是一个分 P,所以给整条缓存,不只给 bvid。 */
    onOfflineItemClick: (OfflineItem) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenToView: () -> Unit,
    onOpenOffline: () -> Unit,
    /** 进消息页。见 MessagesEntry 那一行的说明:入口不带任何计数。 */
    onOpenMessages: () -> Unit,
    /** 账号卡上的「关注」那一格,进关注列表。 */
    onOpenFollowings: () -> Unit,
    /** 账号卡上的「硬币」那一格,进硬币记录。 */
    onOpenCoinLog: () -> Unit,
    onOpenFavFolder: (FavFolderDetail) -> Unit,
    /** 进收藏夹列表页。新建、改名、删除收藏夹都在那里。 */
    onOpenFavFolders: () -> Unit,
    onSettingsClick: () -> Unit,
    /** 点账号那一块进自己的空间。 */
    onOpenSelf: (Long) -> Unit,
    onRetryAccount: () -> Unit,
    onRetryHistory: () -> Unit,
    onRetryToView: () -> Unit,
    onRetryFavFolders: () -> Unit,
    /** 每变一次就回到顶部。重按底栏上当前这一格时由 MainActivity 递增。 */
    scrollToTop: Int = 0,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val scrollState = rememberScrollState()
    // 只认"进这次组合之后又变了":计数器在 MainActivity 手里,切走再切回来时它带着上一次的
    // 值,而 LaunchedEffect 进组合就跑一次。
    var handledScrollToTop by remember { mutableIntStateOf(scrollToTop) }
    LaunchedEffect(scrollToTop) {
        if (scrollToTop != handledScrollToTop) {
            handledScrollToTop = scrollToTop
            scrollState.animateScrollTo(0)
        }
    }
    // **没有顶栏。** 名字、设置、消息都收进最上面那张账号卡([AccountCard]):顶栏里只剩一个
    // 名字和一个齿轮时,名字、等级、签名、消息入口分在顶栏、卡片、分割线下三处,读起来是散的。
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(scrollState),
    ) {
            // 宽屏限宽:卡片横跨整屏时,名字在最左、两个图标在最右,中间是一大片空。
            AdaptiveContent(maxWidth = Breakpoints.ReadableWidth) {
                AccountCard(
                    state = state,
                    onOpenSelf = onOpenSelf,
                    onOpenMessages = onOpenMessages,
                    onOpenFollowings = onOpenFollowings,
                    onOpenCoinLog = onOpenCoinLog,
                    onSettingsClick = onSettingsClick,
                    onRetry = onRetryAccount,
                )
            }

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
                        ToViewSection(state.toView, onToViewItemClick, onOpenToView, onRetryToView)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        // 缓存跟着收藏走:两者都是"我自己存下来的东西",而上面两块是"我看过/打算看的"。
                        FavFoldersSection(state.favFolders, onOpenFavFolder, onOpenFavFolders, onRetryFavFolders)
                        OfflineSection(state.offline, state.offlineUsedBytes, onOfflineItemClick, onOpenOffline)
                    }
                }
            } else {
                HistorySection(state.history, onVideoClick, onOpenHistory, onRetryHistory)
                ToViewSection(state.toView, onToViewItemClick, onOpenToView, onRetryToView)
                FavFoldersSection(state.favFolders, onOpenFavFolder, onOpenFavFolders, onRetryFavFolders)
                OfflineSection(state.offline, state.offlineUsedBytes, onOfflineItemClick, onOpenOffline)
            }

            Spacer(Modifier.height(Spacing.Comfortable))
    }
}

/**
 * 这一页最上面的账号卡:头像、名字加等级、个性签名,下面是关注、硬币、私信三连。整张卡点下去进自己的
 * 空间 —— 这一页看得到的是"我攒了什么",而"我发了什么"在空间页。
 *
 * - **一张卡装下"我"的全部**,取代原先的顶栏(名字、齿轮)+ 头像行 + 分割线 + 分割线下的消息
 *   入口。同一个人的几样东西分在四处,中间还隔一道线,读不出是一组。底色取 surfaceContainer,
 *   卡与下面各节的边界由底色和圆角承担,不再要那道线。
 * - **名字和等级一行**:等级是名字的附注,单独占一行时它读起来像一条标题。
 * - **设置在右上角,和名字一行**。放在签名旁边会把签名挤窄,名字那一行本来就有空。
 *   签名因此能伸到和下面三连同一条右边界。
 * - **关注、硬币、私信三格相连**,形状沿用视频动作栏的分段。私信不带未读计数、不带红点
 *   (DESIGN 1.3)。每一格和设置都是独立的点击目标,不触发整卡的"进空间"。
 * - **签名为空时那一行不画**,不给"这个人很懒"一类的占位:签名本来就可能没填。
 * - 账号还没到或拉取失败时,卡照样在:两个图标不依赖账号,设置在失败时尤其要够得着。
 *   还在读时左边是头像和名字的骨架([SkeletonPulse]),失败时换成一句话加重试。
 *   下面各节不依赖账号信息,不被这一次请求的成败连坐。
 */
@Composable
private fun AccountCard(
    state: ProfileUiState,
    onOpenSelf: (Long) -> Unit,
    onOpenMessages: () -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenCoinLog: () -> Unit,
    onSettingsClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val account = state.account
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier
            .fillMaxWidth()
            // 上边距和左右一样是 16:没有顶栏之后卡片就是页面的第一样东西,贴着状态栏像被裁了一截。
            .padding(Spacing.Comfortable),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            modifier = Modifier
                // **role 要给。** 不给的话读屏只念出里面那几段文字,念不出"这是个可以点的东西",
                // 而这张卡是进自己空间的唯一入口。
                .then(
                    if (account != null) {
                        Modifier.clickable(role = Role.Button) { onOpenSelf(account.mid) }
                    } else {
                        Modifier
                    },
                )
                // 左边 16:头像的左沿和下面预览区封面的左沿对齐(同是 16 + 16,见 PreviewRow)。
                // 右边只留 4:两个图标按钮自带 48dp 触控格,图标本身离卡边已经有 16。
                .padding(start = Spacing.Comfortable, top = Spacing.Comfortable, bottom = Spacing.Comfortable, end = Spacing.Hair),
        ) {
        // 设置在右上角,和名字同一行;签名因此能一直延伸到右边的页边线,和左边一样宽。
        val settingsButton: @Composable () -> Unit = {
            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                    modifier = Modifier.size(AccountActionIconSize),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
            modifier = Modifier.heightIn(min = AccountAvatarSize),
        ) {
            if (account != null) {
                Avatar(url = account.faceUrl, size = AccountAvatarSize)
                Column(modifier = Modifier.weight(1f)) {
                    // 名字、等级、一个 ›:› 是"整张卡点得进去"的唯一视觉提示,跟在名字后面而不是
                    // 卡片右端 —— 右端是设置,它去别处,摆在它旁边会读成第二个按钮。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = account.name,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            LevelBadge(
                                level = account.level,
                                senior = account.isSeniorMember,
                                height = Dimens.LevelBadgeHeight,
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(Dimens.IconInline),
                            )
                        }
                        settingsButton()
                    }
                    if (account.sign.isNotBlank()) {
                        // 右边补 12:卡片右内边距只有 4(留给设置的触控格),补齐之后签名的右沿和
                        // 设置图标的右沿都在离卡边 16 的那条线上,和左边对称。
                        Text(
                            text = account.sign,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = Spacing.Cozy),
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.weight(1f)) {
                    if (state.accountError != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(state.accountError),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                        }
                    } else {
                        // 头像、名字、签名各一块,位置和尺寸同真实内容:账号到了只是颜色在变。
                        SkeletonPulse {
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable)) {
                                Box(Modifier.size(AccountAvatarSize).skeleton(CircleShape))
                                Column(
                                    modifier = Modifier.weight(1f).padding(top = Spacing.Tight),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                                ) {
                                    SkeletonLine(Modifier.fillMaxWidth(0.5f), height = AccountNameSkeletonHeight)
                                    SkeletonLine(Modifier.fillMaxWidth(0.8f))
                                }
                            }
                        }
                    }
                }
                settingsButton()
            }
        }
        // 关注、硬币、消息三连,横跨整张卡。右边补 12,理由同签名那一行。
        AccountActions(
            account = account,
            onOpenFollowings = onOpenFollowings,
            onOpenCoinLog = onOpenCoinLog,
            onOpenMessages = onOpenMessages,
            modifier = Modifier.padding(end = Spacing.Cozy),
        )
        }
    }
}

/**
 * 账号卡底下那一排三连:关注、硬币、消息。**外形照视频页的动作栏**(赞、币、藏那一排):
 * 三段连成一组,整排首尾大圆角、中间小圆角、段间一道细缝,每段字形、数、名字排成一行。同一个
 * 应用里"一排能点的东西"只长一个样子。
 *
 * - 关注:自己一个个点出来的数,点它进关注列表。
 * - 硬币:投币时要用的余额,点它进硬币记录,看每一枚的去向。连成一组之后每一段都得能按,
 *   一段按不动的会被读成坏了。
 * - 消息:私信与回复、@、赞的入口。不带未读数,不带红点(DESIGN 1.3)。
 * - 粉丝数、经验条不放:它们随别人的行为变化,摆在自己的主页上就是一个引人回来刷新的数
 *   (风格指南 §4.2)。
 *
 * 数还没拿到(账号在读、关注数这一次没取到)时那一段只写名字,不写 0 —— 0 是一个真的数。
 */
@Composable
private fun AccountActions(
    account: AccountInfo?,
    onOpenFollowings: () -> Unit,
    onOpenCoinLog: () -> Unit,
    onOpenMessages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = listOf(
        AccountSegmentSpec(
            icon = { tint ->
                Icon(Icons.Outlined.PeopleAlt, contentDescription = null, tint = tint, modifier = Modifier.size(SegmentIconSize))
            },
            value = account?.following?.let { formatSegmentValue(it.toDouble()) },
            label = stringResource(R.string.profile_following),
            onClick = onOpenFollowings,
        ),
        AccountSegmentSpec(
            // 和视频页投币那一格同一个字形(圈里一个 B),空心:余额不是"已投"那种状态。
            icon = { tint ->
                CoinGlyph(
                    tint = tint,
                    filled = false,
                    cutout = MaterialTheme.colorScheme.surfaceContainerHigh,
                    size = SegmentCoinSize,
                )
            },
            value = account?.let { formatSegmentValue(it.coins) },
            label = stringResource(R.string.profile_coins),
            onClick = onOpenCoinLog,
        ),
        AccountSegmentSpec(
            icon = { tint ->
                Icon(Icons.Outlined.Mail, contentDescription = null, tint = tint, modifier = Modifier.size(SegmentIconSize))
            },
            value = null,
            label = stringResource(R.string.message_title),
            onClick = onOpenMessages,
            weight = MessagesSegmentWeight,
        ),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(SegmentGap),
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        segments.forEachIndexed { index, segment ->
            AccountSegment(
                spec = segment,
                shape = segmentShape(index, segments.size),
                modifier = Modifier.weight(segment.weight).fillMaxHeight(),
            )
        }
    }
}

private class AccountSegmentSpec(
    val icon: @Composable (tint: Color) -> Unit,
    /** 这一段的数;为 null 时只写 [label]。 */
    val value: String?,
    val label: String,
    val onClick: () -> Unit,
    /** 这一段在整排里分到的宽度比例。 */
    val weight: Float = 1f,
)

@Composable
private fun AccountSegment(spec: AccountSegmentSpec, shape: Shape, modifier: Modifier = Modifier) {
    Surface(
        onClick = spec.onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        // 字形、数、名字排成一行。竖排时没有数的那一段比旁边两段少一行,居中之后哪一行都对
        // 不上;横排成一行就没有这个问题,整排也矮了一截。
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = SegmentMinHeight).padding(horizontal = Spacing.Tight),
        ) {
            spec.icon(MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
                spec.value?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                Text(
                    spec.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}

/** 三连第 [index] 段的外形,同视频页动作栏:整排首尾 16dp,中间 4dp。 */
private fun segmentShape(index: Int, count: Int): Shape {
    val start = if (index == 0) SegmentOuterCorner else SegmentInnerCorner
    val end = if (index == count - 1) SegmentOuterCorner else SegmentInnerCorner
    return RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
}

private val SegmentOuterCorner = 16.dp
private val SegmentInnerCorner = 4.dp
private val SegmentGap = 2.dp
/**
 * Material 图标的字形只占 24dp 画布里 20dp 的活动区,硬币却是画满自己那个框的圆。两者同写
 * 20 时信封和人像比硬币小一圈;图标取 24、硬币取 20,可见的字形才一样大,同视频页动作栏。
 */
private val SegmentIconSize = 24.dp
private val SegmentCoinSize = 20.dp

/** 消息那一段没有数,和前两段等宽时两边空出一大截。 */
private const val MessagesSegmentWeight = 0.75f

/** 一行排开之后每一段的高度,取触控目标的最小值。 */
private val SegmentMinHeight = 48.dp


/**
 * 三连里的数。**过了 999 一律写成 K、M**,不走全应用的 [formatCount]:那一套中文到一万才进位,
 * 四位数加一位小数的硬币余额("1194.1")在三等分的一格里太长,数字与字形横排之后更放不下。
 * 这样每一格的数最长 5 个字符("999.5"、"12.3K")。
 *
 * 1000 以内的硬币余额保留小数:整数不带 ".0",投过币剩的半枚写成 "42.5"。
 */
private fun formatSegmentValue(value: Double): String = when {
    value >= 999_950 -> "%.1fM".format(value / 1_000_000)
    value >= 1_000 -> "%.1fK".format(value / 1_000)
    value % 1.0 == 0.0 -> value.toLong().toString()
    else -> "%.1f".format(value)
}

/** 账号卡的头像。72 试过,整张卡显得太满;64 仍比列表里的头像大一档。 */
private val AccountAvatarSize = 64.dp

/** 消息与设置两个图标。比默认的 24 大一号,和 72dp 的头像、放大了的名字相称;触控格仍是 48。 */
private val AccountActionIconSize = 28.dp

/** 名字那一行的骨架高度,对应 titleLarge 的字形高。 */
private val AccountNameSkeletonHeight = 18.dp

/** 历史记录预览:最近 [PreviewCount] 条,点标题进完整的历史记录页。 */
@Composable
private fun HistorySection(
    state: ProfilePreviewState<HistoryItem>,
    onVideoClick: (String) -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
) {
    PreviewSection(
        title = stringResource(R.string.history_title),
        state = state,
        emptyText = stringResource(R.string.history_empty),
        onOpen = onOpen,
        onRetry = onRetry,
    ) { item, index, count ->
        PreviewRow(item = item.toRowUi(), index = index, count = count, onClick = { onVideoClick(item.bvid) })
    }
}

/** 稍后再看预览:同上,点标题进完整的稍后再看列表。 */
@Composable
private fun ToViewSection(
    state: ProfilePreviewState<ToViewItem>,
    onVideoClick: (String) -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
) {
    PreviewSection(
        title = stringResource(R.string.tab_toview),
        state = state,
        emptyText = stringResource(R.string.toview_empty),
        onOpen = onOpen,
        onRetry = onRetry,
        hideWhenEmpty = true,
    ) { item, index, count ->
        // 剧集与课程在这里同样打不开,判据同稍后再看页。
        PreviewRow(
            item = item.toRowUi(),
            index = index,
            count = count,
            enabled = item.playable,
            onClick = { if (item.playable) onVideoClick(item.bvid) },
        )
    }
}

/**
 * 预览区的一行:和播放页队列同一种带底色的分段列表项(见 `EpisodeList.QueueRowItem`)。
 *
 * **预览用分段卡片,完整页用平铺行。** 这几条是一屏概览里的一小组,底色和首尾圆角把它们圈成
 * 一块,和上面的账号卡、旁边的收藏夹卡片同一种"一块一块"的读法;完整页是一整屏的列表,
 * 那里一行一张卡只会让列表变成一摞补丁。
 *
 * 分段容器的左沿对齐页边(16dp),封面在容器里再缩进 16dp(lists.md 的 leading padding),
 * 于是和账号卡里头像的左沿落在同一条线上(卡片 16dp + 卡内 16dp)。
 *
 * 行里只有标题和一行状态(看到哪了、已看完),时间、UP 名、计数都不带:概览里要认出的是
 * "哪一条",细节在完整页。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewRow(
    item: VideoRowUi,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    SegmentedListItem(
        onClick = onClick,
        enabled = enabled,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap),
        // 居中,理由同 QueueRowItem:两行标题加一行状态就过了 88dp,默认会把封面顶到上沿。
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = {
            ListCover(
                url = item.coverUrl,
                durationText = item.durationText,
                progressFraction = item.progressFraction,
                width = PreviewCoverWidth,
                cornerRadius = PreviewCoverCorner,
                typeBadge = item.typeBadge,
            )
        },
        supportingContent = item.meta?.let { meta ->
            {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 预览行的封面,同播放页队列条目:16:9 下约 54dp 高,和两行标题齐平。 */
private val PreviewCoverWidth = 96.dp

/** 同队列条目:列表项自己的圆角在 4dp 到 16dp 之间变,封面取中间一档。 */
private val PreviewCoverCorner = 8.dp

/**
 * 缓存预览。与其余几节同一个形状:标题进缓存页,下面是最近缓存的几条。
 *
 * **一条都没有时整节不画**,和稍后再看同一条判据:没缓存过东西的人不需要先认识"离线缓存"
 * 这个概念,才能看懂自己页面上多出来的一行字。
 *
 * 条数与已用空间写在标题行右端:缓存会攒到几十条,预览的三条看不出全貌,"存了多少、占了多少"
 * 是这一节在概览页真正要回答的。它原先是一行 `ListItem` 入口外加一条通栏分割线,和上面几节
 * 长得不一样,读起来像另一类东西。
 */
@Composable
private fun OfflineSection(
    items: List<OfflineItem>,
    usedBytes: Long,
    onItemClick: (OfflineItem) -> Unit,
    onOpen: () -> Unit,
) {
    if (items.isEmpty()) return
    // 最近加进来的在前。在途的也算:刚点了缓存的人回到这一页,要找的正是那几条。
    val preview = items.sortedByDescending { it.createdAtMillis }.take(PreviewCount)
    Column(modifier = Modifier.padding(bottom = Spacing.Comfortable)) {
        SectionHeader(
            title = stringResource(R.string.offline_title),
            onTitleClick = onOpen,
            modifier = Modifier.padding(horizontal = Spacing.Comfortable),
        ) {
            Text(
                text = stringResource(R.string.offline_count, items.size) + MetaSeparator + formatBytes(usedBytes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Column(modifier = Modifier.padding(horizontal = Spacing.Comfortable)) {
            preview.forEachIndexed { index, item ->
                PreviewRow(item = item.toRowUi(), index = index, count = preview.size, onClick = { onItemClick(item) })
            }
        }
    }
}

/**
 * 预览区共用的骨架:小节标题、loading/error/empty 三态、内容。
 *
 * **标题本身就是进完整页的入口**([SectionHeader] 的 `onTitleClick`,带一个右尖括号),
 * 替掉了原先行尾那颗「查看全部」:那颗按钮和标题说的是同一个去处,却要人把视线从行首挪到行尾。
 * 条数为 0 时标题不可点 —— 链接到一个空页面没有意义。
 */
@Composable
private fun <T> PreviewSection(
    title: String,
    state: ProfilePreviewState<T>,
    emptyText: String,
    onOpen: () -> Unit,
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
    /** 一行。给出下标与条数,分段列表项按它们取首尾圆角。 */
    itemRow: @Composable (item: T, index: Int, count: Int) -> Unit,
) {
    if (hideWhenEmpty && !state.loading && state.error == null && state.items.isEmpty()) return
    // **间距记在这一节的下面,不是上面。** 记在上面的话,排在最前的那一节会在它和上面那个
    // 入口之间多出一段谁也没要的留白;而节与节之间的距离两种写法是一样的。末尾多出的一段
    // 落在页面底部,那里本来就有收尾的 Spacer。
    Column(modifier = Modifier.padding(bottom = Spacing.Comfortable)) {
        SectionHeader(
            title = title,
            onTitleClick = onOpen.takeIf { state.items.isNotEmpty() },
            modifier = Modifier.padding(horizontal = Spacing.Comfortable),
        )
        SectionBody(state, onRetry, emptyText, skeleton = { PreviewRowsSkeleton() }) { items ->
            Column(modifier = Modifier.padding(horizontal = Spacing.Comfortable)) {
                items.forEachIndexed { index, item -> itemRow(item, index, items.size) }
            }
        }
    }
}

/**
 * 预览区读取中的样子:[PreviewCount] 条和 [PreviewRow] 同形的分段行,封面、标题、状态各一块。
 * 用同一个分段列表项去画,底色、圆角、内边距一分不差,内容到了只有占位块换成真的。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewRowsSkeleton() {
    SkeletonPulse {
        Column(modifier = Modifier.padding(horizontal = Spacing.Comfortable)) {
            repeat(PreviewCount) { index ->
                SegmentedListItem(
                    shapes = ListItemDefaults.segmentedShapes(index = index, count = PreviewCount),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap),
                    verticalAlignment = Alignment.CenterVertically,
                    leadingContent = {
                        Box(
                            Modifier
                                .width(PreviewCoverWidth)
                                .aspectRatio(CoverAspectRatio)
                                .skeleton(RoundedCornerShape(PreviewCoverCorner)),
                        )
                    },
                    supportingContent = { SkeletonLine(Modifier.fillMaxWidth(0.4f).padding(top = Spacing.Hair)) },
                ) {
                    SkeletonLine(Modifier.fillMaxWidth(0.85f))
                }
            }
        }
    }
}

/**
 * 一节的四态:骨架 / 出错 / 空 / 内容。**四态之间淡入淡出**,不是硬切。
 *
 * 这一页进一次就重取一次四块(见 [ProfileViewModel.refresh]),所以这几态之间的切换是每次
 * 打开「我的」都会发生的事,而硬切的表现是同一块位置上的字凭空换掉一行 —— 读起来像刚才那行
 * 从来没存在过。用 [Crossfade] 而不是 `AnimatedContent`:换的是同一块区域的几种填充,没有
 * 方向可言,而 `AnimatedContent` 默认还要连尺寸一起过渡,那份 `SizeTransform` 只会多抖一下。
 * spec 取 `motionScheme` 的 effects 档:淡入淡出是效果,不是空间位移(风格指南 §6 那张表)。
 *
 * **切换的键是"哪一态",文案带在键里。** 直接在分支里读外面的 `state.error` 不行:淡出还没
 * 走完时旧分支仍在组合,而那一刻 error 已经是 null 了。
 *
 * 手里已经有内容时重取不退回骨架:重进这一页就是最常见的重取时机,退回去的话每次进来都要
 * 先看着几节各闪一下。等新的一份回来直接换掉即可。
 */
@Composable
private fun <T> SectionBody(
    state: ProfilePreviewState<T>,
    onRetry: () -> Unit,
    emptyText: String,
    /** 首载时的占位,和内容同形,见 ui/components/Skeleton.kt。 */
    skeleton: @Composable () -> Unit,
    /** 内容那一态怎么排:预览区是一列行,收藏夹是一行横滑的卡片。 */
    content: @Composable (List<T>) -> Unit,
) {
    val phase: SectionPhase = when {
        state.loading && state.items.isEmpty() -> SectionPhase.Loading
        state.error != null -> SectionPhase.Failed(state.error)
        state.items.isEmpty() -> SectionPhase.Empty
        else -> SectionPhase.Content
    }
    Crossfade(
        targetState = phase,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "profile-section",
    ) { current ->
        when (current) {
            SectionPhase.Loading -> skeleton()
            is SectionPhase.Failed -> InlineSectionError(stringResource(current.message), onRetry)
            SectionPhase.Empty -> InlineSectionMessage(emptyText)
            // 内容那一支读的是最新的 items,不是 target 里的快照:淡入期间又回来一页的话,
            // 该显示的是新的那一份。
            SectionPhase.Content -> content(state.items)
        }
    }
}

/** [SectionBody] 的四态。错误文案带在态里,理由见那个函数。 */
private sealed interface SectionPhase {
    data object Loading : SectionPhase

    data class Failed(@StringRes val message: Int) : SectionPhase

    data object Empty : SectionPhase

    data object Content : SectionPhase
}

/**
 * 收藏夹:一行横滑的封面卡片,照 PiliPlus「我的」页。竖排的话每个收藏夹占一整行,十来个夹子
 * 就把这一页撑成一张长列表,而这一页是一屏看完的概览。
 *
 * 标题叫「收藏夹」,与它点进去那一页同名 —— 原先写「收藏」,读起来像"收藏过的视频"。
 */
@Composable
private fun FavFoldersSection(
    state: ProfilePreviewState<FavFolderDetail>,
    onOpenFolder: (FavFolderDetail) -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
) {
    // **间距记在这一节的下面,不是上面。** 记在上面的话,排在最前的那一节会在它和上面那个
    // 入口之间多出一段谁也没要的留白;而节与节之间的距离两种写法是一样的。末尾多出的一段
    // 落在页面底部,那里本来就有收尾的 Spacer。
    Column(modifier = Modifier.padding(bottom = Spacing.Comfortable)) {
        SectionHeader(
            title = stringResource(R.string.fav_folders_title),
            // **一个都没有时也可点**,与上面几节按 items 非空判不同:那一页挂着「新建收藏夹」,
            // 而一个还没建过收藏夹的人,恰好最需要走进去。
            onTitleClick = onOpen,
            modifier = Modifier.padding(horizontal = Spacing.Comfortable),
        )
        // 同 PreviewSection 一份四态,见 [SectionBody]。
        SectionBody(
            state,
            onRetry,
            stringResource(R.string.profile_favorites_empty),
            skeleton = { FolderCardsSkeleton() },
        ) { folders ->
            // **只有一个(多半就是默认收藏夹)时横着排**:封面在左、名字和条数在右,和上面几节的
            // 预览行同一个样子。一张竖卡孤零零地站在左边,右边空着一大片。
            val only = folders.singleOrNull()
            if (only != null) {
                Box(modifier = Modifier.padding(horizontal = Spacing.Comfortable)) {
                    SingleFolderRow(only, onClick = { onOpenFolder(only) })
                }
                return@SectionBody
            }
            // 左右留白放进 contentPadding 而不是外层 padding:滑动时卡片要能滑到屏幕边缘,
            // 静止时第一张仍和上面的标题对齐。
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.Comfortable),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            ) {
                items(folders, key = { it.id }) { folder ->
                    FavFolderCard(folder, onClick = { onOpenFolder(folder) })
                }
            }
        }
    }
}

/** 只有一个收藏夹时的那一行。形状同 [PreviewRow]:单独一段的分段列表项,四角都是大圆角。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SingleFolderRow(folder: FavFolderDetail, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = {
            FavFolderCover(
                url = folder.coverUrl,
                private = !folder.isPublic,
                modifier = Modifier.width(PreviewCoverWidth),
            )
        },
        supportingContent = {
            Text(
                favFolderMeta(folder),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        },
    ) {
        Text(folder.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 收藏夹读取中的样子:和 [FavFolderCard] 同尺寸的几张卡。收藏夹有几个事先不知道,按一屏
 * 放得下的张数画,多的滑出视口外;真的只有一个时,内容换成横排那一行。
 */
@Composable
private fun FolderCardsSkeleton() {
    SkeletonPulse {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .padding(horizontal = Spacing.Comfortable),
        ) {
            repeat(FolderSkeletonCount) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    modifier = Modifier
                        .width(Dimens.ListCoverWidth + Spacing.Tight * 2)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(Spacing.Tight),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(CoverAspectRatio)
                            .skeleton(RoundedCornerShape(CoverCornerRadius)),
                    )
                    SkeletonLine(Modifier.fillMaxWidth(0.7f))
                    SkeletonLine(Modifier.fillMaxWidth(0.4f))
                }
            }
        }
    }
}

private const val FolderSkeletonCount = 3

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
    // 故意取一个和 oid 不同的值:两者是否相等没有证据,预览里写成 oid 会把「它们一样」
    // 这个未经证实的判断留在代码里当样板抄。
    kid = oid + 1,
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
                    coins = 42.5,
                    following = 128,
                ),
                history = ProfilePreviewState(
                    loading = false,
                    items = listOf(previewHistoryItem(1, "看到一半的视频"), previewHistoryItem(2, "已经看完的视频")),
                ),
                toView = ProfilePreviewState(loading = false, items = emptyList()),
                favFolders = ProfilePreviewState(
                    loading = false,
                    items = listOf(
                        FavFolderDetail(id = 1, title = "默认收藏夹", intro = "", count = 42, attr = 0),
                        FavFolderDetail(id = 2, title = "私密的收藏夹", intro = "", count = 7, attr = 3),
                    ),
                ),
            ),
            onVideoClick = {},
            onToViewItemClick = {},
            onOfflineItemClick = {},
            onOpenHistory = {},
            onOpenToView = {},
            onOpenOffline = {},
            onOpenMessages = {},
            onOpenFavFolder = {},
            onOpenFavFolders = {},
            onOpenFollowings = {},
            onOpenCoinLog = {},
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
            state = ProfileUiState(accountLoading = false, accountError = R.string.error_network),
            onVideoClick = {},
            onToViewItemClick = {},
            onOfflineItemClick = {},
            onOpenHistory = {},
            onOpenToView = {},
            onOpenOffline = {},
            onOpenMessages = {},
            onOpenFavFolder = {},
            onOpenFavFolders = {},
            onOpenFollowings = {},
            onOpenCoinLog = {},
            onSettingsClick = {},
            onOpenSelf = {},
            onRetryAccount = {},
            onRetryHistory = {},
            onRetryToView = {},
            onRetryFavFolders = {},
        )
    }
}
