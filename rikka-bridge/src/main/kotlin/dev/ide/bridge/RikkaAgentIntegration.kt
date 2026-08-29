package dev.ide.bridge

import dev.ide.agent.AgentWorkspace
import dev.ide.agent.LlmProvider
import dev.ide.agent.LlmProviderRegistry
import dev.ide.agent.SimpleLlmProviderRegistry

/**
 * RikkaHub ↔ CodeAssist 整合入口。
 *
 * 这是整个改造的核心入口点：
 * 1. 创建 RikkaHub 风格的 LLM Provider（替代 agent-impl 的 Provider）
 * 2. 创建 CodeAssist 工具注册表（复用 BuiltinTools）
 * 3. 提供系统提示（注入项目上下文）
 *
 * 在 ide-core 的 AgentPlugin 初始化时调用本类的 [create] 方法，
 * 获取完整的 Provider + Tool 集合，注入到 AgentLoop。
 */
class RikkaAgentIntegration private constructor(
    val providerRegistry: LlmProviderRegistry,
    val toolRegistry: dev.ide.agent.AgentToolRegistry,
    val systemPromptProvider: RikkaSystemPrompt,
) {
    companion object {
        /**
         * 创建整合实例。
         *
         * @param workspace CodeAssist 的项目工作区
         * @param apiKeys 用户的 API Key 配置
         */
        fun create(
            workspace: AgentWorkspace,
            apiKeys: RikkaApiKeys,
        ): RikkaAgentIntegration {
            // 1. 创建 Provider 列表
            val providers = buildList {
                if (apiKeys.anthropic.isNotBlank()) add(RikkaProviderBridge.anthropic(apiKeys.anthropic))
                if (apiKeys.openai.isNotBlank()) add(RikkaProviderBridge.openai(apiKeys.openai))
                if (apiKeys.gemini.isNotBlank()) add(RikkaProviderBridge.gemini(apiKeys.gemini))
                if (apiKeys.openRouter.isNotBlank()) add(RikkaProviderBridge.openRouter(apiKeys.openRouter))
                // 本地模型总是可用
                add(RikkaProviderBridge.local(apiKeys.localBaseUrl))
            }

            // 2. 创建工具注册表
            val toolRegistry = RikkaToolRegistry.create(workspace)

            // 3. 创建系统提示
            val systemPrompt = RikkaSystemPrompt(workspace)

            return RikkaAgentIntegration(
                providerRegistry = SimpleLlmProviderRegistry(providers),
                toolRegistry = toolRegistry,
                systemPromptProvider = systemPrompt,
            )
        }
    }
}

/**
 * 用户的 API Key 配置。
 * 从 CodeAssist 的 Settings 框架加载。
 */
data class RikkaApiKeys(
    val anthropic: String = "",
    val openai: String = "",
    val gemini: String = "",
    val openRouter: String = "",
    val localBaseUrl: String = "http://localhost:11434",
) {
    companion object {
        /**
         * 从环境变量加载（用于 GitHub Actions CI）
         */
        fun fromEnv(): RikkaApiKeys = RikkaApiKeys(
            anthropic = System.getenv("ANTHROPIC_API_KEY") ?: "",
            openai = System.getenv("OPENAI_API_KEY") ?: "",
            gemini = System.getenv("GEMINI_API_KEY") ?: "",
            openRouter = System.getenv("OPENROUTER_API_KEY") ?: "",
            localBaseUrl = System.getenv("LOCAL_LLM_BASE_URL") ?: "http://localhost:11434",
        )
    }
}
