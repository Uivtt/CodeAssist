package dev.ide.bridge.chat

import dev.ide.agent.AgentEvent
import dev.ide.agent.AgentEventSink
import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AgentToolRegistry
import dev.ide.agent.LlmProviderRegistry
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRole
import dev.ide.agent.ContentPart
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 聊天 UI 状态模型。
 * 将 CodeAssist 的 AgentEvent 流转换为 UI 可渲染的消息列表。
 *
 * 设计参考 RikkaHub 的聊天界面：
 * - 流式文本渲染
 * - 工具调用可视化
 * - 思考过程折叠显示
 * - 消息分支支持
 */
sealed interface ChatMessage {
    val id: String

    /** 用户发送的消息 */
    data class User(
        override val id: String,
        val text: String,
    ) : ChatMessage

    /** AI 回复消息（流式更新） */
    data class Assistant(
        override val id: String,
        val text: StringBuilder = StringBuilder(),
        val thinking: StringBuilder = StringBuilder(),
        val toolCalls: MutableList<ToolCallDisplay> = mutableListOf(),
        val isStreaming: Boolean = true,
        val usage: TokenUsage? = null,
        val stopReason: StopReason? = null,
    ) : ChatMessage

    /** 工具调用展示 */
    data class ToolCallDisplay(
        val id: String,
        val name: String,
        val summary: String,
        val status: ToolCallStatus,
        val result: String? = null,
    )

    enum class ToolCallStatus { RUNNING, SUCCESS, FAILED, DENIED }

    /** 系统消息（权限请求等） */
    data class System(
        override val id: String,
        val text: String,
        val type: SystemType,
    ) : ChatMessage

    enum class SystemType { INFO, WARNING, ERROR, PERMISSION }
}

/**
 * 聊天 UI 状态管理器。
 *
 * 实现 CodeAssist 的 [AgentEventSink] 接口，
 * 将 Agent 事件流转换为 [ChatMessage] 列表，
 * 供 Compose UI 渲染。
 *
 * 使用方式：
 * 1. 在 AgentLoop 启动时传入作为 eventSink
 * 2. UI 观察 messages 流渲染聊天界面
 * 3. 用户输入通过 send() 方法发送
 */
class RikkaChatState : AgentEventSink {

    private val _messages = MutableSharedFlow<List<ChatMessage>>(replay = 1, extraBufferCapacity = 64)
    val messages: SharedFlow<List<ChatMessage>> = _messages.asSharedFlow()

    private val messageList = mutableListOf<ChatMessage>()
    private var currentAssistant: ChatMessage.Assistant? = null
    private var messageIdCounter = 0

    private fun newId(): String = "msg-${messageIdCounter++}"

    /** 用户发送消息 */
    fun addUserMessage(text: String) {
        messageList.add(ChatMessage.User(newId(), text))
        _messages.tryEmit(messageList.toList())
    }

    /** Agent 事件处理 */
    override suspend fun emit(event: AgentEvent) {
        when (event) {
            is AgentEvent.UserMessage -> {
                // AgentLoop 回显用户消息，确保 UI 一致
                if (messageList.lastOrNull() !is ChatMessage.User) {
                    addUserMessage(event.text)
                }
            }

            is AgentEvent.AssistantTextDelta -> {
                val assistant = currentAssistant ?: run {
                    val new = ChatMessage.Assistant(newId())
                    currentAssistant = new
                    messageList.add(new)
                    new
                }
                assistant.text.append(event.text)
                _messages.tryEmit(messageList.toList())
            }

            is AgentEvent.AssistantThinkingDelta -> {
                val assistant = currentAssistant ?: run {
                    val new = ChatMessage.Assistant(newId())
                    currentAssistant = new
                    messageList.add(new)
                    new
                }
                assistant.thinking.append(event.text)
                _messages.tryEmit(messageList.toList())
            }

            is AgentEvent.ToolCallStarted -> {
                val assistant = currentAssistant ?: run {
                    val new = ChatMessage.Assistant(newId())
                    currentAssistant = new
                    messageList.add(new)
                    new
                }
                assistant.toolCalls.add(
                    ChatMessage.ToolCallDisplay(
                        id = event.id,
                        name = event.name,
                        summary = event.displaySummary,
                        status = ChatMessage.ToolCallDisplay.ToolCallStatus.RUNNING,
                    )
                )
                _messages.tryEmit(messageList.toList())
            }

            is AgentEvent.ToolCallFinished -> {
                currentAssistant?.let { assistant ->
                    val tc = assistant.toolCalls.find { it.id == event.id }
                    if (tc != null) {
                        val index = assistant.toolCalls.indexOf(tc)
                        assistant.toolCalls[index] = tc.copy(
                            status = if (event.ok) ChatMessage.ToolCallDisplay.ToolCallStatus.SUCCESS
                                     else ChatMessage.ToolCallDisplay.ToolCallStatus.FAILED,
                            result = event.resultSummary,
                        )
                    }
                }
                _messages.tryEmit(messageList.toList())
            }

            is AgentEvent.ToolCallDenied -> {
                currentAssistant?.let { assistant ->
                    val tc = assistant.toolCalls.find { it.id == event.id }
                    if (tc != null) {
                        val index = assistant.toolCalls.indexOf(tc)
                        assistant.toolCalls[index] = tc.copy(
                            status = ChatMessage.ToolCallDisplay.ToolCallStatus.DENIED,
                            result = event.reason,
                        )
                    }
                }
                _messages.tryEmit(messageList.toList())
            }

            is AgentEvent.TurnCompleted -> {
                currentAssistant?.let { assistant ->
                    val index = messageList.indexOf(assistant)
                    if (index >= 0) {
                        messageList[index] = assistant.copy(
                            isStreaming = false,
                            usage = event.usage,
                            stopReason = event.stopReason,
                        )
                    }
                }
                currentAssistant = null
                _messages.tryEmit(messageList.toList())
            }

            is AgentEvent.Error -> {
                messageList.add(
                    ChatMessage.System(newId(), event.message, ChatMessage.System.SystemType.ERROR)
                )
                currentAssistant = null
                _messages.tryEmit(messageList.toList())
            }
        }
    }

    /** 清空对话 */
    fun clear() {
        messageList.clear()
        currentAssistant = null
        messageIdCounter = 0
        _messages.tryEmit(emptyList())
    }

    /** 获取当前消息列表快照 */
    fun snapshot(): List<ChatMessage> = messageList.toList()
}
