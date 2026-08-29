package dev.ide.bridge

import dev.ide.agent.ContentPart
import dev.ide.agent.LlmClient
import dev.ide.agent.LlmMessage
import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmRequest
import dev.ide.agent.LlmRole
import dev.ide.agent.LlmStreamEvent
import dev.ide.agent.LlmModelInfo
import dev.ide.agent.ProviderConfig
import dev.ide.agent.StopReason
import dev.ide.agent.TokenUsage
import dev.ide.agent.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * RikkaHub Provider 桥接适配器。
 *
 * 将 RikkaHub 的 LLM Provider（更成熟：支持 OpenRouter、本地模型、Memory 等）
 * 适配为 CodeAssist 的 [LlmProvider] 接口。
 *
 * 设计思路：
 * - RikkaHub 的 Provider 通过 HTTP/SSE 与 LLM 服务通信
 * - CodeAssist 的 AgentLoop 通过 [LlmClient] 消费 [LlmStreamEvent] 流
 * - 本适配器在两者之间做格式转换
 *
 * 由于 RikkaHub 的 ai 模块需要单独引入（作为 :rikka-ai 子模块），
 * 在引入前，本类提供了基于 OkHttp 的直接实现，
 * 支持 OpenAI 兼容、Anthropic、Google Gemini 三种 API。
 */
class RikkaProviderBridge(
    override val id: String,
    override val displayName: String,
    override val models: List<LlmModelInfo>,
    override val defaultModel: String,
    private val apiKey: String,
    private val baseUrl: String,
    /** API 风格: "openai" / "anthropic" / "gemini" */
    private val apiStyle: String = "openai",
) : LlmProvider {

    override fun client(config: ProviderConfig): LlmClient = RikkaLlmClient(
        apiKey = config.apiKey.ifBlank { apiKey },
        baseUrl = config.baseUrl ?: baseUrl,
        apiStyle = apiStyle,
    )

    companion object {
        // ── 预置 Provider 工厂 ──

        fun anthropic(apiKey: String) = RikkaProviderBridge(
            id = "anthropic",
            displayName = "Anthropic (Claude)",
            models = listOf(
                LlmModelInfo("claude-sonnet-4-20250514", "Claude Sonnet 4", supportsThinking = true),
                LlmModelInfo("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet", supportsThinking = true),
                LlmModelInfo("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet"),
            ),
            defaultModel = "claude-sonnet-4-20250514",
            apiKey = apiKey,
            baseUrl = "https://api.anthropic.com",
            apiStyle = "anthropic",
        )

        fun openai(apiKey: String) = RikkaProviderBridge(
            id = "openai",
            displayName = "OpenAI (GPT)",
            models = listOf(
                LlmModelInfo("gpt-4o", "GPT-4o"),
                LlmModelInfo("gpt-4o-mini", "GPT-4o Mini"),
                LlmModelInfo("o3-mini", "o3-mini", supportsThinking = true),
            ),
            defaultModel = "gpt-4o",
            apiKey = apiKey,
            baseUrl = "https://api.openai.com",
            apiStyle = "openai",
        )

        fun gemini(apiKey: String) = RikkaProviderBridge(
            id = "gemini",
            displayName = "Google Gemini",
            models = listOf(
                LlmModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", supportsThinking = true),
                LlmModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", supportsThinking = true),
            ),
            defaultModel = "gemini-2.5-flash",
            apiKey = apiKey,
            baseUrl = "https://generativelanguage.googleapis.com",
            apiStyle = "gemini",
        )

        fun openRouter(apiKey: String) = RikkaProviderBridge(
            id = "openrouter",
            displayName = "OpenRouter",
            models = listOf(
                LlmModelInfo("anthropic/claude-sonnet-4", "Claude Sonnet 4 (OR)"),
                LlmModelInfo("openai/gpt-4o", "GPT-4o (OR)"),
                LlmModelInfo("google/gemini-2.5-pro", "Gemini 2.5 Pro (OR)"),
                LlmModelInfo("deepseek/deepseek-chat", "DeepSeek V3 (OR)"),
            ),
            defaultModel = "anthropic/claude-sonnet-4",
            apiKey = apiKey,
            baseUrl = "https://openrouter.ai/api",
            apiStyle = "openai",
        )

        /**
         * 本地模型（Ollama / llama.cpp / LM Studio）
         * RikkaHub 独有能力：cleartext HTTP to localhost
         */
        fun local(baseUrl: String = "http://localhost:11434") = RikkaProviderBridge(
            id = "local",
            displayName = "Local Model",
            models = listOf(
                LlmModelInfo("qwen2.5-coder", "Qwen 2.5 Coder"),
                LlmModelInfo("deepseek-coder-v2", "DeepSeek Coder V2"),
                LlmModelInfo("llama3.1", "Llama 3.1"),
            ),
            defaultModel = "qwen2.5-coder",
            apiKey = "ollama",
            baseUrl = baseUrl,
            apiStyle = "openai",
        )
    }
}
