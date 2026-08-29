package dev.ide.bridge

import dev.ide.agent.AgentTool
import dev.ide.agent.AgentToolRegistry
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.SimpleToolRegistry
import dev.ide.agent.toolSchema
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec

/**
 * CodeAssist 工具注册中心。
 *
 * 将 CodeAssist 的 [AgentWorkspace]（项目读写端口）暴露的所有工具
 * 注册到统一的 [AgentToolRegistry] 中，供 Agent Loop 和 MCP Server 使用。
 *
 * 工具分为三类：
 * 1. 只读工具 — 无需权限
 * 2. 写入工具 — 权限控制（ASK_EACH / AUTO_ACCEPT / PLAN_ONLY）
 * 3. 构建/运行工具 — 已有（list_tasks / run_task）
 *
 * CodeAssist 的 BuiltinTools.kt 已经定义了完整的工具集，
 * 这里提供一个简化的工厂方法，方便在 RikkaHub 集成层调用。
 */
object RikkaToolRegistry {

    /**
     * 创建绑定到指定 [AgentWorkspace] 的工具注册表。
     *
     * 直接复用 CodeAssist agent-impl 的 builtinTools()，
     * 而非重新实现——确保工具行为完全一致。
     */
    fun create(ws: AgentWorkspace): AgentToolRegistry {
        // 如果 agent-impl 在类路径上，直接使用 builtinTools
        return try {
            val builtinTools = Class.forName("dev.ide.agent.impl.BuiltinToolsKt")
            val method = builtinTools.getDeclaredMethod("builtinTools", AgentWorkspace::class.java)
            val tools = method.invoke(null, ws) as List<AgentTool>
            SimpleToolRegistry(tools)
        } catch (e: Exception) {
            // Fallback: 使用内建的最小工具集
            SimpleToolRegistry(minimalTools(ws))
        }
    }

    /**
     * 最小工具集（不依赖 agent-impl）。
     * 当 agent-impl 模块不可用时使用。
     */
    private fun minimalTools(ws: AgentWorkspace): List<AgentTool> = listOf(
        ReadFileTool(ws),
        ListDirTool(ws),
        ProjectOverviewTool(ws),
    )
}

// ── 最小工具实现（当 agent-impl 不可用时的 fallback）──

private class ReadFileTool(private val ws: AgentWorkspace) : AgentTool {
    override val spec = ToolSpec(
        name = "read_file",
        description = "Read a file's current text from the project.",
        parameters = toolSchema {
            string("path", "File path, absolute or workspace-relative.")
            integer("start_line", "First line to read (1-based).", required = false)
            integer("end_line", "Last line to read (1-based, inclusive).", required = false)
        },
    )
    override suspend fun execute(args: dev.ide.agent.ToolArgs): ToolExecutionResult =
        ToolExecutionResult.ok(ws.readFile(args.string("path"), args.optInt("start_line"), args.optInt("end_line")))
}

private class ListDirTool(private val ws: AgentWorkspace) : AgentTool {
    override val spec = ToolSpec(
        name = "list_dir",
        description = "List the entries of a directory.",
        parameters = toolSchema { string("path", "Directory path.") },
    )
    override suspend fun execute(args: dev.ide.agent.ToolArgs): ToolExecutionResult {
        val entries = ws.listDir(args.string("path"))
        return if (entries.isEmpty()) ToolExecutionResult.ok("(empty)")
        else ToolExecutionResult.ok(entries.joinToString("\n") {
            (if (it.isDirectory) "[dir] " else "      ") + it.name
        })
    }
}

private class ProjectOverviewTool(private val ws: AgentWorkspace) : AgentTool {
    override val spec = ToolSpec(
        name = "project_overview",
        description = "Summarize the project: modules, types, source roots, dependencies.",
        parameters = toolSchema { },
    )
    override suspend fun execute(args: dev.ide.agent.ToolArgs): ToolExecutionResult {
        val overview = ws.projectOverview()
        val sb = StringBuilder("Project: ${overview.name}")
        overview.modules.forEach { m ->
            sb.append("\n\nModule ${m.name} (${m.type})")
            m.languageLevel?.let { sb.append(", language level ").append(it) }
            sb.append("\n  source roots: ").append(m.sourceRoots.joinToString(", ").ifEmpty { "(none)" })
            sb.append("\n  dependencies: ").append(m.dependencies.joinToString(", ").ifEmpty { "(none)" })
        }
        return ToolExecutionResult.ok(sb.toString())
    }
}
