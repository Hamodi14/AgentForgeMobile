package com.agentforge.mobile.security

/**
 * Classifies terminal/build commands into SAFE_READ_ONLY, SAFE_PROJECT_BUILD,
 * REQUIRES_USER_CONFIRMATION, or FORBIDDEN.
 */
enum class CommandSafetyLevel {
    SAFE_READ_ONLY,
    SAFE_PROJECT_BUILD,
    REQUIRES_USER_CONFIRMATION,
    FORBIDDEN
}

data class CommandSafetyAssessment(
    val level: CommandSafetyLevel,
    val normalizedCommand: String,
    val reason: String
)

object CommandSafetyClassifier {

    private val FORBIDDEN_PATTERNS = listOf(
        Regex("\bsu\b"),
        Regex("\bsudo\b"),
        Regex("\brm\s+-rf\s+/($|\s)"),
        Regex("\bmkfs\b"),
        Regex("\bdd\s+if="),
        Regex("\bchmod\s+777\s+/"),
        Regex(":\(\)\s*\{\s*:\|:\s*&\s*\}\s*;\s*:"), // fork bomb
        Regex("\b(curl|wget)\b.*\|\s*(sh|bash|zsh)\b"),
        Regex("\b\.\./") // path traversal in command arguments
    )

    private val CONFIRMATION_PATTERNS = listOf(
        Regex("\brm\b"),
        Regex("\bgit\s+(reset\s+--hard|clean\s+-f|push\s+--force)"),
        Regex("\b(pkg|apt|apt-get|pip|npm|yarn|pnpm)\s+(install|add|remove|uninstall)\b"),
        Regex("\bgradle(w)?\s+publish\b")
    )

    private val SAFE_BUILD_PATTERNS = listOf(
        Regex("^(\.\/)?gradlew(\s+(:[a-zA-Z0-9_-]+)*:(test|check|assembleDebug|lint|compileDebugKotlin|build))?(\s+--[a-zA-Z-]+)*$"),
        Regex("^gradle\s+(test|check|assembleDebug|build)(\s+--[a-zA-Z-]+)*$"),
        Regex("^kotlinc\b.*"),
        Regex("^javac\b.*"),
        Regex("^pytest\b.*"),
        Regex("^npm\s+test\b.*"),
        Regex("^cargo\s+(check|test|build)\b.*")
    )

    private val SAFE_READ_ONLY_PATTERNS = listOf(
        Regex("^git\s+(status|diff|log|branch|show)\b.*"),
        Regex("^(ls|pwd|whoami|java\s+-version|kotlinc\s+-version|gradle\s+-v|git\s+--version)\b.*"),
        Regex("^(cat|head|tail|wc|grep|find)\b.*")
    )

    fun assess(rawCommand: String): CommandSafetyAssessment {
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) {
            return CommandSafetyAssessment(
                level = CommandSafetyLevel.FORBIDDEN,
                normalizedCommand = "",
                reason = "Command cannot be empty."
            )
        }

        for (pattern in FORBIDDEN_PATTERNS) {
            if (pattern.containsMatchIn(cmd)) {
                return CommandSafetyAssessment(
                    level = CommandSafetyLevel.FORBIDDEN,
                    normalizedCommand = cmd,
                    reason = "Command matches forbidden security pattern (${pattern.pattern}). Root escalation, external script piping, and directory escape are prohibited."
                )
            }
        }

        // Check command chaining with destructive commands
        val subCommands = cmd.split(Regex("\s*(&&|\|\||;)\s*"))
        var highestLevel = CommandSafetyLevel.SAFE_READ_ONLY
        var triggerReason = "Read-only inspection command."

        for (sub in subCommands) {
            val trimmedSub = sub.trim()
            if (CONFIRMATION_PATTERNS.any { it.containsMatchIn(trimmedSub) }) {
                return CommandSafetyAssessment(
                    level = CommandSafetyLevel.REQUIRES_USER_CONFIRMATION,
                    normalizedCommand = cmd,
                    reason = "Command installs packages, deletes files, or performs irreversible git actions ('$trimmedSub'). Explicit user approval is required."
                )
            }
            if (SAFE_BUILD_PATTERNS.any { it.matches(trimmedSub) }) {
                if (highestLevel == CommandSafetyLevel.SAFE_READ_ONLY) {
                    highestLevel = CommandSafetyLevel.SAFE_PROJECT_BUILD
                    triggerReason = "Standard project build or test execution command."
                }
            } else if (!SAFE_READ_ONLY_PATTERNS.any { it.matches(trimmedSub) }) {
                return CommandSafetyAssessment(
                    level = CommandSafetyLevel.REQUIRES_USER_CONFIRMATION,
                    normalizedCommand = cmd,
                    reason = "Unrecognized or mutating shell command ('$trimmedSub') requires explicit user confirmation."
                )
            }
        }

        return CommandSafetyAssessment(
            level = highestLevel,
            normalizedCommand = cmd,
            reason = triggerReason
        )
    }
}
