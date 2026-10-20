package com.agentforge.mobile.agent

import com.agentforge.mobile.providers.AIProviderException
import com.agentforge.mobile.providers.AgentMessage
import com.agentforge.mobile.providers.AgentToolCallRequest
import com.agentforge.mobile.providers.ProviderErrorType
import com.agentforge.mobile.routing.SmartModelRouter
import com.agentforge.mobile.security.KeystoreSecretManager
import com.agentforge.mobile.storage.AgentForgeDao
import com.agentforge.mobile.storage.AgentUserPreferences
import com.agentforge.mobile.storage.ChatMessageEntity
import com.agentforge.mobile.storage.ExecutionLogEntity
import com.agentforge.mobile.storage.TaskRunStatus
import com.agentforge.mobile.storage.TaskSessionEntity
import com.agentforge.mobile.tools.AgentToolkitRegistry
import com.agentforge.mobile.tools.ToolExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class PendingApprovalState(
    val taskId: String,
    val toolCall: AgentToolCallRequest,
    val promptMessage: String
)

@Serializable
data class OrchestratorUiState(
    val activeTaskId: String? = null,
    val status: TaskRunStatus = TaskRunStatus.IDLE,
    val activeProviderId: String = "",
    val activeModelId: String = "",
    val completedSteps: Int = 0,
    val totalPlannedSteps: Int = 0,
    val modifiedFiles: Set<String> = emptySet(),
    val inspectedFiles: Set<String> = emptySet(),
    val lastOperationSummary: String = "Ready",
    val pendingApproval: PendingApprovalState? = null,
    val isNetworkAvailable: Boolean = true,
    val lastError: String? = null
) {
    /**
     * Real step-based completion percentage (0..100). Never fabricates arbitrary percentages.
     */
    val progressFraction: Float
        get() = when {
            status == TaskRunStatus.COMPLETED -> 1.0f
            totalPlannedSteps <= 0 -> 0.0f
            else -> (completedSteps.toFloat() / totalPlannedSteps.toFloat()).coerceIn(0.0f, 0.95f)
        }
}

/**
 * Core Autonomous AI Coding Agent Engine (`AgentOrchestrator`).
 *
 * Implements the full 12-stage execution loop:
 * 1. Understands user goal & constraints
 * 2. Inspects project structure & relevant files (`inspect_project`, `list_files`, `search_in_files`)
 * 3. Identifies programming language & build system
 * 4. Builds & updates a step-by-step execution plan
 * 5. Selects tools and files per step
 * 6. Executes real file reads, creations, writes, and patches via [AgentToolkitRegistry]
 * 7. Verifies every tool outcome (never assumes success without checking `ToolExecutionResult.isSuccess`)
 * 8. Runs tests or build checks when supported by the device environment
 * 9. Reads real errors and iterates to fix them
 * 10. Continues autonomously until goal completion, user pause/approval gate, or real blocker
 * 11. Persists task state, checkpoints, modified files, and execution logs to Room after every step
 * 12. Generates a transparent final report detailing completed work, modified files, and any environment limitations
 */
