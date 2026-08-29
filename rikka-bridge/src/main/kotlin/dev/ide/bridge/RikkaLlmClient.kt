package dev.ide.bridge

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmRole
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 基于 OkHttp 的 LLM 客户端实现。
 *
 * 支持 OpenAI 兼容、Anthropic、Google Gemini 三种 API 风格。
 * 通过 SSE (Server-Sent Events) 接收流式响应。
 *
 * 这个实现替代了 CodeAssist 原有的 agent-impl 中的 Provider，
 * 并增加了 RikkaHub 特有的能力：
 * - OpenRouter 支持
 * - 本地模型支持（cleartext HTTP to localhost）
 * - 更灵活的 Provider 配置
 */
class RikkaLlmClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val apiStyle: String = "openai",
) : dev.ide.agent.LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override fun chat(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        when (apiStyle) {
            "anthropic" -> emitAll(anthropicStream(request))
            "gemini" -> emitAll(geminiStream(request))
            else -> emitAll(openAiStream(request))
        }
    }

    // ── OpenAI 兼容流式（也用于 OpenRouter / 本地模型）──

    private suspend fun openAiStream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val body = buildOpenAiBody(request)
        val url = "${baseUrl.trimEnd('/')}/v1/chat/completions"

        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(httpRequest).execute()
        val source = response.body?.source() ?: throw RuntimeException("Empty response body")

        // 读取 SSE 流
        var currentToolCallId: String? = null
        var currentToolCallName: String? = null
        val toolCallArgs = StringBuilder()

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") {
                emit(LlmStreamEvent.Completed(StopReason.END_TURN))
                break
            }
            val chunk = json.parseToJsonElement(data).jsonObject
            val delta = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: continue

            // 文本
            delta["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                emit(LlmStreamEvent.TextDelta(text))
            }

            // 推理
            delta["reasoning"]?.jsonPrimitive?.contentOrNull?.let { reasoning ->
                emit(LlmStreamEvent.ThinkingDelta(reasoning))
            }

            // 工具调用
            delta["tool_calls"]?.jsonArray?.forEach { tc ->
                val tcObj = tc.jsonObject
                val id = tcObj["id"]?.jsonPrimitive?.content ?: currentToolCallId
                val fn = tcObj["function"]?.jsonObject
                if (id == null || fn == null) return@forEach
                val name = fn["name"]?.jsonPrimitive?.content ?: currentToolCallName

                if (name != null && id != currentToolCallId) {
                    // 前一个工具调用完成
                    if (currentToolCallId != null && toolCallArgs.isNotEmpty()) {
                        emit(LlmStreamEvent.ToolCallCompleted(
                            id = currentToolCallId!!,
                            name = currentToolCallName!!,
                            arguments = toolCallArgs.toString(),
                        ))
                        toolCallArgs.clear()
                    }
                    currentToolCallId = id
                    currentToolCallName = name
                    emit(LlmStreamEvent.ToolCallStarted(id, name))
                }
                fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { args ->
                    toolCallArgs.append(args)
                    emit(LlmStreamEvent.ToolCallArgsDelta(id, args))
                }
            }

            // Usage
            chunk["usage"]?.let { usage ->
                val input = usage.jsonObject["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                val output = usage.jsonObject["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                emit(LlmStreamEvent.Usage(TokenUsage(input, output)))
            }

            // Finish reason
            chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("finish_reason")
                ?.jsonPrimitive?.contentOrNull?.let { reason ->
                    // 完成前一个工具调用
                    if (currentToolCallId != null && toolCallArgs.isNotEmpty()) {
                        emit(LlmStreamEvent.ToolCallCompleted(
                            id = currentToolCallId!!,
                            name = currentToolCallName!!,
                            arguments = toolCallArgs.toString(),
                        ))
                        toolCallArgs.clear()
                        currentToolCallId = null
                    }
                    val stop = when (reason) {
                        "stop" -> StopReason.END_TURN
                        "tool_calls" -> StopReason.TOOL_USE
                        "length" -> StopReason.MAX_TOKENS
                        else -> StopReason.END_TURN
                    }
                    emit(LlmStreamEvent.Completed(stop))
                }
        }

        response.close()
    }

    // ── Anthropic 流式 ──

    private suspend fun anthropicStream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val body = buildAnthropicBody(request)
        val url = "${baseUrl.trimEnd('/')}/v1/messages"

        val httpRequest = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(httpRequest).execute()
        val source = response.body?.source() ?: throw RuntimeException("Empty response body")

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ").trim()
            val event = json.parseToJsonElement(data).jsonObject

            when (event["type"]?.jsonPrimitive?.content) {
                "content_block_start" -> {
                    val block = event["content_block"]?.jsonObject
                    if (block != null) {
                        when (block["type"]?.jsonPrimitive?.content) {
                            "tool_use" -> {
                                val id = block["id"]?.jsonPrimitive?.content ?: ""
                                val name = block["name"]?.jsonPrimitive?.content ?: ""
                                emit(LlmStreamEvent.ToolCallStarted(id, name))
                            }
                        }
                    }
                }
                "content_block_delta" -> {
                    val delta = event["delta"]?.jsonObject
                    if (delta != null) {
                        when (delta["type"]?.jsonPrimitive?.content) {
                            "text_delta" -> {
                                delta["text"]?.jsonPrimitive?.contentOrNull?.let {
                                    emit(LlmStreamEvent.TextDelta(it))
                                }
                            }
                            "thinking_delta" -> {
                                delta["thinking"]?.jsonPrimitive?.contentOrNull?.let {
                                    emit(LlmStreamEvent.ThinkingDelta(it))
                                }
                            }
                            "input_json_delta" -> {
                                val id = event["index"]?.jsonPrimitive?.intOrNull?.toString() ?: ""
                                delta["partial_json"]?.jsonPrimitive?.contentOrNull?.let {
                                    emit(LlmStreamEvent.ToolCallArgsDelta(id, it))
                                }
                            }
                        }
                    }
                }
                "content_block_stop" -> {
                    // 工具调用完成
                }
                "message_delta" -> {
                    val delta = event["delta"]?.jsonObject
                    if (delta != null) {
                        delta["stop_reason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
                            val stop = when (reason) {
                                "end_turn" -> StopReason.END_TURN
                                "tool_use" -> StopReason.TOOL_USE
                                "max_tokens" -> StopReason.MAX_TOKENS
                                else -> StopReason.END_TURN
                            }
                            emit(LlmStreamEvent.Completed(stop))
                        }
                    }
                    event["usage"]?.let { usage ->
                        val output = usage.jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                        emit(LlmStreamEvent.Usage(TokenUsage(0, output)))
                    }
                }
                "message_start" -> {
                    event["message"]?.jsonObject?.get("usage")?.let { usage ->
                        val input = usage.jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                        emit(LlmStreamEvent.Usage(TokenUsage(input, 0)))
                    }
                }
            }
        }

        response.close()
    }

    // ── Gemini 流式（简化版）──

    private suspend fun geminiStream(request: LlmRequest): Flow<LlmStreamEvent> = flow {
        val body = buildGeminiBody(request)
        val model = request.model
        val url = "${baseUrl.trimEnd('/')}/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"

        val httpRequest = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(httpRequest).execute()
        val source = response.body?.source() ?: throw RuntimeException("Empty response body")

        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ").trim()
            val chunk = json.parseToJsonElement(data).jsonObject

            chunk["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.let { candidate ->
                candidate["content"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                    val partObj = part.jsonObject
                    partObj["text"]?.jsonPrimitive?.contentOrNull?.let {
                        emit(LlmStreamEvent.TextDelta(it))
                    }
                    partObj["thought"]?.jsonPrimitive?.contentOrNull?.let {
                        emit(LlmStreamEvent.ThinkingDelta(it))
                    }
                    partObj["functionCall"]?.jsonObject?.let { fc ->
                        val name = fc["name"]?.jsonPrimitive?.content ?: ""
                        val args = fc["args"]?.toString() ?: "{}"
                        emit(LlmStreamEvent.ToolCallStarted(name, name))
                        emit(LlmStreamEvent.ToolCallArgsDelta(name, args))
                        emit(LlmStreamEvent.ToolCallCompleted(name, name, args))
                    }
                }
                candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
                    val stop = when (reason) {
                        "STOP" -> StopReason.END_TURN
                        else -> StopReason.END_TURN
                    }
                    emit(LlmStreamEvent.Completed(stop))
                }
            }

            chunk["usageMetadata"]?.let { usage ->
                val input = usage.jsonObject["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                val output = usage.jsonObject["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                emit(LlmStreamEvent.Usage(TokenUsage(input, output)))
            }
        }

        response.close()
    }

    // ── 请求体构建 ──

    private fun buildOpenAiBody(request: LlmRequest): String {
        return buildJsonObject {
            put("model", request.model)
            put("stream", true)
            put("max_tokens", request.maxTokens)
            if (request.reasoningEffort != null) {
                put("reasoning_effort", request.reasoningEffort)
            }
            putJsonArray("messages") {
                if (request.system != null) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", request.system)
                    })
                }
                request.messages.forEach { msg ->
                    add(buildJsonObject {
                        put("role", when (msg.role) {
                            LlmRole.SYSTEM -> "system"
                            LlmRole.USER -> "user"
                            LlmRole.ASSISTANT -> "assistant"
                            LlmRole.TOOL -> "tool"
                        })
                        if (msg.content.size == 1 && msg.content[0] is ContentPart.Text) {
                            put("content", (msg.content[0] as ContentPart.Text).text)
                        } else {
                            putJsonArray("content") {
                                msg.content.forEach { part ->
                                    add(buildJsonObject {
                                        when (part) {
                                            is ContentPart.Text -> {
                                                put("type", "text")
                                                put("text", part.text)
                                            }
                                            is ContentPart.ToolUse -> {
                                                put("type", "function")
                                                putJsonObject("function") {
                                                    put("name", part.name)
                                                    put("arguments", part.arguments)
                                                }
                                            }
                                            is ContentPart.ToolResultPart -> {
                                                put("type", "tool_result")
                                                put("tool_call_id", part.toolCallId)
                                                put("content", part.content)
                                            }
                                            is ContentPart.Thinking -> {
                                                put("type", "reasoning")
                                                put("content", part.text)
                                            }
                                        }
                                    })
                                }
                            }
                        }
                    })
                }
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", Json.parseToJsonElement(tool.parameters))
                            }
                        })
                    }
                }
            }
        }.toString()
    }

    private fun buildAnthropicBody(request: LlmRequest): String {
        return buildJsonObject {
            put("model", request.model)
            put("stream", true)
            put("max_tokens", request.maxTokens)
            if (request.system != null) {
                put("system", request.system)
            }
            if (request.thinking) {
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", request.thinkingBudget ?: 10000)
                }
            }
            putJsonArray("messages") {
                request.messages.forEach { msg ->
                    if (msg.role == LlmRole.SYSTEM) return@forEach
                    add(buildJsonObject {
                        put("role", when (msg.role) {
                            LlmRole.USER, LlmRole.TOOL -> "user"
                            LlmRole.ASSISTANT -> "assistant"
                            LlmRole.SYSTEM -> "user"
                        })
                        putJsonArray("content") {
                            msg.content.forEach { part ->
                                add(buildJsonObject {
                                    when (part) {
                                        is ContentPart.Text -> {
                                            put("type", "text")
                                            put("text", part.text)
                                        }
                                        is ContentPart.ToolUse -> {
                                            put("type", "tool_use")
                                            put("id", part.id)
                                            put("name", part.name)
                                            put("input", Json.parseToJsonElement(part.arguments))
                                        }
                                        is ContentPart.ToolResultPart -> {
                                            put("type", "tool_result")
                                            put("tool_use_id", part.toolCallId)
                                            put("content", part.content)
                                            if (part.isError) put("is_error", true)
                                        }
                                        is ContentPart.Thinking -> {
                                            put("type", "thinking")
                                            put("thinking", part.text)
                                            if (part.signature != null) {
                                                put("signature", part.signature)
                                            }
                                        }
                                    }
                                })
                            }
                        }
                    })
                }
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", Json.parseToJsonElement(tool.parameters))
                        })
                    }
                }
            }
        }.toString()
    }

    private fun buildGeminiBody(request: LlmRequest): String {
        return buildJsonObject {
            putJsonObject("systemInstruction") {
                if (request.system != null) {
                    putJsonArray("parts") {
                        add(buildJsonObject { put("text", request.system) })
                    }
                }
            }
            putJsonArray("contents") {
                request.messages.forEach { msg ->
                    if (msg.role == LlmRole.SYSTEM) return@forEach
                    add(buildJsonObject {
                        put("role", when (msg.role) {
                            LlmRole.USER, LlmRole.TOOL -> "user"
                            LlmRole.ASSISTANT -> "model"
                            LlmRole.SYSTEM -> "user"
                        })
                        putJsonArray("parts") {
                            msg.content.forEach { part ->
                                add(buildJsonObject {
                                    when (part) {
                                        is ContentPart.Text -> put("text", part.text)
                                        is ContentPart.ToolUse -> {
                                            putJsonObject("functionCall") {
                                                put("name", part.name)
                                                put("args", Json.parseToJsonElement(part.arguments))
                                            }
                                        }
                                        is ContentPart.ToolResultPart -> {
                                            putJsonObject("functionResponse") {
                                                put("name", part.toolCallId)
                                                put("response", buildJsonObject {
                                                    put("content", part.content)
                                                })
                                            }
                                        }
                                        else -> {}
                                    }
                                })
                            }
                        }
                    })
                }
            }
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            request.tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", Json.parseToJsonElement(tool.parameters))
                                })
                            }
                        }
                    })
                }
            }
        }.toString()
    }
}
