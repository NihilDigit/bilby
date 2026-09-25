package dev.bilby.ui.fav

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.data.FavFolderDetail
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.components.RefreshAction
import dev.bilby.ui.components.SearchField

/**
 * 收藏夹内容页的顶栏:返回、夹内搜索、管理菜单;搜索态下标题位换成输入框。
 *
 * 夹内搜索是这一页内容的过滤,不是全局搜索,所以取 M3 的 search icon button 形态,与空间页的
 * 投稿搜索同一个做法(风格指南 §2.4b 末段,`SpaceScreen` 的 `SpaceTopBar`)。**筛选生效期间
 * 顶栏一直是输入框**,收起只有关闭按钮一条路,它同时清掉关键词 —— "输入框不见了"和"没在筛"
 * 永远是同一件事。
 *
 * 搜索态不留菜单:输入框要那一截宽度,而正在筛的人此刻不是要改名或删夹子。
 *
 * @param folder 收藏夹本身。第一页回来之前是 null,那时菜单不可用 —— 编辑要它的公开性与
 *   是否默认,删除要它的条数。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val searching = searchOpen || appliedKeyword.isNotBlank()
    var confirmingClean by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    TopAppBar(
        title = {
            if (searching) {
                SearchField(
                    value = keyword,
                    onValueChange = onKeywordChange,
                    placeholder = stringResource(Res.string.fav_search_hint),
                    onSearch = onSearch,
                    focusRequester = focusRequester,
                )
                // 点图标打开时直接进输入;筛选生效着、从播放页回到这一页时不抢焦点 ——
                // 那时人是来看结果的,弹出键盘会盖住一半列表。
                LaunchedEffect(Unit) {
                    if (keyword.isEmpty()) focusRequester.requestFocus()
                }
            } else {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.action_back),
                )
            }
        },
        actions = {
            RefreshAction(refreshing, onRefresh)
            if (searching) {
                IconButton(
                    onClick = {
                        searchOpen = false
                        onCloseSearch()
                    },
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.fav_search_close))
                }
            } else {
                IconButton(onClick = { searchOpen = true }) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(Res.string.fav_search))
                }
                FolderMenu(
                    folder = folder,
                    cleaning = cleaning,
                    onEdit = onEdit,
                    onCleanInvalid = { confirmingClean = true },
                    onDelete = onDelete,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(),
        scrollBehavior = scrollBehavior,
    )

    // 清掉的是已经打不开的条目,但那也是"我收过什么"的记录,清掉就找不回来,所以问一句。
    if (confirmingClean) {
        AlertDialog(
            onDismissRequest = { confirmingClean = false },
            title = { Text(stringResource(Res.string.fav_clean_invalid_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClean = false
                        onCleanInvalid()
                    },
                ) { Text(stringResource(Res.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClean = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
        )
    }
}

/**
 * 管理菜单。M3E vertical menu,项的形状按首末取,同 [FavFoldersScreen] 行尾那一个。
 * 默认收藏夹删不掉,删除项不给(理由同那边)。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderMenu(
    folder: FavFolderDetail?,
    cleaning: Boolean,
    onEdit: (FavFolderDetail) -> Unit,
    onCleanInvalid: () -> Unit,
    onDelete: (FavFolderDetail) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val deletable = folder != null && !folder.isDefault
    Box {
        IconButton(onClick = { open = true }, enabled = folder != null) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = stringResource(Res.string.fav_folder_actions, folder?.title.orEmpty()),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MenuDefaults.shape,
            containerColor = MenuDefaults.containerColor,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.fav_folder_edit_info)) },
                onClick = {
                    open = false
                    folder?.let(onEdit)
                },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                shape = MenuDefaults.leadingItemShape,
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.fav_clean_invalid)) },
                onClick = {
                    open = false
                    onCleanInvalid()
                },
                enabled = !cleaning,
                leadingIcon = { Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
                shape = if (deletable) MenuDefaults.middleItemShape else MenuDefaults.trailingItemShape,
            )
            if (deletable) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.fav_folder_delete_title)) },
                    onClick = {
                        open = false
                        folder?.let(onDelete)
                    },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                    shape = MenuDefaults.trailingItemShape,
                )
            }
        }
    }
}
