package dev.bilby.ui

import androidx.navigation3.runtime.NavKey

/**
 * 压栈的唯一入口:**同一个 [NavKey] 在栈里只允许出现一次**,已经在栈里就把它移到栈顶。
 *
 * ## 为什么必须唯一
 *
 * Nav3 的 backstack 只是一个 `SnapshotStateList<NavKey>`,它自己不查重(反编译
 * navigation3-runtime 1.1.5 的 `NavBackStack` 核实过:全是原样转发给 base 的 List 操作)。
 * 查重的责任因此落在压栈这一侧,而重复的代价一共有三层:
 *
 * - **两个装饰器都按 key 索引。** `SaveableStateHolderNavEntryDecorator` 用它存
 *   `rememberSaveable`(滚动位置、当前 tab、展开状态),`ViewModelStoreNavEntryDecorator` 用它
 *   索引 ViewModelStore —— 于是栈里两份同 key 的条目**共用同一个 ViewModel 实例**。
 *   `Destinations.kt` 里 [CollectionContents] 那段注释记的就是这个坑的一次发作。
 * - **弹出会替另一份清账。** 那个装饰器的 onPop 调的是 `clearKey(contentKey)`。弹掉上面那份
 *   等于把下面那份的 ViewModel 一起清了,退回去时页面重新加载,而 saveable 那份状态还在,
 *   两者对不上。
 * - **同时组合会抛异常。** `SaveableStateHolder.SaveableStateProvider` 里写着
 *   `require(key !in registries) { "Key $key was used multiple times " }`
 *   (compose runtime-saveable 1.10.2)。转场和预测式返回都会让相邻两页同时在组合里。
 *
 * ## 移到栈顶,不是拒绝压栈
 *
 * 目标已经在栈里时若什么都不做,"点了没反应"就成了正常行为;而弹掉它上面那些页面又太狠 ——
 * 中间那几页是用户自己走过来的。移到栈顶两样都不占:页面到得了,返回回到刚才那一页,
 * key 仍然唯一。这与 Android 的 `REORDER_TO_FRONT` 是同一个语义。
 *
 * 触发它的真实路径:在某人的空间页里,他自己的转发动态常常 @ 他自己,点一下就是同一个
 * `Space(mid)`(实测 mid 1298779265)。当时的表现是画面纹丝不动 —— 目标与栈顶相同,
 * NavDisplay 认为没有变化,不转场,但栈实实在在多了一层,返回键要多按一次才出得去。
 *
 * **[remove] 与 [add] 落在同一帧里**,所以 Nav3 比对前后两份列表时这个 key 两边都在,
 * 不算一次弹出,装饰器也就不会去 clearKey —— 那一页的状态跟着它一起挪到栈顶。
 *
 * @return 栈变了没有。**false 表示目标就是当前这一页**,调用方该说一句(见 `MainActivity`):
 *   一个点得动、按下去有涟漪、然后什么都不发生的链接,读起来和坏掉没有区别。
 */
fun MutableList<NavKey>.pushUnique(key: NavKey): Boolean {
    if (lastOrNull() == key) return false
    remove(key)
    add(key)
    return true
}
