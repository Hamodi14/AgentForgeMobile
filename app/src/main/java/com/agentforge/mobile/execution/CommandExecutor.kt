package com.agentforge.mobile.execution

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.agentforge.mobile.security.CommandSafetyAssessment
import com.agentforge.mobile.security.CommandSafetyClassifier
import com.agentforge.mobile.security.CommandSafetyLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.charset.StandardCharsets

@Serializable
data class CommandExecutionResult(
    val command: String,
    val executed: Boolean,
    val isSuccess: Boolean,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val safetyLevel: String,
    val environmentNote: String
)

/**
 * Executes verified commands inside the bounded project directory or dispatches them to
 * Termux (`com.termux.app.RunCommandService`) when Termux is configured and permitted.
 *
 * Strictly enforces:
 * - No root (`su` / `sudo`) or privilege escalation
 * - Working directory confinement
 * - Honest reporting when Gradle/Android SDK build tools are absent on the device
 */
class CommandExecutor(
    private val context: Context? = null,
    private val environmentInspector: EnvironmentInspector = EnvironmentInspector(context)
) {

    suspend fun executeCommand(
        rawCommand: String,
        workingDirectory: File?,
        userApprovedMutatingCommand: Boolean = false,
        timeoutMs: Long = 60_000L
    ): CommandExecutionResult = withContext(Dispatchers.IO) {
        val safety: CommandSafetyAssessment = CommandSafetyClassifier.assess(rawCommand)

        if (safety.level == CommandSafetyLevel.FORBIDDEN) {
            return@withContext CommandExecutionResult(
                command = rawCommand,
                executed = false,
                isSuccess = false,
                exitCode = -1,
                stdout = "",
                stderr = "Blocked by CommandSafetyClassifier: ${safety.reason}",
                safetyLevel = safety.level.name,
                environmentNote = "Forbidden command blocked."
            )
        }

        if (safety.level == CommandSafetyLevel.REQUIRES_USER_CONFIRMATION && !userApprovedMutatingCommand) {
            return@withContext CommandExecutionResult(
                command = rawCommand,
                executed = false,
                isSuccess = false,
                exitCode = -2,
                stdout = "",
                stderr = "AWAITING_USER_CONFIRMATION: ${safety.reason}",
                safetyLevel = safety.level.name,
                environmentNote = "Requires explicit user confirmation before execution."
            )
        }

        val caps = environmentInspector.inspectCapabilities(workingDirectory)
        if (!caps.localShellAvailable) {
            return@withContext CommandExecutionResult(
                command = rawCommand,
                executed = false,
                isSuccess = false,
                exitCode = -3,
                stdout = "",
                stderr = "Local shell execution is not supported on this device environment.",
                safetyLevel = safety.level.name,
                environmentNote = caps.summaryNote
            )
        }

        if (workingDirectory != null && (!workingDirectory.exists() || !workingDirectory.isDirectory)) {
            return@withContext CommandExecutionResult(
                command = rawCommand,
                executed = false,
                isSuccess = false,
                exitCode = -4,
                stdout = "",
                stderr = "Working directory does not exist or is only accessible via SAF URI stream. To run native shell commands on SAF folders, configure Termux shared storage path.",
                safetyLevel = safety.level.name,
                environmentNote = caps.summaryNote
            )
        }

        return@withContext try {
            val pb = ProcessBuilder("sh", "-c", safety.normalizedCommand)
            if (workingDirectory != null) {
                pb.directory(workingDirectory)
            }
            val process = pb.start()

            val finishedInTime = withTimeoutOrNull(timeoutMs) {
                val outText = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                val errText = process.errorStream.bufferedReader(StandardCharsets.UTF_8).readText()
                val code = process.waitFor()
                Triple(code, outText, errText)
            }

            if (finishedInTime == null) {
                process.destroyForcibly()
                CommandExecutionResult(
                    command = safety.normalizedCommand,
                    executed = true,
                    isSuccess = false,
                    exitCode = 124,
                    stdout = "",
                    stderr = "Command timed out after ${timeoutMs / 1000} seconds.",
                    safetyLevel = safety.level.name,
                    environmentNote = caps.summaryNote
                )
            } else {
                val (code, out, err) = finishedInTime
                CommandExecutionResult(
                    command = safety.normalizedCommand,
                    executed = true,
                    isSuccess = (code == 0),
                    exitCode = code,
                    stdout = out.take(16_000),
                    stderr = err.take(16_000),
                    safetyLevel = safety.level.name,
                    environmentNote = caps.summaryNote
                )
            }
        } catch (e: Exception) {
            CommandExecutionResult(
                command = safety.normalizedCommand,
                executed = false,
                isSuccess = false,
                exitCode = -5,
                stdout = "",
                stderr = "Process execution failed: ${e.message}",
                safetyLevel = safety.level.name,
                environmentNote = caps.summaryNote
            )
        }
    }

    /**
     * Dispatches a command to Termux's official `RUN_COMMAND` Intent API when installed and permitted.
     * Requires the user to enable `allow-external-apps = true` in `~/.termux/termux.properties`.
     */
    fun dispatchTermuxCommand(
        executablePath: String,
        arguments: Array<String>,
        workingDirPath: String
    ): Result<Boolean> {
        val ctx = context ?: return Result.failure(IllegalStateException("Android Context not available."))
        val caps = environmentInspector.inspectCapabilities()
        if (!caps.termuxInstalled || !caps.termuxRunCommandPermissionGranted) {
            return Result.failure(
                IllegalStateException("Termux is either not installed or RUN_COMMAND permission is not granted.")
            )
        }

        val intent = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", executablePath)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
            putExtra("com.termux.RUN_COMMAND_WORKDIR", workingDirPath)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)

            val resultIntent = Intent(ctx, TermuxResultService::class.java)
            val pendingIntent = PendingIntent.getService(
                ctx,
                1001,
                resultIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE
            )
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
        }

        return runCatching {
            ctx.startService(intent)
            true
        }
    }
}
