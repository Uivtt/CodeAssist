package dev.ide.bridge.chat

import dev.ide.agent.AgentEventSink
import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AgentToolRegistry
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.ContentPart
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmProviderRegistry
import dev.ide.agent.LlmRequest
import dev.ide.agent.ProviderConfig
import dev.ide.bridge.RikkaSystemPrompt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** AgentLoop 运行回调（由 agent-impl 提供实现，避免循环依赖） */
fun interface AgentLoopRunner {
    suspend fun run(request: LlmRequest, eventSink: AgentEventSink)
}

/**
 * 聊天 ViewModel：连接 UI 和 Agent 引擎。
 */
class RikkaChatViewModel(
    private val providerRegistry: LlmProviderRegistry,
    private val toolRegistry: AgentToolRegistry,
    private val workspace: AgentWorkspace,
    private val permissionGate: AgentPermissionGate,
    private val systemPromptProvider: RikkaSystemPrompt,
    private val loopRunner: AgentLoopRunner,
) {
    val chatState = RikkaChatState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _running = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _running.asStateFlow()
    private val _providerId = MutableStateFlow(providerRegistry.providers.firstOrNull()?.id ?: "")
    val selectedProviderId: StateFlow<String> = _providerId.asStateFlow()
    private val _model = MutableStateFlow(providerRegistry.providers.firstOrNull()?.defaultModel ?: "")
    val selectedModel: StateFlow<String> = _model.asStateFlow()
    val providers = providerRegistry.providers

    fun selectProvider(id: String) {
        _providerId.value = id
        providerRegistry.provider(id)?.let { _model.value = it.defaultModel }
    }

    fun send(text: String) {
        if (_running.value || text.isBlank()) return
        _running.value = true
        chatState.addUserMessage(text)
        scope.launch {
            try {
                val provider = providerRegistry.provider(_providerId.value) ?: error("Provider not found")
                val request = LlmRequest(
                    model = _model.value,
                    system = systemPromptProvider.build(),
                    messages = buildMessages(text),
                    tools = toolRegistry.specs(),
                )
                loopRunner.run(request, chatState)
            } catch (e: Exception) {
                chatState.emit(dev.ide.agent.AgentEvent.Error(e.message ?: e.toString()))
            } finally {
                _running.value = false
            }
        }
    }

    fun stop() { _running.value = false }
    fun clear() { chatState.clear() }

    private fun buildMessages(newUserText: String): List<LlmMessage> {
        val msgs = mutableListOf<LlmMessage>()
        for (msg in chatState.snapshot()) {
            when (msg) {
                is ChatMessage.User -> msgs.add(LlmMessage.user(msg.text))
                is ChatMessage.Assistant -> {
                    if (msg.text.isNotEmpty()) {
                        msgs.add(LlmMessage.assistant(listOf(ContentPart.Text(msg.text.toString()))))
                    }
                    for (tc in msg.toolCalls) {
                        msgs.add(LlmMessage.assistant(listOf(ContentPart.ToolUse(tc.id, tc.name, tc.result ?: ""))))
                        msgs.add(LlmMessage.toolResult(tc.id, tc.result ?: "", tc.status == ToolCallStatus.FAILED))
                    }
                }
                is ChatMessage.System -> {}
            }
        }
        return msgs
    }
}
