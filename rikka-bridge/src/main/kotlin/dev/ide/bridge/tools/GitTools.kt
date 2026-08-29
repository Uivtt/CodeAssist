package dev.ide.bridge.tools

import dev.ide.agent.AgentTool
import dev.ide.agent.AgentWorkspace
import dev.ide.agent.ToolArgs
import dev.ide.agent.ToolExecutionResult
import dev.ide.agent.ToolSpec
import dev.ide.agent.toolSchema

/**
 * Git 工具集。
 *
 * 通过 AgentWorkspace.httpRequest 或 workspace shell 执行 git 操作。
 * 让 AI 可以在构建成功后自动提交代码、推送到远程仓库。
 *
 * 注意：这些工具需要 workspace 的 shell/httpRequest 能力。
 * 在 MCP Server 模式下，shell 通过 PRoot 环境执行。
 */
class GitCommitTool(
    private val ws: AgentWorkspace,
) : AgentTool {
    override val spec = ToolSpec(
        name = "git_commit",
        description = "Stage all changes and create a git commit in the project. " +
            "Use this after successfully building to save your work.",
        parameters = toolSchema {
            string("message", "Commit message describing the changes.")
        },
    )
    override val mutating = true

    override fun summarize(args: ToolArgs): String =
        "git commit: ${args.optString("message")?.take(50)}"

    override suspend fun execute(args: ToolArgs): ToolExecutionResult {
        val message = args.string("message")
        val root = ws.projectRoot() ?: return ToolExecutionResult.error("No project open")

        // 使用 workspace 的 httpRequest 执行 git 命令
        // 在实际实现中，这会通过 PRoot shell 执行
        val result = try {
            ws.httpRequest(
                method = "POST",
                url = "http://127.0.0.1:18765/shell",
                headers = listOf("Content-Type: application/json"),
                body = """{"command":"cd $root && git add -A && git commit -m \"$message\""}""",
            )
        } catch (_: Throwable) {
            // Fallback: 如果 shell 不可用，提示用户手动提交
            return ToolExecutionResult(
                content = "Git commit requires workspace shell access. " +
                    "Please run manually in the terminal:\n" +
                    "  cd $root\n" +
                    "  git add -A\n" +
                    "  git commit -m \"$message\"",
            )
        }

        return ToolExecutionResult.ok("Committed: $message\n$result")
    }
}

class GitPushTool(
    private val ws: AgentWorkspace,
) : AgentTool {
    override val spec = ToolSpec(
        name = "git_push",
        description = "Push committed changes to the remote repository. " +
            "Requires a successful git_commit first.",
        parameters = toolSchema {
            string("remote", "Remote name (default: origin)", required = false)
            string("branch", "Branch name (default: current branch)", required = false)
        },
    )
    override val mutating = true

    override fun summarize(args: ToolArgs): String = "git push"

    override suspend fun execute(args: ToolArgs): ToolExecutionResult {
        val remote = args.optString("remote") ?: "origin"
        val branch = args.optString("branch") ?: ""
        val root = ws.projectRoot() ?: return ToolExecutionResult.error("No project open")

        val result = try {
            ws.httpRequest(
                method = "POST",
                url = "http://127.0.0.1:18765/shell",
                headers = listOf("Content-Type: application/json"),
                body = """{"command":"cd $root && git push $remote ${if (branch.isBlank()) "" else branch}"}""",
            )
        } catch (_: Throwable) {
            return ToolExecutionResult(
                content = "Git push requires workspace shell access. " +
                    "Please run manually: git push $remote $branch",
            )
        }

        return ToolExecutionResult.ok("Pushed to $remote\n$result")
    }
}

class GitStatusTool(
    private val ws: AgentWorkspace,
) : AgentTool {
    override val spec = ToolSpec(
        name = "git_status",
        description = "Show the current git status: modified, staged, and untracked files.",
        parameters = toolSchema {},
    )
    override val mutating = false

    override fun summarize(args: ToolArgs): String = "git status"

    override suspend fun execute(args: ToolArgs): ToolExecutionResult {
        val root = ws.projectRoot() ?: return ToolExecutionResult.error("No project open")

        val result = try {
            ws.httpRequest(
                method = "POST",
                url = "http://127.0.0.1:18765/shell",
                headers = listOf("Content-Type: application/json"),
                body = """{"command":"cd $root && git status --short"}""",
            )
        } catch (_: Throwable) {
            return ToolExecutionResult.error("Git status requires workspace shell access.")
        }

        return ToolExecutionResult.ok(result)
    }
}
