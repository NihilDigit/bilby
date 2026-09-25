package dev.bilby.ui.follow

import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.data.DEFAULT_GROUP_ID
import dev.bilby.data.FollowGroup
import dev.bilby.data.FollowRepository
import dev.bilby.data.RelationRepository
import dev.bilby.data.SPECIAL_GROUP_ID
import dev.bilby.data.UpBrief
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「设置某人的分组」这件事的全部状态与写回逻辑。**关注列表页和空间页共用这一份。**
 *
 * 不是为了省几行才抽出来的:写回是**覆盖式**的(`FollowRepository.replaceGroupsOf`),
 * 传过去的那一份就是这个人此后所属的全部分组。这意味着面板必须先把当前全量读齐、`loading`
 * 期间不能让人按保存、拿到的结果还要按 mid 核对是不是过期的 —— 三条里漏掉任何一条,后果
 * 都是静默摘掉用户没看见的那些分组。在两个 ViewModel 里各写一遍,迟早有一边漏。
 *
 * 不做成 ViewModel:它没有自己的生命周期,跟着宿主那个走。[scope] 由宿主传进来。
 */
class GroupPickerController(
    private val followRepository: FollowRepository,
    private val relationRepository: RelationRepository,
    private val scope: CoroutineScope,
) {

    private val _groups = MutableStateFlow<List<FollowGroup>>(emptyList())

    /**
     * 可选的分组。**「默认分组」不在里面**:它装的是没被分进任何分组的人,不是一个能勾的
     * 归属;要把人从所有分组里拿出来,做法是一个都不勾(仓库那侧会替换成 "0")。关注列表页
     * 的筛选 chip 用的是同一份,那里的理由是它和「全部关注」几乎重合(实测 288 = 默认分组
     * 273 + 特别关注 15),两个几乎一样的档只会让人先比较再选。
     *
     * 「特别关注」在里面,它就是一个普通 tagid。
     */
    val groups: StateFlow<List<FollowGroup>> = _groups.asStateFlow()

    private val _picker = MutableStateFlow<GroupPickerState?>(null)
    val picker: StateFlow<GroupPickerState?> = _picker.asStateFlow()

    fun loadGroups() {
        scope.launch {
            when (val result = followRepository.groups()) {
                is BiliResult.Ok ->
                    _groups.value = result.value.filterNot { it.id == DEFAULT_GROUP_ID }
                // 取不到就是一个空面板,宿主页面本身照常能用,不当成整页的错误。
                else -> BiliLog.w("取关注分组失败: $result")
            }
        }
    }

    /** 打开面板并把这个人当前的分组读齐。读完之前 [GroupPickerState.loading] 挡着保存。 */
    fun open(up: UpBrief) {
        _picker.value = GroupPickerState(up = up)
        if (_groups.value.isEmpty()) loadGroups()
        scope.launch {
            val result = relationRepository.groupsOf(up.mid)
            _picker.update { current ->
                // 面板已经关掉、或者换了个人时,这次结果就是过期的。
                val picker = current?.takeIf { it.up.mid == up.mid } ?: return@update current
                when (result) {
                    is BiliResult.Ok -> picker.copy(
                        selected = result.value.groupIds,
                        special = result.value.special,
                        original = result.value.groupIds +
                            if (result.value.special) setOf(SPECIAL_GROUP_ID) else emptySet(),
                        loading = false,
                    )

                    is BiliResult.ApiError ->
                        picker.copy(loading = false, error = "${result.message}(${result.code})")

                    is BiliResult.Failure ->
                        picker.copy(loading = false, error = result.cause.message ?: "网络错误")
                }
            }
            if (result !is BiliResult.Ok) BiliLog.w("取某人的分组失败: $result")
        }
    }

    fun close() {
        _picker.value = null
    }

    /** 「特别关注」勾在 [GroupPickerState.special] 上,不进那个集合 —— 提交时才并回去。 */
    fun toggle(groupId: Long) = _picker.update { picker ->
        when {
            picker == null -> null
            groupId == SPECIAL_GROUP_ID -> picker.copy(special = !picker.special)
            groupId in picker.selected -> picker.copy(selected = picker.selected - groupId)
            else -> picker.copy(selected = picker.selected + groupId)
        }
    }

    /**
     * 写回。
     *
     * **成功之后不重取分组名单,人数在本地按增量改。** 写完立刻回头读拿到的是写之前那一份:
     * 实测加进特别关注后人数纹丝不动,移出之后反而 +1(那正是移出前的值),重启应用才对得上。
     * 而这一次改了什么客户端完全知道 —— 进了哪几组、出了哪几组,由 [GroupPickerState.original]
     * 和提交的那一份一比就有,不必问服务端。同 CLAUDE.md 对点赞/投币/收藏的那一条。
     *
     * @param onSaved 宿主自己还要做的事。
     */
    fun save(onSaved: () -> Unit = {}) {
        val picker = _picker.value ?: return
        // 读齐之前、正在写、或者压根没读到,都不能提交:那时 selected 不是全量,
        // 写回去等于把没读到的分组全摘掉。
        if (picker.loading || picker.saving || picker.error != null) return
        _picker.value = picker.copy(saving = true)
        scope.launch {
            // 特别关注就是 tagid -10,跟着这一份一起覆盖提交,**不另外调 special 接口** ——
            // 调了会撞 `22004 已经设置该属性了`,见 notes/relation-groups.md 1.7。
            val tagIds =
                picker.selected + if (picker.special) setOf(SPECIAL_GROUP_ID) else emptySet()
            when (val result = followRepository.replaceGroupsOf(picker.up.mid, tagIds)) {
                is BiliResult.Ok -> {
                    _picker.value = null
                    applyCountDelta(before = picker.original, after = tagIds)
                    onSaved()
                }

                is BiliResult.ApiError -> fail(picker, "${result.message}(${result.code})")
                is BiliResult.Failure -> fail(picker, result.cause.message ?: "网络错误")
            }
        }
    }

    /** 一个人进出若干分组,受影响的每一组各 ±1。没动过的组一个都不碰。 */
    private fun applyCountDelta(before: Set<Long>, after: Set<Long>) = _groups.update { groups ->
        groups.map { group ->
            when {
                group.id in after && group.id !in before -> group.copy(count = group.count + 1)
                // 下限 0:人数是上一次读回来的快照,期间别处取关过的话它本来就偏大,
                // 减到负数会把一个显示问题变成一个看起来像 bug 的数字。
                group.id in before && group.id !in after ->
                    group.copy(count = (group.count - 1).coerceAtLeast(0))

                else -> group
            }
        }
    }

    private fun fail(picker: GroupPickerState, message: String) {
        BiliLog.w("设置分组失败: $message")
        _picker.update { current ->
            // 面板已经关掉或换了个人时,这次失败不该再写回去。
            if (current == null || current.up.mid != picker.up.mid) current
            else current.copy(saving = false, error = message)
        }
    }
}
