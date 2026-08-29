package dev.ide.bridge.memory

import dev.ide.agent.AgentWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 项目记忆系统。
 *
 * 封装 CodeAssist AgentWorkspace 的 readMemory/writeMemory 能力，
 * 提供更高层的 API：
 * - 按类别存储记忆（架构、约定、决策）
 * - 自动合并新旧记忆
 * - 记忆摘要
 *
 * 这对应 RikkaHub 的 ChatGPT 式记忆功能，
 * 让 AI 跨对话记住项目的上下文。
 */
class RikkaMemoryStore(
    private val workspace: AgentWorkspace,
) {
    /** 记忆类别 */
    enum class MemoryCategory {
        ARCHITECTURE,   // 架构决策
        CONVENTIONS,    // 编码约定
        DEPENDENCIES,   // 依赖选择
        BUGS,           // 已知问题
        TASKS,          // 待办事项
        NOTES,          // 其他笔记
    }

    data class MemoryEntry(
        val category: MemoryCategory,
        val content: String,
        val timestamp: String = "",
    )

    /** 读取所有记忆 */
    suspend fun read(): List<MemoryEntry> = withContext(Dispatchers.IO) {
        val raw = workspace.readMemory()
        if (raw.isBlank()) return@withContext emptyList()

        // 解析记忆格式：每条记忆以 ## Category: Title 开头
        val entries = mutableListOf<MemoryEntry>()
        val sections = raw.split("\n## ")
        for (section in sections) {
            if (section.isBlank()) continue
            val lines = section.lines()
            val header = lines.firstOrNull() ?: continue
            val content = lines.drop(1).joinToString("\n").trim()

            val category = when {
                header.contains("Architecture", ignoreCase = true) -> MemoryCategory.ARCHITECTURE
                header.contains("Convention", ignoreCase = true) -> MemoryCategory.CONVENTIONS
                header.contains("Depend", ignoreCase = true) -> MemoryCategory.DEPENDENCIES
                header.contains("Bug", ignoreCase = true) -> MemoryCategory.BUGS
                header.contains("Task", ignoreCase = true) -> MemoryCategory.TASKS
                else -> MemoryCategory.NOTES
            }

            entries.add(MemoryEntry(category, content, header))
        }
        entries
    }

    /** 写入记忆（替换全部） */
    suspend fun write(entries: List<MemoryEntry>) = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        sb.append("# Project Memory\n\n")
        sb.append("This file is managed by the AI agent. Do not edit manually.\n\n")

        // 按类别分组
        for (category in MemoryCategory.entries) {
            val categoryEntries = entries.filter { it.category == category }
            if (categoryEntries.isEmpty()) continue

            sb.append("## ").append(category.name).append("\n\n")
            for (entry in categoryEntries) {
                sb.append("### ").append(entry.timestamp.ifBlank { "Entry" }).append("\n")
                sb.append(entry.content).append("\n\n")
            }
        }

        workspace.writeMemory(sb.toString())
    }

    /** 追加单条记忆 */
    suspend fun append(category: MemoryCategory, content: String) = withContext(Dispatchers.IO) {
        val current = read().toMutableList()
        current.add(MemoryEntry(category, content, System.currentTimeMillis().toString()))
        write(current)
    }

    /** 获取记忆摘要（供系统提示注入） */
    suspend fun summary(): String = withContext(Dispatchers.IO) {
        val entries = read()
        if (entries.isEmpty()) return@withContext ""

        val sb = StringBuilder("## Project Memory\n")
        for (category in MemoryCategory.entries) {
            val categoryEntries = entries.filter { it.category == category }
            if (categoryEntries.isEmpty()) continue

            sb.append("\n### ").append(category.name).append("\n")
            categoryEntries.take(3).forEach { entry ->
                sb.append("- ").append(entry.content.take(200)).append("\n")
            }
            if (categoryEntries.size > 3) {
                sb.append("  ... and ").append(categoryEntries.size - 3).append(" more\n")
            }
        }
        sb.toString()
    }
}
