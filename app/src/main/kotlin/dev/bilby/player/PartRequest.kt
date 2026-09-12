package dev.bilby.player

/**
 * 用户指名要放哪一 P,**一次性的**。
 *
 * 缓存列表里一行就是一个分 P,点哪一行就该播哪一行;而路由的身份只有 bvid(设计文档
 * 「决定 1」),分 P 是播放层的内部状态。指名的意图因此不能挂在导航参数上 —— 那样它会跟着
 * 页面一起被复原,转屏之后又执行一遍,把服务端记着的那一 P 顶回用户几分钟前点的那一行。
 *
 * 所以它走这里:点的那一下写进来,[AudioPlaybackService.openVideo] 一进门就取走一次,取完
 * 即空。页内切 P 不经过这里,它本来就是一条命令([AudioPlaybackService.ACTION_PLAY_PART])。
 *
 * **读点只能有一个,而且必须在"打开视频"的入口上。** 它曾经在装载解析里,也就是只有冷装载
 * 才读得到;而打开一条视频有三条路,另外两条(这条正在放、这条在队列里)都不产生冷装载,
 * 指名于是原地作废 —— 表现是缓存列表里点分 P 有时没反应,而那条没被取走的指名还会在之后
 * 某次冷装载时突然生效。
 *
 * 写在导航发生之前、读在装载解析时,两处都在主线程,但服务的解析跑在自己的协程上,所以
 * 加锁而不是靠时序。
 */
class PartRequest {

    private val lock = Any()
    private var bvid: String? = null
    private var cid: Long = 0

    /** 记下"这一次要放这条视频的这一 P"。同一时刻只留最后一次,先前没被取走的那条作废。 */
    fun request(bvid: String, cid: Long) {
        synchronized(lock) {
            this.bvid = bvid.takeIf { cid != 0L }
            this.cid = cid
        }
    }

    /** 取走这条视频的指名,没有则 0。**取完即弃**,同一次指名不会生效第二次。 */
    fun consume(bvid: String): Long = synchronized(lock) {
        if (this.bvid != bvid) return 0
        val requested = cid
        this.bvid = null
        this.cid = 0
        requested
    }
}
