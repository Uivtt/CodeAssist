package dev.ide.agent.impl

import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmProviderRegistry
import dev.ide.agent.SimpleLlmProviderRegistry

/**
 * Assembles the built-in providers.
 *
 * Phase 1 改造：使用 RikkaHub 风格的 Provider（rikka-bridge 模块），
 * 替代原有的 agent-impl Provider。新增能力：
 * - OpenRouter 多模型路由
 * - 本地模型支持（Ollama / llama.cpp / LM Studio）
 * - 更灵活的 Provider 配置（自定义 baseUrl、CA 证书）
 *
 * 原有 Provider（AnthropicProvider / OpenAiProvider 等）仍然保留作为 fallback。
 * 当 rikka-bridge 模块不可用时，自动回退到原有实现。
 *
 * 配置方式：在 CodeAssist Settings → Agent → Provider 中填入 API Key。
 * Provider 会根据填入的 Key 自动启用。
 */
object AgentProviders {

    fun defaults(transport: LlmTransport = OkHttpLlmTransport()): List<LlmProvider> {
        // ── 尝试使用 rikka-bridge 的 Provider（如果模块可用）──
        val rikkaProviders = try {
            val bridgeClass = Class.forName("dev.ide.bridge.RikkaProviderBridge")
            val keys = loadApiKeys()
            
            @Suppress("UNCHECKED_CAST")
            val providers = mutableListOf<LlmProvider>()
            
            // Anthropic
            keys.anthropic.takeIf { it.isNotBlank() }?.let { key ->
                val factory = bridgeClass.getMethod("anthropic", String::class.java)
                providers.add(factory.invoke(null, key) as LlmProvider)
            }
            // OpenAI
            keys.openai.takeIf { it.isNotBlank() }?.let { key ->
                val factory = bridgeClass.getMethod("openai", String::class.java)
                providers.add(factory.invoke(null, key) as LlmProvider)
            }
            // Gemini
            keys.gemini.takeIf { it.isNotBlank() }?.let { key ->
                val factory = bridgeClass.getMethod("gemini", String::class.java)
                providers.add(factory.invoke(null, key) as LlmProvider)
            }
            // OpenRouter
            keys.openRouter.takeIf { it.isNotBlank() }?.let { key ->
                val factory = bridgeClass.getMethod("openRouter", String::class.java)
                providers.add(factory.invoke(null, key) as LlmProvider)
            }
            // Local
            val localFactory = bridgeClass.getMethod("local", String::class.java)
            providers.add(localFactory.invoke(null, keys.localBaseUrl) as LlmProvider)
            
            if (providers.isNotEmpty()) {
                return providers
            }
            null
        } catch (_: ClassNotFoundException) {
            // rikka-bridge 模块不在类路径上，使用原有 Provider
        } catch (_: Exception) {
            // 其他异常，回退
        }
        
        // ── Fallback: 原有 Provider ──
        return listOf(
            AnthropicProvider(transport),
            OpenAiProvider(transport),
            GeminiProvider(transport),
            OpenRouterProvider(transport),
        )
    }

    fun registry(transport: LlmTransport = OkHttpLlmTransport()): LlmProviderRegistry =
        SimpleLlmProviderRegistry(defaults(transport))

    // ── API Key 加载 ──
    // 从 CodeAssist Settings 框架加载用户配置的 API Key
    // 在实际接入时，这里会对接 SettingsStore
    private data class ApiKeys(
        val anthropic: String = "",
        val openai: String = "",
        val gemini: String = "",
        val openRouter: String = "",
        val localBaseUrl: String = "http://localhost:11434",
    )

    private fun loadApiKeys(): ApiKeys = ApiKeys(
        anthropic = System.getenv("ANTHROPIC_API_KEY") ?: "",
        openai = System.getenv("OPENAI_API_KEY") ?: "",
        gemini = System.getenv("GEMINI_API_KEY") ?: "",
        openRouter = System.getenv("OPENROUTER_API_KEY") ?: "",
        localBaseUrl = System.getenv("LOCAL_LLM_BASE_URL") ?: "http://localhost:11434",
    )
}
