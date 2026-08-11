package dev.bilby.agent

import kotlinx.serialization.json.JsonObject

interface Tool {
    val name: String
    val description: String

    /** JSON Schema。手写字符串即可,不引 schema 生成库(DESIGN 3.4)。 */
    val parameters: JsonObject

    suspend fun execute(arguments: JsonObject): ToolResult

    /**
     * 过程直播里显示给人看的一句话,如「搜了 X」「瞟了一眼 Y 的相关推荐」。
     *
     * **写成助理刚做完这件事的口吻**:动词过去式、带一点人称,「瞟了一眼」这样的词在这里是
     * 对的。这一栏和别处的规矩相反 —— 它讲的是助理自己刚才干了什么,拟人正是它要传达的东西。
     * 应用其余的文案(缓存删除、取关这类确认)仍然一律书面:那些地方讲的是用户要做的事,
     * 而一个不可逆的动作配一句俏皮话,读起来像没当回事。
     */
    fun label(arguments: JsonObject): String = name
}

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    val specs: List<ToolSpec> = tools.map {
        ToolSpec(function = FunctionSpec(it.name, it.description, it.parameters))
    }

    operator fun get(name: String): Tool? = byName[name]
}
