package com.bilby.agent

import kotlinx.serialization.json.JsonObject

interface Tool {
    val name: String
    val description: String

    /** JSON Schema。手写字符串即可,不引 schema 生成库(DESIGN 3.4)。 */
    val parameters: JsonObject

    suspend fun execute(arguments: JsonObject): ToolResult

    /** 过程直播里显示给人看的一句话,如「搜了 X」「读了 Y 的热评」。 */
    fun label(arguments: JsonObject): String = name
}

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }

    val specs: List<ToolSpec> = tools.map {
        ToolSpec(function = FunctionSpec(it.name, it.description, it.parameters))
    }

    operator fun get(name: String): Tool? = byName[name]
}
