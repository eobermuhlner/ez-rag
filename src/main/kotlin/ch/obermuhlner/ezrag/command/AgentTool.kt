package ch.obermuhlner.ezrag.command

enum class AgentTool(val toolName: String) {
    CLAUDE_CODE("claude-code"),
    OPENCODE("opencode"),
    GENERIC("generic");

    companion object {
        fun fromName(name: String): AgentTool? = entries.find { it.toolName == name }
    }
}
