package dev.bilby.agent

import kotlinx.serialization.json.JsonObject

/**
 * 过程里的一步是哪一类动作。**动作由行首图标表达,文字只写对象**(见 [Tool.subject]):
 * 「搜了」「读了」「瞟了一眼」这类助理自述的口吻试过,读起来像助理在旁边说话,而这一栏要的是
 * 扫一眼就知道它查过什么。
 */
enum class StepKind { SearchVideos, SearchUsers, Up, UpVideos, Video, Comments, Collection, Related, Other }

/**
 * 过程直播里用到的名字:视频标题、UP 名、合集标题。**只来自本轮之前的工具返回**,查不到就退回
 * 编号 —— 为了一个名字多打一次接口不值,而模型点名的对象多半就是它刚在结果里看到的。
 */
class NameBook(
    private val titles: Map<String, String>,
    private val upNames: Map<Long, String>,
    private val collections: Map<Long, String>,
) {
    fun video(bvid: String): String = titles[bvid]?.let { "《${it.shorten()}》" } ?: bvid
    fun up(mid: Long): String = upNames[mid] ?: mid.toString()
    fun collection(sid: Long): String = collections[sid]?.let { "《${it.shorten()}》" } ?: sid.toString()

    /** 标题太长会把一行挤成两行,过程里认出是哪一条就够。 */
    private fun String.shorten(): String = if (length <= TitleLimit) this else take(TitleLimit) + "…"

    private companion object {
        const val TitleLimit = 18
    }
}

interface Tool {
    val name: String
    val description: String

    /** JSON Schema。手写字符串即可,不引 schema 生成库(DESIGN 3.4)。 */
    val parameters: JsonObject

    /** 过程里这一步用哪个图标。 */
    val kind: StepKind get() = StepKind.Other

    suspend fun execute(arguments: JsonObject): ToolResult

    /**
     * 这一次调用的对象:搜的词、视频标题、UP 名。**不写动词**,动作归图标([kind])。
     */
    fun subject(arguments: JsonObject, names: NameBook): String = name

    /**
     * 同一轮里对这个工具的几次调用合成一步时写什么。模型一次并发读三个视频的热评,过程里是
     * 一步「《A》等 3 个视频的热评」,不是三行几乎一样的字。
     */
    fun step(subjects: List<String>): String = joinSubjects(subjects)
}

/** 一个对象原样;两三个用顿号连起来;再多写前一个加总数。 */
fun joinSubjects(subjects: List<String>, unit: String = "项"): String = when {
    subjects.size <= 1 -> subjects.firstOrNull().orEmpty()
    subjects.size <= 3 -> subjects.joinToString("、")
    else -> "${subjects.first()} 等 ${subjects.size} $unit"
}

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    val specs: List<ToolSpec> = tools.map {
        ToolSpec(function = FunctionSpec(it.name, it.description, it.parameters))
    }

    operator fun get(name: String): Tool? = byName[name]
}
