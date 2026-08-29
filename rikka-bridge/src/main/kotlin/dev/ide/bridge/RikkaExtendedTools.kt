package dev.ide.bridge

import dev.ide.agent.AgentTool
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.SimpleToolRegistry
import dev.ide.bridge.tools.BuildProjectTool
import dev.ide.bridge.tools.GitCommitTool
import dev.ide.bridge.tools.GitPushTool
import dev.ide.bridge.tools.GitStatusTool

/**
 * 扩展工具集工厂。
 *
 * 在 CodeAssist 原有 BuiltinTools 基础上，添加 RikkaHub 风格的增强工具：
 *
 * 原有工具 (20+):
 *   read_file, list_dir, search_text, find_symbol, get_diagnostics, project_overview,
 *   create_file, write_file, edit_file, create_dir, rename_path, move_path, delete_path,
 *   add_dependency, run_program, list_tasks, run_task, search_dependency,
 *   read_memory, write_memory, web_fetch, http_request,
 *   go_to_definition, find_references, project_diagnostics, rename_symbol,
 *   list_quick_fixes, apply_quick_fix, format_file, organize_imports
 *
 * 新增工具 (4):
 *   build_project  — 简化构建流程（自动查找 assembleDebug/Release 任务）
 *   git_commit    — 提交代码（通过 workspace shell）
 *   git_push      — 推送代码
 *   git_status    — 查看 git 状态
 */
object RikkaExtendedTools {

    /**
     * 创建扩展工具列表。
     * 这些工具与 BuiltinTools 合并使用。
     */
    fun extendedTools(ws: AgentWorkspace): List<AgentTool> = listOf(
        BuildProjectTool(ws),
        GitCommitTool(ws),
        GitPushTool(ws),
        GitStatusTool(ws),
    )

    /**
     * 创建包含原有工具 + 扩展工具的完整注册表。
     */
    fun createWithExtended(
        ws: AgentWorkspace,
        builtin: List<AgentTool>,
    ): dev.ide.agent.AgentToolRegistry {
        val all = builtin + extendedTools(ws)
        return SimpleToolRegistry(all)
    }
}
