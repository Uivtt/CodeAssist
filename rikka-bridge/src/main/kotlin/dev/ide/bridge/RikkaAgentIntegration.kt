package dev.ide.bridge

import dev.ide.agent.AgentPermissionGate
import dev.ide.agent.AgentToolRegistry
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.AllowAllGate
import dev.ide.agent.LlmProviderRegistry
import dev.ide.agent.SimpleLlmProviderRegistry
import dev.ide.agent.SimpleToolRegistry
import dev.ide.bridge.chat.AgentLoopRunner
import dev.ide.bridge.chat.RikkaChatState
import dev.ide.bridge.chat.RikkaChatViewModel
import dev.ide.bridge.memory.RikkaMemoryStore
import dev.ide.bridge.skills.RikkaSkillManager
import java.io.File

/**
 * RikkaHub ↔ CodeAssist 整合入口（完整版 Phase 0-4）。
 *
 * 这是整个改造的核心入口点，组装所有组件：
 *
 * Phase 0: LLM Provider（RikkaProviderBridge）
 * Phase 1: Provider 接入（替代 agent-impl）
 * Phase 2: 扩展工具（BuildProjectTool / GitTools）
 * Phase 3: 聊天 UI 桥接（RikkaChatState / RikkaChatViewModel）
 * Phase 4: 记忆系统 + 技能系统
 *
 * 使用方式：
 * ```kotlin
 * val integration = RikkaAgentIntegration.create(
 *     workspace = ideEngine.agentWorkspace,
 *     apiKeys = RikkaApiKeys.fromSettings(),
 *     skillsDir = File(projectRoot, ".agents/skills"),
 *     permissionGate = AgentPermissionGate(mode),  // or AllowAllGate
 * )
 *
 * // 获取聊天 ViewModel
 * val viewModel = integration.createChatViewModel()
 * ```
 */
class RikkaAgentIntegration private constructor(
    val providerRegistry: LlmProviderRegistry,
    val toolRegistry: AgentToolRegistry,
    val systemPromptProvider: RikkaSystemPrompt,
    val memoryStore: RikkaMemoryStore,
    val skillManager: RikkaSkillManager,
    val chatState: RikkaChatState,
    private val workspace: AgentWorkspace,
    private val permissionGate: AgentPermissionGate,
) {
    companion object {
        /**
         * 创建整合实例。
         *
         * @param workspace CodeAssist 项目工作区
         * @param apiKeys 用户的 API Key 配置
         * @param skillsDir 技能目录路径（.agents/skills/）
         * @param permissionGate 权限策略
         */
        fun create(
            workspace: AgentWorkspace,
            apiKeys: RikkaApiKeys,
            skillsDir: File = File(".agents/skills"),
            permissionGate: AgentPermissionGate = AllowAllGate,
        ): RikkaAgentIntegration {
            // Phase 0: 创建 Provider 列表
            val providers = buildList {
                if (apiKeys.anthropic.isNotBlank()) add(RikkaProviderBridge.anthropic(apiKeys.anthropic))
                if (apiKeys.openai.isNotBlank()) add(RikkaProviderBridge.openai(apiKeys.openai))
                if (apiKeys.gemini.isNotBlank()) add(RikkaProviderBridge.gemini(apiKeys.gemini))
                if (apiKeys.openRouter.isNotBlank()) add(RikkaProviderBridge.openRouter(apiKeys.openRouter))
                // 本地模型总是可用
                add(RikkaProviderBridge.local(apiKeys.localBaseUrl))
            }

            // Phase 2: 创建工具注册表（原有 BuiltinTools + 扩展工具）
            val toolRegistry = RikkaToolRegistry.create(workspace)
            // 尝试添加扩展工具
            val extendedRegistry = try {
                val builtin = toolRegistry.tools
                RikkaExtendedTools.createWithExtended(workspace, builtin)
            } catch (_: Exception) {
                toolRegistry
            }

            // Phase 4: 记忆系统和技能系统
            val memoryStore = RikkaMemoryStore(workspace)
            val skillManager = RikkaSkillManager(skillsDir)
            // 默认启用 android-dev 技能
            skillManager.enable("android-dev")

            // Phase 4: 系统提示（包含记忆和技能）
            val systemPrompt = RikkaSystemPrompt(workspace)

            // Phase 3: 聊天状态
            val chatState = RikkaChatState()

            return RikkaAgentIntegration(
                providerRegistry = SimpleLlmProviderRegistry(providers),
                toolRegistry = extendedRegistry,
                systemPromptProvider = systemPrompt,
                memoryStore = memoryStore,
                skillManager = skillManager,
                chatState = chatState,
                workspace = workspace,
                permissionGate = permissionGate,
            )
        }
    }

    /**
     * 创建聊天 ViewModel（供 Compose UI 使用）。
     * @param loopRunner AgentLoop 运行回调（由 agent-impl 提供）
     */
    fun createChatViewModel(
        loopRunner: AgentLoopRunner,
    ): RikkaChatViewModel = RikkaChatViewModel(
        providerRegistry = providerRegistry,
        toolRegistry = toolRegistry,
        workspace = workspace,
        permissionGate = permissionGate,
        systemPromptProvider = systemPromptProvider,
        loopRunner = loopRunner,
    )

    /**
     * 获取完整的系统提示（包含技能指令和记忆摘要）。
     */
    suspend fun getSystemPrompt(): String {
        val base = systemPromptProvider.build()
        val skills = skillManager.enabledInstructions()
        val memory = memoryStore.summary()
        return buildString {
            append(base)
            if (memory.isNotBlank()) {
                append("\n\n")
                append(memory)
            }
            append(skills)
        }
    }
}
