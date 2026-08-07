package dev.bilby.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.agent.AgentEvent
import dev.bilby.agent.AgentIntent
import dev.bilby.agent.AgentLoop
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentViewModel(
    private val loop: AgentLoop,
    private val intent: AgentIntent,
) : ViewModel() {

    private val _state = MutableStateFlow(AgentUiState(intentLabel = intent.label()))
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var session: Job? = null

    init {
        start()
    }

    fun start() {
        session?.cancel()
        _state.value = AgentUiState(intentLabel = intent.label(), running = true)
        session = viewModelScope.launch {
            loop.run(intent).collect { event ->
                _state.update { current -> current.reduce(event) }
            }
            _state.update { it.copy(running = false) }
        }
    }

    private fun AgentUiState.reduce(event: AgentEvent): AgentUiState = when (event) {
        // 模型的自然语言输出不进 UI:过程直播要显示的是"做了什么",不是"想了什么"。
        is AgentEvent.Thinking -> this

        is AgentEvent.ToolStarted -> copy(steps = steps + AgentStep(event.label, emptyList(), finished = false))

        // 按 label 回填最后一个未完成的同名步骤,而不是新增一条:同一次调用的开始与结束
        // 是两个事件,新增会让每步显示两遍。
        is AgentEvent.ToolFinished -> copy(
            steps = steps.toMutableList().also { list ->
                val index = list.indexOfLast { it.label == event.label && !it.finished }
                if (index >= 0) list[index] = list[index].copy(items = event.items, finished = true)
                else list += AgentStep(event.label, event.items, finished = true)
            },
        )

        is AgentEvent.Answer -> copy(blocks = event.blocks, running = false)

        is AgentEvent.Failed -> copy(error = event.message, running = false)
    }

    private fun AgentIntent.label(): String = when (this) {
        is AgentIntent.Query -> text
        is AgentIntent.Related -> "与《$title》相关"
    }
}