class AgentOrchestrator(
    private val dao: AgentForgeDao,
    private val toolkit: AgentToolkitRegistry,
    private val router: SmartModelRouter,
    private val secretRedactor: (String) -> String = { it }
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _uiState = MutableStateFlow(OrchestratorUiState())
    val uiState: StateFlow<OrchestratorUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    companion object {
        const val AGENT_SYSTEM_PROMPT = """
You are AgentForge Mobile, an autonomous AI software engineering agent operating directly on a real project directory on Android.

CRITICAL RULES:
1. Do NOT merely reply with code snippets or theoretical advice. When the user asks you to build, fix, refactor, or inspect something, you MUST call your real tools (`inspect_project`, `list_files`, `read_file`, `search_in_files`, `create_file`, `write_file`, `apply_patch`, `run_tests`, `build_project`, etc.) to act directly on the project files.
2. Always inspect the project structure or read target files before modifying existing code.
3. Verify the result of every tool call. If an operation fails or a patch does not match, read the file again and fix the issue.
4. Respect project boundaries: use only relative paths inside the project root (never `../` or system paths).
5. Never claim a build or test succeeded unless `build_project` or `run_tests` returned `isSuccess = true` with exit code 0. If the mobile environment lacks local JDK/Gradle, state honestly that the source files were created/updated and verified on disk, while compilation was skipped due to missing local build tools.
6. When all requested file changes and verifications are complete, provide a clear final report listing:
   - What was accomplished
   - Every file created or modified
   - Verification/test/build status and any remaining environment constraints
"""
    }

    /**
     * Starts a new autonomous coding task on [projectId] or executes synchronously inside [scope].
     */
    suspend fun executeTaskSession(
        projectId: String,
        userGoal: String,
        preferences: AgentUserPreferences,
        resumedSession: TaskSessionEntity? = null,
        approvedPendingToolCall: Boolean = false
    ): TaskSessionEntity {
        val taskId = resumedSession?.id ?: UUID.randomUUID().toString()
        val modifiedFiles = resumedSession?.modifiedFilesJson?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
        }?.toMutableSet() ?: mutableSetOf()

        val inspectedFiles = resumedSession?.inspectedFilesJson?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
        }?.toMutableSet() ?: mutableSetOf()

        val toolHistorySummary = resumedSession?.toolHistorySummaryJson?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
        }?.toMutableList() ?: mutableListOf()

        var session = resumedSession?.copy(
            status = TaskRunStatus.ANALYZING,
            pendingApprovalJson = null,
            lastErrorMessage = null,
            updatedAtEpochMs = System.currentTimeMillis()
        ) ?: TaskSessionEntity(
            id = taskId,
            projectId = projectId,
            userGoal = userGoal,
            status = TaskRunStatus.ANALYZING,
            totalSteps = 4,
            currentStepIndex = 0
        )
        dao.upsertTaskSession(session)

        if (resumedSession == null) {
            dao.insertChatMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    taskId = taskId,
                    role = "user",
                    content = userGoal
                )
            )
        }

        _uiState.value = _uiState.value.copy(
            activeTaskId = taskId,
            status = TaskRunStatus.ANALYZING,
            completedSteps = session.currentStepIndex,
            totalPlannedSteps = session.totalSteps.coerceAtLeast(1),
            modifiedFiles = modifiedFiles,
            inspectedFiles = inspectedFiles,
            pendingApproval = null,
            lastError = null,
            lastOperationSummary = "Analyzing project and planning execution..."
        )

        val conversation = mutableListOf<AgentMessage>()
        conversation.add(AgentMessage(role = "system", content = AGENT_SYSTEM_PROMPT))

        if (resumedSession != null && toolHistorySummary.isNotEmpty()) {
            conversation.add(
                AgentMessage(
                    role = "user",
                    content = buildString {
                        appendLine("Original Goal: ${session.userGoal}")
                        appendLine("Resuming task from checkpoint step ${session.currentStepIndex}.")
                        appendLine("Files already modified: ${modifiedFiles.joinToString(", ").ifEmpty { "None" }}")
                        appendLine("Completed tool operations so far:")
                        toolHistorySummary.forEach { appendLine("- $it") }
                    }
                )
            )
        } else {
            conversation.add(AgentMessage(role = "user", content = userGoal))
        }

        // If resuming from a user-approved pending tool call, execute that tool call first
        val pendingJson = resumedSession?.pendingApprovalJson
        if (approvedPendingToolCall && !pendingJson.isNullOrBlank()) {
            val pendingState = runCatching {
                json.decodeFromString<PendingApprovalState>(pendingJson)
            }.getOrNull()
            if (pendingState != null) {
                val toolOutcome = executeAndLogToolCall(
                    projectId = projectId,
                    taskId = taskId,
                    stepIndex = session.currentStepIndex + 1,
                    toolCall = pendingState.toolCall,
                    preferences = preferences,
                    userApprovedDestructive = true,
                    providerId = session.activeProviderId.ifEmpty { "user_approved" },
                    modelId = session.activeModelId.ifEmpty { "user_approved" },
                    modifiedFiles = modifiedFiles,
                    inspectedFiles = inspectedFiles,
                    toolHistorySummary = toolHistorySummary
                )
                conversation.add(
                    AgentMessage(
                        role = "tool",
                        content = toolOutcome.outputText.ifEmpty { toolOutcome.errorMessage ?: "Completed" },
                        toolCallId = pendingState.toolCall.id,
                        toolName = pendingState.toolCall.toolName
                    )
                )
                session = session.copy(
                    currentStepIndex = session.currentStepIndex + 1,
                    lastSafeCheckpointStep = session.currentStepIndex + 1,
                    modifiedFilesJson = json.encodeToString(modifiedFiles.toList()),
                    inspectedFilesJson = json.encodeToString(inspectedFiles.toList()),
                    toolHistorySummaryJson = json.encodeToString(toolHistorySummary),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                dao.upsertTaskSession(session)
            }
        }

        val maxIterations = preferences.maxLoopIterations.coerceIn(1, 50)
        var iteration = session.loopIterationCount

        while (iteration < maxIterations) {
            iteration++
            val providerConfigs = dao.getProviderConfigs()
            val handOffSummary = buildHandOffSummary(session, modifiedFiles, inspectedFiles, toolHistorySummary)

            val turnResult = router.executeRoutedTurn(
                routingMode = preferences.routingMode,
                providerConfigs = providerConfigs,
                messages = conversation,
                tools = toolkit.getAllSchemas(),
                taskHandOffSummary = handOffSummary,
                manualProviderId = preferences.manualProviderId,
                manualModelId = preferences.manualModelId,
                retryWaitSeconds = preferences.retryWaitPolicySeconds,
                maxRetriesPerProvider = preferences.maxRetriesPerProvider
            )

            if (turnResult.isFailure) {
                val err = turnResult.exceptionOrNull()
                val rawErrMsg = secretRedactor(err?.message ?: "Provider communication failed.")
                val isNetworkDisconnect = (err as? AIProviderException)?.info?.errorType ==
                    ProviderErrorType.NETWORK_DISCONNECTED

                val pausedOrFailedStatus = if (isNetworkDisconnect) TaskRunStatus.PAUSED else TaskRunStatus.FAILED
                session = session.copy(
                    status = pausedOrFailedStatus,
                    loopIterationCount = iteration,
                    lastErrorMessage = rawErrMsg,
                    contextSummary = handOffSummary,
                    modifiedFilesJson = json.encodeToString(modifiedFiles.toList()),
                    inspectedFilesJson = json.encodeToString(inspectedFiles.toList()),
                    toolHistorySummaryJson = json.encodeToString(toolHistorySummary),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                dao.upsertTaskSession(session)
                dao.insertChatMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        projectId = projectId,
                        taskId = taskId,
                        role = "system_event",
                        content = rawErrMsg,
                        isSuccess = false
                    )
                )
                _uiState.value = _uiState.value.copy(
                    status = pausedOrFailedStatus,
                    isNetworkAvailable = !isNetworkDisconnect,
                    lastError = rawErrMsg,
                    lastOperationSummary = rawErrMsg
                )
                return session
            }

            val outcome = turnResult.getOrThrow()

            // Log any provider failover events
            for (failover in outcome.failoverEvents) {
                val cleanReason = secretRedactor(failover.reason)
                dao.insertExecutionLog(
                    ExecutionLogEntity(
                        id = UUID.randomUUID().toString(),
                        projectId = projectId,
                        taskId = taskId,
                        stepIndex = session.currentStepIndex,
                        toolName = "model_router_failover",
                        targetFilePath = null,
                        providerId = "${failover.fromProviderId} -> ${failover.toProviderId}",
                        modelId = "${failover.fromModelId} -> ${failover.toModelId}",
                        isFailoverEvent = true,
                        failoverReason = cleanReason,
                        isSuccess = true,
                        summaryMessage = "Switched from ${failover.fromProviderId}/${failover.fromModelId} to ${failover.toProviderId}/${failover.toModelId} (${failover.errorType})",
                        errorDetail = cleanReason,
                        startedAtEpochMs = failover.timestampEpochMs,
                        completedAtEpochMs = System.currentTimeMillis()
                    )
                )
                dao.insertChatMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        projectId = projectId,
                        taskId = taskId,
                        role = "system_event",
                        content = "تم التبديل التلقائي للنموذج من `${failover.fromProviderId}/${failover.fromModelId}` إلى `${failover.toProviderId}/${failover.toModelId}` بسبب: ${failover.errorType} ($cleanReason)",
                        providerId = failover.toProviderId,
                        modelId = failover.toModelId
                    )
                )
            }

            val turn = outcome.response
            session = session.copy(
                activeProviderId = turn.providerId,
                activeModelId = turn.modelId,
                loopIterationCount = iteration,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            _uiState.value = _uiState.value.copy(
                activeProviderId = turn.providerId,
                activeModelId = turn.modelId,
                isNetworkAvailable = true
            )

            if (turn.assistantText.isNotBlank()) {
                dao.insertChatMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        projectId = projectId,
                        taskId = taskId,
                        role = "assistant",
                        content = turn.assistantText,
                        providerId = turn.providerId,
                        modelId = turn.modelId
                    )
                )
            }

            conversation.add(
                AgentMessage(
                    role = "assistant",
                    content = turn.assistantText,
                    toolCalls = turn.toolCalls
                )
            )

            // If the model emitted no tool calls, the task has reached its final report
            if (turn.toolCalls.isEmpty()) {
                val finalReportText = turn.assistantText.ifBlank {
                    "Task completed. Modified files: ${modifiedFiles.joinToString(", ").ifEmpty { "None" }}"
                }
                session = session.copy(
                    status = TaskRunStatus.COMPLETED,
                    totalSteps = session.currentStepIndex.coerceAtLeast(1),
                    finalReport = finalReportText,
                    modifiedFilesJson = json.encodeToString(modifiedFiles.toList()),
                    inspectedFilesJson = json.encodeToString(inspectedFiles.toList()),
                    toolHistorySummaryJson = json.encodeToString(toolHistorySummary),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                dao.upsertTaskSession(session)
                _uiState.value = _uiState.value.copy(
                    status = TaskRunStatus.COMPLETED,
                    completedSteps = session.currentStepIndex,
                    totalPlannedSteps = session.currentStepIndex.coerceAtLeast(1),
                    modifiedFiles = modifiedFiles,
                    inspectedFiles = inspectedFiles,
                    lastOperationSummary = "Task completed (${modifiedFiles.size} file(s) modified)."
                )
                return session
            }

            // Otherwise execute each requested tool call in sequence
            val plannedTotal = (session.currentStepIndex + turn.toolCalls.size + 1).coerceAtLeast(session.totalSteps)
            for (toolCall in turn.toolCalls) {
                val isTestingTool = toolCall.toolName == "run_tests" || toolCall.toolName == "build_project"
                val currentStatus = if (isTestingTool) TaskRunStatus.TESTING else TaskRunStatus.EXECUTING
                _uiState.value = _uiState.value.copy(
                    status = currentStatus,
                    totalPlannedSteps = plannedTotal,
                    lastOperationSummary = "Running ${toolCall.toolName}..."
                )

                val toolDef = toolkit.getTool(toolCall.toolName)
                val needsManualGate = (!preferences.autoExecutionMode && isMutatingTool(toolCall.toolName)) ||
                    (toolDef?.isDestructiveOrRequiresConfirmationInAutoMode == true)

                val toolResult = executeAndLogToolCall(
                    projectId = projectId,
                    taskId = taskId,
                    stepIndex = session.currentStepIndex + 1,
                    toolCall = toolCall,
                    preferences = preferences,
                    userApprovedDestructive = !needsManualGate,
                    providerId = turn.providerId,
                    modelId = turn.modelId,
                    modifiedFiles = modifiedFiles,
                    inspectedFiles = inspectedFiles,
                    toolHistorySummary = toolHistorySummary
                )

                if (toolResult.requiresUserConfirmation) {
                    val approvalState = PendingApprovalState(
                        taskId = taskId,
                        toolCall = toolCall,
                        promptMessage = toolResult.confirmationPrompt
                            ?: "Confirm execution of '${toolCall.toolName}'?"
                    )
                    session = session.copy(
                        status = TaskRunStatus.AWAITING_APPROVAL,
                        pendingApprovalJson = json.encodeToString(approvalState),
                        contextSummary = buildHandOffSummary(session, modifiedFiles, inspectedFiles, toolHistorySummary),
                        modifiedFilesJson = json.encodeToString(modifiedFiles.toList()),
                        inspectedFilesJson = json.encodeToString(inspectedFiles.toList()),
                        toolHistorySummaryJson = json.encodeToString(toolHistorySummary),
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                    dao.upsertTaskSession(session)
                    _uiState.value = _uiState.value.copy(
                        status = TaskRunStatus.AWAITING_APPROVAL,
                        pendingApproval = approvalState,
                        lastOperationSummary = approvalState.promptMessage
                    )
                    return session
                }

                val stepAfter = session.currentStepIndex + 1
                session = session.copy(
                    status = currentStatus,
                    currentStepIndex = stepAfter,
                    totalSteps = plannedTotal,
                    lastSafeCheckpointStep = stepAfter,
                    modifiedFilesJson = json.encodeToString(modifiedFiles.toList()),
                    inspectedFilesJson = json.encodeToString(inspectedFiles.toList()),
                    toolHistorySummaryJson = json.encodeToString(toolHistorySummary),
                    updatedAtEpochMs = System.currentTimeMillis()
                )
                dao.upsertTaskSession(session)

                _uiState.value = _uiState.value.copy(
                    completedSteps = stepAfter,
                    totalPlannedSteps = plannedTotal,
                    modifiedFiles = modifiedFiles.toSet(),
                    inspectedFiles = inspectedFiles.toSet(),
                    lastOperationSummary = if (toolResult.isSuccess) {
                        "Completed ${toolCall.toolName} ${toolResult.targetFilePath.orEmpty()}"
                    } else {
                        "Error in ${toolCall.toolName}: ${toolResult.errorMessage}"
                    }
                )

                val toolFeedbackText = if (toolResult.isSuccess) {
                    toolResult.outputText
                } else {
                    "TOOL_ERROR: ${toolResult.errorMessage}
${toolResult.outputText}"
                }

                conversation.add(
                    AgentMessage(
                        role = "tool",
                        content = toolFeedbackText,
                        toolCallId = toolCall.id,
                        toolName = toolCall.toolName
                    )
                )
            }
        }

        // Reached max iterations limit: checkpoint and report transparently (never claim fake completion)
        val limitMsg = "تم الوصول إلى الحد الأقصى لدورات التنفيذ المتتالية ($maxIterations دورة). تم حفظ الحالة والتغييرات المنجزة (${modifiedFiles.size} ملفات معدلة) لاستئناف المهمة بأمان."
        session = session.copy(
            status = TaskRunStatus.PAUSED,
            lastErrorMessage = limitMsg,
            contextSummary = buildHandOffSummary(session, modifiedFiles, inspectedFiles, toolHistorySummary),
            modifiedFilesJson = json.encodeToString(modifiedFiles.toList()),
            inspectedFilesJson = json.encodeToString(inspectedFiles.toList()),
            toolHistorySummaryJson = json.encodeToString(toolHistorySummary),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertTaskSession(session)
        dao.insertChatMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                taskId = taskId,
                role = "system_event",
                content = limitMsg,
                isSuccess = false
            )
        )
        _uiState.value = _uiState.value.copy(
            status = TaskRunStatus.PAUSED,
            lastError = limitMsg,
            lastOperationSummary = limitMsg
        )
        return session
    }

    fun startTaskAsync(
        scope: CoroutineScope,
        projectId: String,
        userGoal: String,
        preferences: AgentUserPreferences
    ) {
        activeJob?.cancel()
        activeJob = scope.launch(Dispatchers.IO) {
            try {
                executeTaskSession(
                    projectId = projectId,
                    userGoal = userGoal,
                    preferences = preferences
                )
            } catch (e: CancellationException) {
                // Task paused or cancelled by user
            }
        }
    }

    suspend fun pauseActiveTask(projectId: String) {
        activeJob?.cancel()
        val latest = dao.getLatestTaskForProject(projectId) ?: return
        val paused = latest.copy(
            status = TaskRunStatus.PAUSED,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertTaskSession(paused)
        _uiState.value = _uiState.value.copy(
            status = TaskRunStatus.PAUSED,
            lastOperationSummary = "Task paused at step ${paused.currentStepIndex}. Ready to resume."
        )
    }

    fun resumeTaskAsync(
        scope: CoroutineScope,
        projectId: String,
        preferences: AgentUserPreferences,
        approvePendingAction: Boolean = false
    ) {
        activeJob?.cancel()
        activeJob = scope.launch(Dispatchers.IO) {
            val latest = dao.getLatestTaskForProject(projectId) ?: return@launch
            executeTaskSession(
                projectId = projectId,
                userGoal = latest.userGoal,
                preferences = preferences,
                resumedSession = latest,
                approvedPendingToolCall = approvePendingAction
            )
        }
    }

    suspend fun cancelActiveTask(projectId: String) {
        activeJob?.cancel()
        val latest = dao.getLatestTaskForProject(projectId)
        if (latest != null) {
            dao.upsertTaskSession(
                latest.copy(
                    status = TaskRunStatus.IDLE,
                    pendingApprovalJson = null,
                    lastErrorMessage = "Cancelled by user at step ${latest.currentStepIndex}.",
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
        _uiState.value = _uiState.value.copy(
            status = TaskRunStatus.IDLE,
            pendingApproval = null,
            lastOperationSummary = "Task cancelled."
        )
    }

    private suspend fun executeAndLogToolCall(
        projectId: String,
        taskId: String,
        stepIndex: Int,
        toolCall: AgentToolCallRequest,
        preferences: AgentUserPreferences,
        userApprovedDestructive: Boolean,
        providerId: String,
        modelId: String,
        modifiedFiles: MutableSet<String>,
        inspectedFiles: MutableSet<String>,
        toolHistorySummary: MutableList<String>
    ) = run {
        val startedAt = System.currentTimeMillis()
        val execContext = ToolExecutionContext(
            projectId = projectId,
            taskId = taskId,
            autoExecutionMode = preferences.autoExecutionMode,
            userApprovedDestructiveAction = userApprovedDestructive,
            maxFileReadBytes = preferences.maxFileReadBytes
        )
        val result = toolkit.invokeTool(toolCall.toolName, toolCall.arguments, execContext)
        val completedAt = System.currentTimeMillis()

        if (result.isSuccess) {
            result.targetFilePath?.let { path ->
                if (isMutatingTool(toolCall.toolName)) {
                    modifiedFiles.add(path)
                } else {
                    inspectedFiles.add(path)
                }
            }
            toolHistorySummary.add(
                "Step $stepIndex: ${toolCall.toolName}(${result.targetFilePath.orEmpty()}) -> SUCCESS"
            )
        } else if (!result.requiresUserConfirmation) {
            toolHistorySummary.add(
                "Step $stepIndex: ${toolCall.toolName}(${result.targetFilePath.orEmpty()}) -> ERROR: ${result.errorMessage}"
            )
        }

        val redactedSummary = secretRedactor(
            result.outputText.lines().firstOrNull()?.take(180)
                ?: result.errorMessage
                ?: toolCall.toolName
        )
        val redactedError = result.errorMessage?.let { secretRedactor(it) }

        dao.insertExecutionLog(
            ExecutionLogEntity(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                taskId = taskId,
                stepIndex = stepIndex,
                toolName = toolCall.toolName,
                targetFilePath = result.targetFilePath,
                providerId = providerId,
                modelId = modelId,
                isFailoverEvent = false,
                isSuccess = result.isSuccess,
                summaryMessage = redactedSummary,
                errorDetail = redactedError,
                startedAtEpochMs = startedAt,
                completedAtEpochMs = completedAt
            )
        )

        dao.insertChatMessage(
            ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                taskId = taskId,
                role = "tool",
                content = buildString {
                    appendLine("Tool: `${toolCall.toolName}` ${result.targetFilePath?.let { "($it)" }.orEmpty()}")
                    if (result.isSuccess) {
                        appendLine(secretRedactor(result.outputText.take(1500)))
                    } else {
                        appendLine("Error: ${redactedError.orEmpty()}")
                    }
                },
                providerId = providerId,
                modelId = modelId,
                toolName = toolCall.toolName,
                isSuccess = result.isSuccess
            )
        )

        result
    }

    private fun isMutatingTool(toolName: String): Boolean {
        return toolName in setOf(
            "create_file",
            "write_file",
            "apply_patch",
            "create_directory",
            "move_file",
            "rename_file",
            "delete_file",
            "restore_backup",
            "run_command"
        )
    }

    private fun buildHandOffSummary(
        session: TaskSessionEntity,
        modifiedFiles: Set<String>,
        inspectedFiles: Set<String>,
        toolHistorySummary: List<String>
    ): String = buildString {
        appendLine("User Goal: ${session.userGoal}")
        appendLine("Current Checkpoint Step: ${session.currentStepIndex}")
        appendLine("Inspected Files: ${inspectedFiles.joinToString(", ").ifEmpty { "None" }}")
        appendLine("Modified Files: ${modifiedFiles.joinToString(", ").ifEmpty { "None" }}")
        appendLine("Recent Tool Executions:")
        toolHistorySummary.takeLast(10).forEach { appendLine("- $it") }
    }
}
