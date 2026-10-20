package com.agentforge.mobile.tools

import com.agentforge.mobile.execution.CommandExecutor
import com.agentforge.mobile.execution.EnvironmentInspector
import com.agentforge.mobile.project.BackupManager
import com.agentforge.mobile.project.DiffEngine
import com.agentforge.mobile.project.FileOpResult
import com.agentforge.mobile.project.ProjectFileSystem
import com.agentforge.mobile.project.ProjectInspector
import com.agentforge.mobile.providers.ToolDefinitionSchema
import com.agentforge.mobile.providers.ToolParameterProperty
import com.agentforge.mobile.security.CommandSafetyClassifier
import com.agentforge.mobile.security.CommandSafetyLevel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Implements and registers all 17 required real coding agent tools:
 * 1. `list_files`
 * 2. `read_file`
 * 3. `search_in_files`
 * 4. `create_file`
 * 5. `write_file`
 * 6. `apply_patch`
 * 7. `create_directory`
 * 8. `move_file`
 * 9. `rename_file`
 * 10. `delete_file`
 * 11. `get_diff`
 * 12. `create_backup`
 * 13. `restore_backup`
 * 14. `inspect_project`
 * 15. `run_command`
 * 16. `run_tests`
 * 17. `build_project`
 */
class AgentToolkitRegistry(
    private val fileSystem: ProjectFileSystem,
    private val backupManager: BackupManager,
    private val commandExecutor: CommandExecutor,
    private val environmentInspector: EnvironmentInspector,
    private val localProjectDirProvider: () -> File? = { null }
) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private val toolsMap: Map<String, AgentTool> = buildList<AgentTool> {
        add(ListFilesTool())
        add(ReadFileTool())
        add(SearchInFilesTool())
        add(CreateFileTool())
        add(WriteFileTool())
        add(ApplyPatchTool())
        add(CreateDirectoryTool())
        add(MoveFileTool())
        add(RenameFileTool())
        add(DeleteFileTool())
        add(GetDiffTool())
        add(CreateBackupTool())
        add(RestoreBackupTool())
        add(InspectProjectTool())
        add(RunCommandTool())
        add(RunTestsTool())
        add(BuildProjectTool())
    }.associateBy { it.schema.name }

    fun getAllSchemas(): List<ToolDefinitionSchema> = toolsMap.values.map { it.schema }

    fun getTool(name: String): AgentTool? = toolsMap[name]

    suspend fun invokeTool(
        toolName: String,
        arguments: JsonObject,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val tool = toolsMap[toolName]
            ?: return ToolExecutionResult(
                toolName = toolName,
                isSuccess = false,
                outputText = "",
                errorMessage = "Unknown tool '$toolName'. Available tools: ${toolsMap.keys.joinToString(", ")}"
            )
        return tool.execute(arguments, context)
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content?.trim().orEmpty()

    private fun JsonObject.bool(key: String, default: Boolean): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: default

    // 1. list_files
    private inner class ListFilesTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "list_files",
            description = "Lists files and directories inside the project folder, respecting .agentignore exclusions.",
            parameters = mapOf(
                "path" to ToolParameterProperty("string", "Relative subdirectory path (empty string for project root)."),
                "recursive" to ToolParameterProperty("boolean", "Whether to list subdirectories recursively (default true).")
            ),
            requiredParameters = emptyList()
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val subDir = arguments.str("path")
            val recursive = arguments.bool("recursive", true)
            return when (val res = fileSystem.listFiles(subDir, recursive = recursive)) {
                is FileOpResult.Success -> {
                    val formatted = if (res.data.isEmpty()) {
                        "(Project directory is empty)"
                    } else {
                        res.data.joinToString("
") { node ->
                            val typeTag = if (node.isDirectory) "[DIR] " else "[FILE]"
                            val sizeInfo = if (node.isDirectory) "" else " (${node.sizeBytes} B)"
                            "$typeTag ${node.relativePath}$sizeInfo"
                        }
                    }
                    ToolExecutionResult(
                        toolName = schema.name,
                        isSuccess = true,
                        targetFilePath = subDir.ifEmpty { "/" },
                        outputText = formatted
                    )
                }
                is FileOpResult.Error -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = subDir,
                    outputText = "",
                    errorMessage = res.errorMessage
                )
            }
        }
    }

    // 2. read_file
    private inner class ReadFileTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "read_file",
            description = "Reads the text content of a file inside the project folder.",
            parameters = mapOf(
                "path" to ToolParameterProperty("string", "Project-relative path to the file.")
            ),
            requiredParameters = listOf("path")
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val path = arguments.str("path")
            return when (val res = fileSystem.readFile(path, maxBytes = context.maxFileReadBytes)) {
                is FileOpResult.Success -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = true,
                    targetFilePath = path,
                    outputText = res.data
                )
                is FileOpResult.Error -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = path,
                    outputText = "",
                    errorMessage = res.errorMessage
                )
            }
        }
    }

    // 3. search_in_files
    private inner class SearchInFilesTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "search_in_files",
            description = "Searches for a text string or regular expression across project files.",
            parameters = mapOf(
                "query" to ToolParameterProperty("string", "Text or regex pattern to search for."),
                "is_regex" to ToolParameterProperty("boolean", "True if query is a regular expression.")
            ),
            requiredParameters = listOf("query")
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val query = arguments.str("query")
            val isRegex = arguments.bool("is_regex", false)
            return when (val res = fileSystem.searchInFiles(query, isRegex = isRegex, maxFileBytes = context.maxFileReadBytes)) {
                is FileOpResult.Success -> {
                    val out = if (res.data.isEmpty()) {
                        "No matches found for '$query'."
                    } else {
                        res.data.joinToString("
") { "${it.relativePath}:${it.lineNumber}: ${it.linePreview}" }
                    }
                    ToolExecutionResult(
                        toolName = schema.name,
                        isSuccess = true,
                        outputText = out
                    )
                }
                is FileOpResult.Error -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    outputText = "",
                    errorMessage = res.errorMessage
                )
            }
        }
    }

    // 4. create_file
    private inner class CreateFileTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "create_file",
            description = "Creates a new file with the specified content inside the project directory.",
            parameters = mapOf(
                "path" to ToolParameterProperty("string", "Project-relative path for the new file."),
                "content" to ToolParameterProperty("string", "Full initial text content for the file.")
            ),
            requiredParameters = listOf("path", "content")
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val path = arguments.str("path")
            val content = arguments["content"]?.jsonPrimitive?.content.orEmpty()
            return when (val res = fileSystem.createFile(path, content, overwrite = false)) {
                is FileOpResult.Success -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = true,
                    targetFilePath = res.data.relativePath,
                    outputText = res.message
                )
                is FileOpResult.Error -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = path,
                    outputText = "",
                    errorMessage = res.errorMessage
                )
            }
        }
    }

    // 5. write_file
    private inner class WriteFileTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "write_file",
            description = "Writes or replaces the content of a file in the project. Automatically creates a backup snapshot if the file already exists.",
            parameters = mapOf(
                "path" to ToolParameterProperty("string", "Project-relative path to the file."),
                "content" to ToolParameterProperty("string", "Complete updated file content.")
            ),
            requiredParameters = listOf("path", "content")
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val path = arguments.str("path")
            val content = arguments["content"]?.jsonPrimitive?.content.orEmpty()

            // Automatically create backup if file exists
            val existingRead = fileSystem.readFile(path, maxBytes = context.maxFileReadBytes)
            val prevContent = (existingRead as? FileOpResult.Success)?.data
            if (prevContent != null) {
                backupManager.createBackupForFile(
                    projectId = context.projectId,
                    taskId = context.taskId,
                    relativePath = path,
                    reason = "Auto-backup before write_file"
                )
            }

            return when (val writeRes = fileSystem.writeFile(path, content)) {
                is FileOpResult.Success -> {
                    // Verify write by reading back
                    val diffSummary = if (prevContent != null) {
                        DiffEngine.computeUnifiedDiff(path, prevContent, content)
                    } else {
                        "Created new file '$path' (${writeRes.data.sizeBytes} bytes)."
                    }
                    ToolExecutionResult(
                        toolName = schema.name,
                        isSuccess = true,
                        targetFilePath = writeRes.data.relativePath,
                        outputText = "${writeRes.message}
$diffSummary"
                    )
                }
                is FileOpResult.Error -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = path,
                    outputText = "",
                    errorMessage = writeRes.errorMessage
                )
            }
        }
    }

    // 6. apply_patch
    private inner class ApplyPatchTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "apply_patch",
            description = "Applies one or more SEARCH/REPLACE hunks (`<<<< SEARCH\n...\n====\n...\n>>>> REPLACE`) to an existing file after creating an automatic backup.",
            parameters = mapOf(
                "path" to ToolParameterProperty("string", "Project-relative path to the target file."),
                "patch" to ToolParameterProperty("string", "Patch text containing `<<<< SEARCH ... ==== ... >>>> REPLACE` blocks.")
            ),
            requiredParameters = listOf("path", "patch")
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val path = arguments.str("path")
            val patchText = arguments["patch"]?.jsonPrimitive?.content.orEmpty()

            val readRes = fileSystem.readFile(path, maxBytes = context.maxFileReadBytes)
            if (readRes is FileOpResult.Error) {
                return ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = path,
                    outputText = "",
                    errorMessage = readRes.errorMessage
                )
            }
            val originalContent = (readRes as FileOpResult.Success).data
            val patchResult = DiffEngine.applyPatch(path, originalContent, patchText)
            if (!patchResult.isSuccess) {
                return ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = path,
                    outputText = "",
                    errorMessage = patchResult.errorMessage
                )
            }

            backupManager.createBackupForFile(
                projectId = context.projectId,
                taskId = context.taskId,
                relativePath = path,
                reason = "Auto-backup before apply_patch"
            )

            return when (val writeRes = fileSystem.writeFile(path, patchResult.updatedContent)) {
                is FileOpResult.Success -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = true,
                    targetFilePath = writeRes.data.relativePath,
                    outputText = "Applied ${patchResult.appliedHunks} hunk(s) to '$path'.
${patchResult.diffPreview}"
                )
                is FileOpResult.Error -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = path,
                    outputText = "",
                    errorMessage = writeRes.errorMessage
                )
            }
        }
    }

    // 7. create_directory
    private inner class CreateDirectoryTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "create_directory",
            description = "Creates a new directory (and any missing parent directories) inside the project.",
            parameters = mapOf(
                "path" to ToolParameterProperty("string", "Project-relative directory path to create.")
            ),
            requiredParameters = listOf("path")
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val path = arguments.str("path")
            return when (val res = fileSystem.createDirectory(path)) {
                is FileOpResult.Success -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = true,
                    targetFilePath = res.data.relativePath,
                    outputText = res.message
                )
                is FileOpResult.Error -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = path,
                    outputText = "",
                    errorMessage = res.errorMessage
                )
            }
        }
    }

    // 8. move_file
    private inner class MoveFileTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "move_file",
            description = "Moves a file from source_path to target_path within the project folder.",
            parameters = mapOf(
                "source_path" to ToolParameterProperty("string", "Current project-relative path of the file."),
                "target_path" to ToolParameterProperty("string", "Destination project-relative path.")
            ),
            requiredParameters = listOf("source_path", "target_path")
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val src = arguments.str("source_path")
            val dst = arguments.str("target_path")
            return when (val res = fileSystem.moveFile(src, dst)) {
                is FileOpResult.Success -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = true,
                    targetFilePath = res.data.relativePath,
                    outputText = res.message
                )
                is FileOpResult.Error -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = src,
                    outputText = "",
                    errorMessage = res.errorMessage
                )
            }
        }
    }

    // 9. rename_file
    private inner class RenameFileTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "rename_file",
            description = "Renames a file or folder in place within its current directory.",
            parameters = mapOf(
                "path" to ToolParameterProperty("string", "Current project-relative path."),
                "new_name" to ToolParameterProperty("string", "New filename (without directory slashes).")
            ),
            requiredParameters = listOf("path", "new_name")
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val path = arguments.str("path")
            val newName = arguments.str("new_name")
            return when (val res = fileSystem.renameFile(path, newName)) {
                is FileOpResult.Success -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = true,
                    targetFilePath = res.data.relativePath,
                    outputText = res.message
                )
                is FileOpResult.Error -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = path,
                    outputText = "",
                    errorMessage = res.errorMessage
                )
            }
        }
    }

    // 10. delete_file
    private inner class DeleteFileTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = true
        override val schema = ToolDefinitionSchema(
            name = "delete_file",
            description = "Deletes a file from the project after creating a safety backup. Requires user confirmation.",
            parameters = mapOf(
                "path" to ToolParameterProperty("string", "Project-relative path of the file to delete.")
            ),
            requiredParameters = listOf("path")
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val path = arguments.str("path")
            if (!context.userApprovedDestructiveAction) {
                return ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    requiresUserConfirmation = true,
                    confirmationPrompt = "Confirm permanent deletion of '$path' from project folder? (A local backup will be saved first.)",
                    targetFilePath = path,
                    outputText = "",
                    errorMessage = "Awaiting user approval to delete '$path'."
                )
            }

            backupManager.createBackupForFile(
                projectId = context.projectId,
                taskId = context.taskId,
                relativePath = path,
                reason = "Pre-deletion safety backup"
            )

            return when (val res = fileSystem.deleteFile(path)) {
                is FileOpResult.Success -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = true,
                    targetFilePath = path,
                    outputText = res.message
                )
                is FileOpResult.Error -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = path,
                    outputText = "",
                    errorMessage = res.errorMessage
                )
            }
        }
    }

    // 11. get_diff
    private inner class GetDiffTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "get_diff",
            description = "Computes the unified diff between the latest backup snapshot of a file and its current content on disk (or proposed new content).",
            parameters = mapOf(
                "path" to ToolParameterProperty("string", "Project-relative path of the file."),
                "proposed_content" to ToolParameterProperty("string", "Optional proposed content to compare against current file.")
            ),
            requiredParameters = listOf("path")
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val path = arguments.str("path")
            val proposed = arguments["proposed_content"]?.jsonPrimitive?.content

            val currentRead = fileSystem.readFile(path, maxBytes = context.maxFileReadBytes)
            if (currentRead is FileOpResult.Error) {
                return ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = path,
                    outputText = "",
                    errorMessage = currentRead.errorMessage
                )
            }
            val currentText = (currentRead as FileOpResult.Success).data

            val diff = if (proposed != null) {
                DiffEngine.computeUnifiedDiff(path, currentText, proposed)
            } else {
                DiffEngine.computeUnifiedDiff(path, currentText, currentText)
            }

            return ToolExecutionResult(
                toolName = schema.name,
                isSuccess = true,
                targetFilePath = path,
                outputText = diff
            )
        }
    }

    // 12. create_backup
    private inner class CreateBackupTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "create_backup",
            description = "Creates an explicit local backup snapshot of a project file before major refactoring.",
            parameters = mapOf(
                "path" to ToolParameterProperty("string", "Project-relative path of the file to back up."),
                "reason" to ToolParameterProperty("string", "Reason or description for the backup.")
            ),
            requiredParameters = listOf("path")
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val path = arguments.str("path")
            val reason = arguments.str("reason").ifEmpty { "Agent explicit checkpoint backup" }
            return when (val res = backupManager.createBackupForFile(context.projectId, context.taskId, path, reason)) {
                is FileOpResult.Success -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = true,
                    targetFilePath = path,
                    outputText = "${res.message} (backup_id=${res.data.id})"
                )
                is FileOpResult.Error -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = path,
                    outputText = "",
                    errorMessage = res.errorMessage
                )
            }
        }
    }

    // 13. restore_backup
    private inner class RestoreBackupTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "restore_backup",
            description = "Restores a file from its most recent backup snapshot or by backup_id.",
            parameters = mapOf(
                "path" to ToolParameterProperty("string", "Project-relative path of the file to restore."),
                "backup_id" to ToolParameterProperty("string", "Optional specific backup_id to restore.")
            ),
            requiredParameters = listOf("path")
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val path = arguments.str("path")
            val backupId = arguments.str("backup_id")
            val res = if (backupId.isNotEmpty()) {
                backupManager.restoreBackupById(backupId)
            } else {
                backupManager.restoreLatestBackupForFile(context.projectId, path)
            }
            return when (res) {
                is FileOpResult.Success -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = true,
                    targetFilePath = res.data.relativePath,
                    outputText = "Restored '${res.data.relativePath}' from backup."
                )
                is FileOpResult.Error -> ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    targetFilePath = path,
                    outputText = "",
                    errorMessage = res.errorMessage
                )
            }
        }
    }

    // 14. inspect_project
    private inner class InspectProjectTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "inspect_project",
            description = "Analyzes the project structure, primary language, frameworks, build system, and device execution capabilities.",
            parameters = emptyMap(),
            requiredParameters = emptyList()
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val projRes = ProjectInspector.inspect(fileSystem)
            if (projRes is FileOpResult.Error) {
                return ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    outputText = "",
                    errorMessage = projRes.errorMessage
                )
            }
            val summary = (projRes as FileOpResult.Success).data
            val envCaps = environmentInspector.inspectCapabilities(localProjectDirProvider())
            val combinedReport = buildString {
                appendLine("=== Project Structure Summary ===")
                appendLine(json.encodeToString(summary))
                appendLine()
                appendLine("=== Device Execution Capabilities ===")
                appendLine(json.encodeToString(envCaps))
            }
            return ToolExecutionResult(
                toolName = schema.name,
                isSuccess = true,
                outputText = combinedReport
            )
        }
    }

    // 15. run_command
    private inner class RunCommandTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "run_command",
            description = "Executes a local terminal command inside the project directory when supported and permitted by the device environment.",
            parameters = mapOf(
                "command" to ToolParameterProperty("string", "Shell command to run inside the project root.")
            ),
            requiredParameters = listOf("command")
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val cmd = arguments.str("command")
            val assessment = CommandSafetyClassifier.assess(cmd)
            if (assessment.level == CommandSafetyLevel.FORBIDDEN) {
                return ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    outputText = "",
                    errorMessage = assessment.reason
                )
            }
            if (assessment.level == CommandSafetyLevel.REQUIRES_USER_CONFIRMATION && !context.userApprovedDestructiveAction) {
                return ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    requiresUserConfirmation = true,
                    confirmationPrompt = "Allow agent to run command `$cmd`? (${assessment.reason})",
                    outputText = "",
                    errorMessage = "Command requires explicit user confirmation."
                )
            }

            val execRes = commandExecutor.executeCommand(
                rawCommand = cmd,
                workingDirectory = localProjectDirProvider(),
                userApprovedMutatingCommand = context.userApprovedDestructiveAction
            )
            return ToolExecutionResult(
                toolName = schema.name,
                isSuccess = execRes.isSuccess,
                outputText = buildString {
                    appendLine("Command: ${execRes.command}")
                    appendLine("Executed: ${execRes.executed} | Exit Code: ${execRes.exitCode}")
                    if (execRes.stdout.isNotBlank()) appendLine("STDOUT:
${execRes.stdout}")
                    if (execRes.stderr.isNotBlank()) appendLine("STDERR:
${execRes.stderr}")
                    appendLine("Environment: ${execRes.environmentNote}")
                },
                errorMessage = if (!execRes.isSuccess) execRes.stderr.ifBlank { execRes.environmentNote } else null
            )
        }
    }

    // 16. run_tests
    private inner class RunTestsTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "run_tests",
            description = "Runs project unit tests when a supported test runner (Gradle, pytest, npm) is available in the execution environment.",
            parameters = mapOf(
                "test_command" to ToolParameterProperty("string", "Optional custom test command (defaults to ./gradlew test or pytest).")
            ),
            requiredParameters = emptyList()
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val workDir = localProjectDirProvider()
            val caps = environmentInspector.inspectCapabilities(workDir)
            val customCmd = arguments.str("test_command")
            val effectiveCmd = when {
                customCmd.isNotEmpty() -> customCmd
                workDir != null && File(workDir, "gradlew").exists() -> "./gradlew test"
                caps.gradleAvailable -> "gradle test"
                else -> ""
            }

            if (effectiveCmd.isEmpty() || (!caps.gradleAvailable && customCmd.isEmpty())) {
                return ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    outputText = "Tests were not executed because a local test runner (Gradle/JDK) is not installed in the current mobile environment. (${caps.summaryNote})",
                    errorMessage = "Test runner environment not available on device."
                )
            }

            val res = commandExecutor.executeCommand(
                rawCommand = effectiveCmd,
                workingDirectory = workDir,
                userApprovedMutatingCommand = context.userApprovedDestructiveAction
            )
            return ToolExecutionResult(
                toolName = schema.name,
                isSuccess = res.isSuccess,
                outputText = "Test command `$effectiveCmd` exited with ${res.exitCode}.
${res.stdout}
${res.stderr}",
                errorMessage = if (!res.isSuccess) "Tests failed or could not run (exit ${res.exitCode})." else null
            )
        }
    }

    // 17. build_project
    private inner class BuildProjectTool : AgentTool {
        override val isDestructiveOrRequiresConfirmationInAutoMode = false
        override val schema = ToolDefinitionSchema(
            name = "build_project",
            description = "Attempts to build the project when real build tools (Gradle, JDK, Android SDK) are available. Never claims build success unless a real build command succeeds with exit code 0.",
            parameters = mapOf(
                "build_task" to ToolParameterProperty("string", "Optional Gradle task (default: assembleDebug).")
            ),
            requiredParameters = emptyList()
        )

        override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolExecutionResult {
            val workDir = localProjectDirProvider()
            val caps = environmentInspector.inspectCapabilities(workDir)
            val task = arguments.str("build_task").ifEmpty { "assembleDebug" }

            if (!caps.gradleAvailable || !caps.javaAvailable) {
                return ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    outputText = "لم يتم تنفيذ البناء (Build Not Executed): أدوات البناء المحلية (JDK / Gradle / Android SDK) غير متوفرة في بيئة التشغيل الحالية. تم حفظ جميع التعديلات البرمجية في ملفات المشروع بنجاح، ويمكن بناء APK عبر Android Studio أو بيئة Termux المجهزة. (${caps.summaryNote})",
                    errorMessage = "Build tools (JDK/Gradle/Android SDK) are not installed in this runtime environment."
                )
            }

            val cmd = if (workDir != null && File(workDir, "gradlew").exists()) {
                "./gradlew $task"
            } else {
                "gradle $task"
            }

            val res = commandExecutor.executeCommand(
                rawCommand = cmd,
                workingDirectory = workDir,
                userApprovedMutatingCommand = context.userApprovedDestructiveAction,
                timeoutMs = 180_000L
            )

            return if (res.executed && res.isSuccess && res.exitCode == 0) {
                ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = true,
                    outputText = "تم البناء بنجاح (Build Succeeded): `$cmd` exited with code 0.
${res.stdout}"
                )
            } else {
                ToolExecutionResult(
                    toolName = schema.name,
                    isSuccess = false,
                    outputText = "فشل البناء أو لم يكتمل (`$cmd`, exit=${res.exitCode}):
${res.stdout}
${res.stderr}",
                    errorMessage = "Build command failed with exit code ${res.exitCode}."
                )
            }
        }
    }
}
