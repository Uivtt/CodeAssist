package dev.ide.bridge.chat

import dev.ide.agent.AgentEventSink
import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AgentToolRegistry
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmProviderRegistry
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmRole
import dev.ide.agent.PermissionMode
import dev.ide.agent.WriteRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 聊天 ViewModel。
 *
 * 连接 UI 层和 Agent 引擎层：
 * - UI 通过 send() 发送用户消息
 * - ViewModel 构建 LlmRequest，驱动 AgentLoop
 * - AgentEvent 通过 RikkaChatState 回传给 UI
 *
 * 在实际 App 中，这个 ViewModel 会被注入到 Compose 的 viewModel() 中，
 * UI 通过观察 chatState.messages 渲染聊天界面。
 *
 * 使用方式（伪 Compose 代码）:
 * ```
 * val viewModel: RikkaChatViewModel = viewModel { ... }
 * val messages by viewModel.chatState.messages.collectAsState(initial = emptyList())
 *
 * LazyColumn {
 *     items(messages) { message ->
 *         ChatBubble(message)
 *     }
 * }
 * TextInput(onSend = { viewModel.send(it) })
 * ```
 */
class RikkaChatViewModel(
    private val providerRegistry: LlmProviderRegistry,
    private val toolRegistry: AgentToolRegistry,
    private val workspace: AgentWorkspace,
    private val permissionGate: AgentPermissionGate,
    private val systemPromptProvider: dev.ide.bridge.RikkaSystemPrompt,
) {
    val chatState = RikkaChatState()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _selectedProviderId = MutableStateFlow(providerRegistry.providers.firstOrNull()?.id ?: "")
    val selectedProviderId: StateFlow<String> = _selectedProviderId.asStateFlow()

    private val _selectedModel = MutableStateFlow(
        providerRegistry.providers.firstOrNull()?.defaultModel ?: ""
    )
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    /** 可用 Provider 列表（供 UI 下拉选择） */
    val providers = providerRegistry.providers

    /** 切换 Provider */
    fun selectProvider(id: String) {
        _selectedProviderId.value = id
        providerRegistry.provider(id)?.let { provider ->
            _selectedModel.value = provider.defaultModel
        }
    }

    /** 切换模型 */
    fun selectModel(model: String) {
        _selectedModel.value = model
    }

    /** 用户发送消息 */
    fun send(text: String) {
        if (_isRunning.value || text.isBlank()) return

        _isRunning.value = true
        chatState.addUserMessage(text)

        scope.launch {
            try {
                val provider = providerRegistry.provider(_selectedProviderId.value)
                    ?: throw IllegalStateException("Provider not found: ${_selectedProviderId.value}")

                val client = provider.client(
                    dev.ide.agent.ProviderConfig(apiKey = "")
                )

                // 构建请求
                val request = LlmRequest(
                    model = _selectedModel.value,
                    system = systemPromptProvider.build(),
                    messages = buildMessages(text),
                    tools = toolRegistry.specs(),
                )

                // 驱动 AgentLoop（简化版：直接流式 + 工具调用）
                val loop = dev.ide.agent.impl.AgentLoop(
                    client = client,
                    toolRegistry = toolRegistry,
                    workspace = workspace,
                    permissionGate = permissionGate,
                    eventSink = chatState,
                )
                loop.run(request)
            } catch (e: Exception) {
                chatState.emit(dev.ide.agent.AgentEvent.Error(e.message ?: e.toString()))
            } finally {
                _isRunning.value = false
            }
        }
    }

    /** 停止当前运行 */
    fun stop() {
        _isRunning.value = false
        // AgentLoop 的协程会被取消
    }

    /** 清空对话 */
    fun clear() {
        chatState.clear()
    }

    /** 构建消息列表（包含历史） */
    private fun buildMessages(newUserText: String): List<LlmMessage> {
        // 从 chatState 快照构建历史消息
        val messages = mutableListOf<LlmMessage>()
        val snapshot = chatState.snapshot()

        for (msg in snapshot) {
            when (msg) {
                is ChatMessage.User -> {
                    messages.add(LlmMessage.user(msg.text))
                }
                is ChatMessage.Assistant -> {
                    if (msg.text.isNotEmpty()) {
                        messages.add(LlmMessage.assistant(
                            listOf(ContentPart.Text(msg.text.toString()))
                        ))
                    }
                    // 添加工具调用和结果
                    msg.toolCalls.forEach { tc ->
                        // 工具调用
                        messages.add(LlmMessage.assistant(
                            listOf(ContentPart.ToolUse(
                                id = tc.id,
                                name = tc.name,
                                arguments = tc.result ?: "",
                            ))
                        ))
                        // 工具结果
                        messages.add(LlmMessage.toolResult(
                            toolCallId = tc.id,
                            content = tc.result ?: "",
                            isError = tc.status == ChatMessage.ToolCallDisplay.ToolCallStatus.FAILED,
                        ))
                    }
                }
                is ChatMessage.System -> {}
            }
        }

        return messages
    }
}
