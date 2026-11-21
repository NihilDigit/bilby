package dev.bilby.ui.fav

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AppBarRow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.data.FavFolderDetail
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.BackHandler
import dev.bilby.ui.CaptionDragHeight
import dev.bilby.ui.LocalWindowCaption
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.RegisterPageSearch
import dev.bilby.ui.components.SearchField
import dev.bilby.ui.components.TopBarRefreshButton
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.rememberCaptionSlot
import dev.bilby.ui.theme.tabular
import dev.bilby.ui.windowDragArea

/**
 * 收藏夹内容页的顶栏：常规态为带条数/公开性副标题的 M3E [MediumFlexibleTopAppBar] + [AppBarRow] 自适应溢出操作栏；
 * 搜索态切回紧凑输入框，收起只有关闭按钮一条路。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FavFolderTopBar(
    title: String,
    folder: FavFolderDetail?,
    keyword: String,
    appliedKeyword: String,
    cleaning: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onEdit: (FavFolderDetail) -> Unit,
    onCleanInvalid: () -> Unit,
    onDelete: (FavFolderDetail) -> Unit,
    onBack: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchFocusNonce by remember { mutableIntStateOf(0) }
    val searching = searchOpen || appliedKeyword.isNotBlank()
    val closeSearch: () -> Unit = {
        searchOpen = false
        onCloseSearch()
    }
    RegisterPageSearch(
        onTrigger = {
            searchOpen = true
            searchFocusNonce++
        },
    )
    BackHandler(enabled = searching, onBack = closeSearch)
    var confirmingClean by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LocalWindowCaption.current?.Host()
    val captionSlot = rememberCaptionSlot()
    val isTouch = LocalPointerSource.current.isTouchLike

    val navigationIcon: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val backLabel = stringResource(Res.string.action_back)
            BilbyTooltip(text = backLabel, shortcut = "Alt+←") {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = backLabel,
                    )
                }
            }
            TopBarRefreshButton(refreshing = refreshing, onRefresh = onRefresh)
        }
    }

    val barActions: @Composable RowScope.() -> Unit = {
        val searchLabel = stringResource(Res.string.fav_search)
        val closeLabel = stringResource(Res.string.fav_search_close)
        val actionsLabel = stringResource(Res.string.fav_folder_actions, folder?.title.orEmpty())
        val editLabel = stringResource(Res.string.fav_folder_edit_info)
        val cleanLabel = stringResource(Res.string.fav_clean_invalid)
        val deleteLabel = stringResource(Res.string.fav_folder_delete_title)
        val appBarRowContent: @Composable () -> Unit = {
            AppBarRow(
                maxItemCount = 2,
                overflowIndicator = { menuState ->
                    BilbyTooltip(text = actionsLabel) {
                        IconButton(onClick = { menuState.show() }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = actionsLabel)
                        }
                    }
                },
            ) {
                if (searching) {
                    customItem(
                        appbarContent = {
                            BilbyTooltip(text = closeLabel, shortcut = "Esc") {
                                IconButton(onClick = closeSearch) {
                                    Icon(Icons.Outlined.Close, contentDescription = closeLabel)
                                }
                            }
                        },
                        menuContent = { menuState ->
                            DropdownMenuItem(
                                text = { Text(closeLabel) },
                                onClick = {
                                    menuState.dismiss()
                                    closeSearch()
                                },
                                leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null) },
                            )
                        },
                    )
                } else {
                    customItem(
                        appbarContent = {
                            BilbyTooltip(text = searchLabel, shortcut = "Ctrl+F") {
                                IconButton(onClick = { searchOpen = true }) {
                                    Icon(Icons.Outlined.Search, contentDescription = searchLabel)
                                }
                            }
                        },
                        menuContent = { menuState ->
                            DropdownMenuItem(
                                text = { Text(searchLabel) },
                                onClick = {
                                    menuState.dismiss()
                                    searchOpen = true
                                },
                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            )
                        },
                    )
                    if (folder != null) {
                        val deletable = !folder.isDefault
                        val totalOverflow = 2 + if (deletable) 1 else 0
                        customItem(
                            appbarContent = {
                                BilbyTooltip(text = editLabel) {
                                    IconButton(onClick = { onEdit(folder) }) {
                                        Icon(Icons.Outlined.Edit, contentDescription = editLabel)
                                    }
                                }
                            },
                            menuContent = { menuState ->
                                DropdownMenuItem(
                                    text = { Text(editLabel) },
                                    onClick = {
                                        menuState.dismiss()
                                        onEdit(folder)
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                    shape = menuItemShape(0, totalOverflow),
                                )
                            },
                        )
                        customItem(
                            appbarContent = {
                                BilbyTooltip(text = cleanLabel) {
                                    IconButton(onClick = { confirmingClean = true }, enabled = !cleaning) {
                                        Icon(Icons.Outlined.CleaningServices, contentDescription = cleanLabel)
                                    }
                                }
                            },
                            menuContent = { menuState ->
                                DropdownMenuItem(
                                    text = { Text(cleanLabel) },
                                    onClick = {
                                        menuState.dismiss()
                                        confirmingClean = true
                                    },
                                    enabled = !cleaning,
                                    leadingIcon = { Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
                                    shape = menuItemShape(1, totalOverflow),
                                )
                            },
                        )
                        if (deletable) {
                            customItem(
                                appbarContent = {
                                    BilbyTooltip(text = deleteLabel) {
                                        IconButton(onClick = { onDelete(folder) }) {
                                            Icon(Icons.Outlined.DeleteOutline, contentDescription = deleteLabel)
                                        }
                                    }
                                },
                                menuContent = { menuState ->
                                    DropdownMenuItem(
                                        text = { Text(deleteLabel) },
                                        onClick = {
                                            menuState.dismiss()
                                            onDelete(folder)
                                        },
                                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                                        shape = menuItemShape(2, totalOverflow),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        if (isTouch) {
            appBarRowContent()
        } else {
            MaterialExpressiveTheme(
                colorScheme = MaterialTheme.colorScheme,
                motionScheme = FavTopBarDesktopMenuMotionScheme,
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes,
                content = appBarRowContent,
            )
        }
        captionSlot.buttons?.invoke()
    }

    if (searching) {
        TopAppBar(
            modifier = captionSlot.modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    closeSearch()
                    true
                } else {
                    false
                }
            },
            title = {
                SearchField(
                    value = keyword,
                    onValueChange = onKeywordChange,
                    placeholder = stringResource(Res.string.fav_search_hint),
                    onSearch = onSearch,
                    focusRequester = focusRequester,
                )
                LaunchedEffect(searching, searchFocusNonce) {
                    if (searching && (keyword.isEmpty() || searchFocusNonce > 0)) {
                        runCatching { focusRequester.requestFocus() }
                    }
                }
            },
            navigationIcon = navigationIcon,
            actions = barActions,
            colors = TopAppBarDefaults.topAppBarColors(),
            scrollBehavior = scrollBehavior,
        )
    } else {
        MediumFlexibleTopAppBar(
            modifier = captionSlot.modifier,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = CaptionDragHeight)
                        .windowDragArea(captionSlot.atTop),
                ) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            subtitle = {
                folder?.let {
                    Text(
                        text = favFolderMeta(it),
                        style = MaterialTheme.typography.labelMedium.tabular,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = navigationIcon,
            actions = barActions,
            colors = TopAppBarDefaults.topAppBarColors(),
            scrollBehavior = scrollBehavior,
        )
    }

    // 清掉的是已经打不开的条目,但那也是"我收过什么"的记录,清掉就找不回来,所以问一句。
    if (confirmingClean) {
        AlertDialog(
            onDismissRequest = { confirmingClean = false },
            title = { Text(stringResource(Res.string.fav_clean_invalid_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmingClean = false
                        onCleanInvalid()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(Res.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClean = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object FavTopBarDesktopMenuMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
}
