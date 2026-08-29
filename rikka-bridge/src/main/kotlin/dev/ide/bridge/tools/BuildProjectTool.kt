package dev.ide.bridge.tools

import dev.ide.agent.AgentTool
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.toolSchema

/**
 * 构建项目工具。
 *
 * 封装 AgentWorkspace.listTasks + runTask，让 AI 可以一句话构建项目。
 * CodeAssist 的 AgentWorkspace 已经有 list_tasks 和 run_task 两个工具，
 * 但 AI 经常不知道用哪个 task id。这个工具简化了流程：
 * 直接传入 variant（debug/release），自动查找并运行对应构建任务。
 */
class BuildProjectTool(
    private val ws: AgentWorkspace,
) : AgentTool {
    override val spec = ToolSpec(
        name = "build_project",
        description = "Build the Android project. Automatically finds and runs the appropriate build " +
            "task (assembleDebug/assembleRelease). Returns build status and any errors. " +
            "Use this after making code changes to verify the project compiles.",
        parameters = toolSchema {
            string("variant", "Build variant: 'debug' or 'release'", required = false, enum = listOf("debug", "release"))
        },
    )
    override val mutating = true

    override fun summarize(args: ToolArgs): String =
        "build project (${args.optString("variant") ?: "debug"})"

    override suspend fun execute(args: ToolArgs): ToolExecutionResult {
        val variant = args.optString("variant") ?: "debug"

        // 1. 查找构建任务
        val tasks = ws.listTasks()
        val buildTaskId = tasks.find { it.id.contains("assemble${variant.replaceFirstChar { c -> c.uppercase() }}", ignoreCase = true) }
            ?: tasks.find { it.group == "build" && it.id.contains(variant, ignoreCase = true) }
            ?: tasks.find { it.group == "build" }

        if (buildTaskId == null) {
            // 没有构建任务，尝试直接运行
            return ToolExecutionResult(
                content = "No build task found. Available tasks:\n" +
                    tasks.joinToString("\n") { "  ${it.id} — ${it.label} [${it.group}]" },
                isError = true,
            )
        }

        // 2. 运行构建
        val result = ws.runTask(buildTaskId.id)

        // 3. 格式化结果
        val sb = StringBuilder()
        sb.append(if (result.success) "✅ Build succeeded" else "❌ Build ${result.status}")
        sb.append(" (task: ${buildTaskId.id})")
        sb.append("\n")

        if (result.diagnostics.isNotEmpty()) {
            sb.append("\n--- Diagnostics ---\n")
            result.diagnostics.take(20).forEach { sb.append("  $it\n") }
            if (result.diagnostics.size > 20) {
                sb.append("  ... and ${result.diagnostics.size - 20} more\n")
            }
        }

        if (result.log.isNotBlank()) {
            sb.append("\n--- Build Log (last 30 lines) ---\n")
            result.log.lines().takeLast(30).forEach { sb.append("  $it\n") }
        }

        return ToolExecutionResult(
            content = sb.toString().trim(),
            isError = !result.success,
        )
    }
}
