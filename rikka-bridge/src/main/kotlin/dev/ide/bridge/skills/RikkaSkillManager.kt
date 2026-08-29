package dev.ide.bridge.skills

import java.io.File

/**
 * 技能管理器。
 *
 * 参考 RikkaHub 的 Skills 系统，从技能目录加载可复用的提示指令包。
 * 每个 Skill 是一个目录，包含 skill.md（frontmatter + 指令内容）。
 *
 * 技能目录结构:
 * ```
 * skills/
 * ├── android-dev/
 * │   └── skill.md     # Android 开发技能
 * ├── kotlin-clean/
 * │   └── skill.md     # Kotlin Clean Architecture 技能
 * └── compose-ui/
 *     └── skill.md     # Compose UI 技能
 * ```
 *
 * skill.md frontmatter:
 * ```
 * ---
 * name: android-dev
 * description: Expert Android development assistant
 * compatibility: Works best with Claude 3.5+, GPT-4o+
 * allowed-tools: read_file edit_file create_file build_project ...
 * ---
 * 技能指令内容...
 * ```
 */
class RikkaSkillManager(
    private val skillsDir: File,
) {
    data class Skill(
        val name: String,
        val description: String,
        val compatibility: String?,
        val allowedTools: List<String>,
        val instructions: String,
        val dir: File,
    )

    private var skills: List<Skill> = emptyList()
    private var enabledSkills: MutableSet<String> = mutableSetOf()

    init {
        loadSkills()
    }

    /** 加载所有技能 */
    private fun loadSkills() {
        if (!skillsDir.exists()) {
            skills = emptyList()
            return
        }

        val loaded = mutableListOf<Skill>()
        skillsDir.listFiles { it -> it.isDirectory }?.forEach { dir ->
            val skillFile = File(dir, "skill.md")
            if (!skillFile.exists()) return@forEach

            val content = skillFile.readText()
            val parsed = parseSkillFile(content, dir)
            parsed?.let { loaded.add(it) }
        }
        skills = loaded
    }

    /** 解析 skill.md 文件 */
    private fun parseSkillFile(content: String, dir: File): Skill? {
        // 解析 frontmatter
        val frontmatterRegex = Regex("""^---\n(.*?)\n---\n(.*)""", RegexOption.DOT_MATCHES_ALL)
        val match = frontmatterRegex.find(content) ?: return null

        val frontmatter = match.groupValues[1]
        val instructions = match.groupValues[2].trim()

        var name = dir.name
        var description = ""
        var compatibility: String? = null
        var allowedTools = emptyList<String>()

        frontmatter.lines().forEach { line ->
            when {
                line.startsWith("name:") -> name = line.substringAfter("name:").trim().trim('"')
                line.startsWith("description:") -> description = line.substringAfter("description:").trim().trim('"')
                line.startsWith("compatibility:") -> compatibility = line.substringAfter("compatibility:").trim().trim('"')
                line.startsWith("allowed-tools:") -> {
                    val toolsStr = line.substringAfter("allowed-tools:").trim()
                    allowedTools = toolsStr.split(" ").filter { it.isNotBlank() }
                }
            }
        }

        return Skill(name, description, compatibility, allowedTools, instructions, dir)
    }

    /** 获取所有可用技能 */
    fun list(): List<Skill> = skills

    /** 启用/禁用技能 */
    fun enable(name: String) { enabledSkills.add(name) }
    fun disable(name: String) { enabledSkills.remove(name) }
    fun isEnabled(name: String): Boolean = name in enabledSkills

    /** 获取已启用技能的指令内容 */
    fun enabledInstructions(): String {
        val enabled = skills.filter { it.name in enabledSkills }
        if (enabled.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n\n## Active Skills\n")
        for (skill in enabled) {
            sb.append("\n### Skill: ").append(skill.name).append("\n")
            sb.append(skill.instructions).append("\n")
        }
        return sb.toString()
    }

    /** 获取已启用技能允许的工具列表 */
    fun allowedTools(): Set<String>? {
        val enabled = skills.filter { it.name in enabledSkills }
        if (enabled.isEmpty()) return null  // null 表示不限制

        // 取所有已启用技能的 allowed-tools 的并集
        return enabled.flatMap { it.allowedTools }.toSet()
    }
}
