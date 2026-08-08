package dev.bilby.ui

/**
 * 分页追加,按 [key] 去掉与已有条目重复的那些。
 *
 * **服务端从不保证页与页之间没有交集。** 评论按热度排序时翻页期间热度分还在变,同一条会
 * 同时落在上一页尾和下一页首;历史记录里同一个 oid 因为重复观看本来就会再次出现。裸拼接的
 * 直接后果是 `LazyColumn` 的 key 唯一性检查抛 `IllegalArgumentException` —— 线上崩过一次,
 * key 是评论的 rpid。
 *
 * 冲突时保留列表里已有的那一份,不用新来的覆盖:旧的那份正挂着用户可见的状态(楼中楼展开、
 * 点赞态),换成新实例会让它们塌回去。
 *
 * seen 集合从当前列表现算,不常驻:刷新时列表被清空,去重范围跟着自动重置,不存在"忘了清
 * seenSet"这条路。
 */
fun <T, K> List<T>.appendDistinctBy(next: List<T>, key: (T) -> K): List<T> {
    if (next.isEmpty()) return this
    if (isEmpty()) return next.distinctBy(key)
    val seen = mapTo(HashSet(size + next.size), key)
    val fresh = next.filter { seen.add(key(it)) }
    return if (fresh.isEmpty()) this else this + fresh
}
