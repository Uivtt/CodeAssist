package dev.ide.bridge.chat

import dev.ide.agent.AgentEvent
import dev.ide.agent.AgentEventSink
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// ── 顶层枚举（避免嵌套引用问题）──

enum class ToolCallStatus { RUNNING, SUCCESS, FAILED, DENIED }
enum class SystemMessageType { INFO, WARNING, ERROR, PERMISSION }

// ── 聊天消息模型 ──

sealed interface ChatMessage {
    val id: String

    data class User(override val id: String, val text: String) : ChatMessage

    data class Assistant(
        override val id: String,
        val text: StringBuilder = StringBuilder(),
        val thinking: StringBuilder = StringBuilder(),
        val toolCalls: MutableList<ToolCallDisplay> = mutableListOf(),
        val isStreaming: Boolean = true,
        val usage: TokenUsage? = null,
        val stopReason: StopReason? = null,
    ) : ChatMessage

    data class ToolCallDisplay(
        val id: String,
        val name: String,
        val summary: String,
        val status: ToolCallStatus,
        val result: String? = null,
    )

    data class System(
        override val id: String,
        val text: String,
        val type: SystemMessageType,
    ) : ChatMessage
}

// ── 聊天状态管理器 ──

/**
 * 实现 [AgentEventSink]，将 Agent 事件流转换为 [ChatMessage] 列表供 UI 渲染。
 */
class RikkaChatState : AgentEventSink {

    private val _messages = MutableSharedFlow<List<ChatMessage>>(replay = 1, extraBufferCapacity = 64)
    val messages: SharedFlow<List<ChatMessage>> = _messages.asSharedFlow()

    private val messageList = mutableListOf<ChatMessage>()
    private var currentAssistant: ChatMessage.Assistant? = null
    private var idCounter = 0

    private fun newId(): String = "msg-${idCounter++}"

    fun addUserMessage(text: String) {
        messageList.add(ChatMessage.User(newId(), text))
        _messages.tryEmit(messageList.toList())
    }

    override suspend fun emit(event: AgentEvent) {
        when (event) {
            is AgentEvent.UserMessage -> {
                if (messageList.lastOrNull() !is ChatMessage.User) addUserMessage(event.text)
            }
            is AgentEvent.AssistantTextDelta -> {
                getOrCreateAssistant().text.append(event.text)
                _messages.tryEmit(messageList.toList())
            }
            is AgentEvent.AssistantThinkingDelta -> {
                getOrCreateAssistant().thinking.append(event.text)
                _messages.tryEmit(messageList.toList())
            }
            is AgentEvent.ToolCallStarted -> {
                getOrCreateAssistant().toolCalls.add(
                    ChatMessage.ToolCallDisplay(event.id, event.name, event.displaySummary, ToolCallStatus.RUNNING)
                )
                _messages.tryEmit(messageList.toList())
            }
            is AgentEvent.ToolCallFinished -> {
                currentAssistant?.let { a ->
                    val i = a.toolCalls.indexOfFirst { it.id == event.id }
                    if (i >= 0) a.toolCalls[i] = a.toolCalls[i].copy(
                        status = if (event.ok) ToolCallStatus.SUCCESS else ToolCallStatus.FAILED,
                        result = event.resultSummary,
                    )
                }
                _messages.tryEmit(messageList.toList())
            }
            is AgentEvent.ToolCallDenied -> {
                currentAssistant?.let { a ->
                    val i = a.toolCalls.indexOfFirst { it.id == event.id }
                    if (i >= 0) a.toolCalls[i] = a.toolCalls[i].copy(
                        status = ToolCallStatus.DENIED, result = event.reason,
                    )
                }
                _messages.tryEmit(messageList.toList())
            }
            is AgentEvent.TurnCompleted -> {
                currentAssistant?.let { a ->
                    val i = messageList.indexOf(a)
                    if (i >= 0) messageList[i] = a.copy(isStreaming = false, usage = event.usage, stopReason = event.stopReason)
                }
                currentAssistant = null
                _messages.tryEmit(messageList.toList())
            }
            is AgentEvent.Error -> {
                messageList.add(ChatMessage.System(newId(), event.message, SystemMessageType.ERROR))
                currentAssistant = null
                _messages.tryEmit(messageList.toList())
            }
        }
    }

    private fun getOrCreateAssistant(): ChatMessage.Assistant {
        return currentAssistant ?: run {
            val a = ChatMessage.Assistant(newId())
            currentAssistant = a
            messageList.add(a)
            a
        }
    }

    fun clear() { messageList.clear(); currentAssistant = null; idCounter = 0; _messages.tryEmit(emptyList()) }
    fun snapshot(): List<ChatMessage> = messageList.toList()
}
