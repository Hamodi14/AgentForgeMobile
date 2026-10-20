package com.agentforge.mobile.tools

import com.agentforge.mobile.providers.ToolDefinitionSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolExecutionContext(
    val projectId: String,
    val taskId: String,
    val autoExecutionMode: Boolean,
    val userApprovedDestructiveAction: Boolean = false,
    val maxFileReadBytes: Int = 256 * 1024
)

@Serializable
data class ToolExecutionResult(
    val toolName: String,
    val isSuccess: Boolean,
    val requiresUserConfirmation: Boolean = false,
    val confirmationPrompt: String? = null,
    val targetFilePath: String? = null,
    val outputText: String,
    val errorMessage: String? = null
)

/**
 * Contract for every real agent tool in AgentForge Mobile.
 */
interface AgentTool {
    val schema: ToolDefinitionSchema
    val isDestructiveOrRequiresConfirmationInAutoMode: Boolean

    suspend fun execute(
        arguments: JsonObject,
        context: ToolExecutionContext
    ): ToolExecutionResult
}
