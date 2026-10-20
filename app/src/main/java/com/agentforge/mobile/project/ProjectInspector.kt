package com.agentforge.mobile.project

import kotlinx.serialization.Serializable

@Serializable
data class ProjectInspectionSummary(
    val projectName: String,
    val primaryLanguage: String,
    val detectedFrameworks: List<String>,
    val buildSystem: String,
    val totalFilesIndexed: Int,
    val totalDirectoriesIndexed: Int,
    val entryPointFiles: List<String>,
    val hasTestsDirectory: Boolean
)

/**
 * Inspects a project's file tree to identify programming languages, frameworks,
 * build tools, and key configuration/entry-point files without loading all files into memory.
 */
object ProjectInspector {

    suspend fun inspect(fileSystem: ProjectFileSystem): FileOpResult<ProjectInspectionSummary> {
        val listResult = fileSystem.listFiles("", recursive = true, includeIgnored = false, maxEntries = 600)
        if (listResult is FileOpResult.Error) return listResult

        val nodes = (listResult as FileOpResult.Success).data
        val files = nodes.filter { !it.isDirectory }
        val dirs = nodes.filter { it.isDirectory }
        val filePaths = files.map { it.relativePath }.toSet()
        val fileNames = files.map { it.name }.toSet()

        val extCounts = mutableMapOf<String, Int>()
        for (f in files) {
            val ext = f.name.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty()) {
                extCounts[ext] = (extCounts[ext] ?: 0) + 1
            }
        }

        val primaryLanguage = when {
            (extCounts["kt"] ?: 0) + (extCounts["kts"] ?: 0) > 0 -> "Kotlin"
            (extCounts["java"] ?: 0) > 0 -> "Java"
            (extCounts["ts"] ?: 0) + (extCounts["tsx"] ?: 0) > 0 -> "TypeScript"
            (extCounts["js"] ?: 0) + (extCounts["jsx"] ?: 0) > 0 -> "JavaScript"
            (extCounts["py"] ?: 0) > 0 -> "Python"
            (extCounts["dart"] ?: 0) > 0 -> "Dart"
            (extCounts["rs"] ?: 0) > 0 -> "Rust"
            (extCounts["go"] ?: 0) > 0 -> "Go"
            else -> "Mixed / Unknown"
        }

        val frameworks = mutableListOf<String>()
        if (filePaths.any { it.endsWith("AndroidManifest.xml") }) frameworks.add("Android SDK")
        if ("pubspec.yaml" in fileNames) frameworks.add("Flutter")
        if ("package.json" in fileNames) frameworks.add("Node.js")
        if ("requirements.txt" in fileNames || "pyproject.toml" in fileNames) frameworks.add("Python")
        if ("Cargo.toml" in fileNames) frameworks.add("Rust / Cargo")

        val buildSystem = when {
            "build.gradle.kts" in fileNames || "settings.gradle.kts" in fileNames -> "Gradle (Kotlin DSL)"
            "build.gradle" in fileNames -> "Gradle (Groovy)"
            "pom.xml" in fileNames -> "Maven"
            "package.json" in fileNames -> "npm / Node"
            "pyproject.toml" in fileNames -> "pyproject / pip"
            "Cargo.toml" in fileNames -> "Cargo"
            else -> "None / Custom"
        }

        val importantNames = setOf(
            "settings.gradle.kts",
            "build.gradle.kts",
            "app/build.gradle.kts",
            "AndroidManifest.xml",
            "package.json",
            "pyproject.toml",
            "README.md"
        )
        val entryPoints = files
            .filter { it.relativePath in importantNames || it.name in importantNames || it.name.contains("Main") || it.name.contains("Login") || it.name.contains("Auth") }
            .map { it.relativePath }
            .take(15)

        val hasTests = dirs.any {
            it.name.equals("test", ignoreCase = true) ||
                it.name.equals("tests", ignoreCase = true) ||
                it.name.equals("androidTest", ignoreCase = true)
        }

        return FileOpResult.Success(
            ProjectInspectionSummary(
                projectName = fileSystem.getProjectDisplayName(),
                primaryLanguage = primaryLanguage,
                detectedFrameworks = frameworks.ifEmpty { listOf("Standard Project") },
                buildSystem = buildSystem,
                totalFilesIndexed = files.size,
                totalDirectoriesIndexed = dirs.size,
                entryPointFiles = entryPoints,
                hasTestsDirectory = hasTests
            )
        )
    }
}
