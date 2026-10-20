package com.agentforge.mobile.project

/**
 * Parses `.agentignore` rules and enforces default exclusions for build artifacts,
 * version control metadata, secret files, and oversized binaries.
 */
class AgentIgnoreRules(
    customRulesText: String = "",
    includeDefaultExclusions: Boolean = true
) {
    companion object {
        val DEFAULT_EXCLUDED_DIRS = setOf(
            ".git",
            "build",
            ".gradle",
            "node_modules",
            ".idea",
            ".kotlin",
            "__pycache__",
            ".venv",
            "venv",
            "target",
            "dist",
            "out"
        )

        val DEFAULT_EXCLUDED_PATTERNS = listOf(
            "local.properties",
            ".env",
            ".env.*",
            "*.keystore",
            "*.jks",
            "*.pem",
            "*.key",
            "*.apk",
            "*.aab",
            "*.dex",
            "*.class",
            "*.so",
            "*.o",
            "*.jar"
        )

        const val DEFAULT_AGENTIGNORE_TEMPLATE = """
# AgentForge Mobile - .agentignore configuration
# Directories & files listed here will not be indexed or sent to AI models.

.git/
build/
.gradle/
node_modules/
.idea/
local.properties
.env
.env.*
*.keystore
*.jks
*.pem
*.key
*.apk
*.aab
"""
    }

    private val directoryNames = mutableSetOf<String>()
    private val globRegexes = mutableListOf<Regex>()

    init {
        if (includeDefaultExclusions) {
            directoryNames.addAll(DEFAULT_EXCLUDED_DIRS)
            DEFAULT_EXCLUDED_PATTERNS.forEach { addRule(it) }
        }
        parseRules(customRulesText)
    }

    fun parseRules(text: String) {
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { addRule(it) }
    }

    private fun addRule(rule: String) {
        val cleaned = rule.replace('\\', '/').trimStart('/')
        if (cleaned.endsWith("/")) {
            val dirName = cleaned.trimEnd('/')
            if (!dirName.contains('/') && !dirName.contains('*')) {
                directoryNames.add(dirName)
                return
            }
        }
        val regexPattern = buildString {
            append("^")
            var i = 0
            while (i < cleaned.length) {
                val c = cleaned[i]
                when {
                    c == '*' && i + 1 < cleaned.length && cleaned[i + 1] == '*' -> {
                        append(".*")
                        i++
                    }
                    c == '*' -> append("[^/]*")
                    c == '?' -> append("[^/]")
                    c in ".+()[]{}^$|\\" -> {
                        append("\\")
                        append(c)
                    }
                    else -> append(c)
                }
                i++
            }
            append("$")
        }
        runCatching { Regex(regexPattern) }.getOrNull()?.let { globRegexes.add(it) }
    }

    fun isIgnored(relativePath: String, isDirectory: Boolean): Boolean {
        val norm = relativePath.replace('\\', '/').trim('/')
        if (norm.isEmpty()) return false

        val segments = norm.split('/')
        // Check if any segment matches an excluded directory name
        for ((idx, seg) in segments.withIndex()) {
            val isDirSegment = isDirectory || idx < segments.lastIndex
            if (isDirSegment && seg in directoryNames) {
                return true
            }
        }

        val fileName = segments.last()
        for (regex in globRegexes) {
            if (regex.matches(norm) || regex.matches(fileName)) {
                return true
            }
        }
        return false
    }
}
