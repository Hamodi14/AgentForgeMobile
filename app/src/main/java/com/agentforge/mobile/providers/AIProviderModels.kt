package com.agentforge.mobile.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Detailed classification of provider errors so [com.agentforge.mobile.routing.SmartModelRouter]
 * can distinguish between permanent auth errors, rate limits with Retry-After, quota exhaustion,
 * context overflow, and lack of native tool support.
 */
@Serializable
enum class ProviderErrorType {
    QUOTA_EXHAUSTED,
    RATE_LIMIT_EXCEEDED,
    INVALID_API_KEY,
    NETWORK_DISCONNECTED,
    SERVER_UNAVAILABLE,
    TOOL_CALLING_UNSUPPORTED,
    CONTEXT_LENGTH_EXCEEDED,
    REQUEST_REJECTED,
    BAD_REQUEST
}

@Serializable
data class ProviderExceptionInfo(
    val errorType: ProviderErrorType,
    val httpStatusCode: Int? = null,
    val retryAfterSeconds: Long? = null,
    val rawMessage: String,
    val isRetryableOnSameProvider: Boolean
)

class AIProviderException(
    val info: ProviderExceptionInfo,
    cause: Throwable? = null
) : Exception(info.rawMessage, cause)

@Serializable
data class AIModelDescriptor(
    val providerId: String,
    val modelId: String,
    val displayName: String,
    val isFreeTier: Boolean,
    val supportsNativeToolCalling: Boolean,
    val supportsJsonFallbackTools: Boolean = true,
    val supportsStreaming: Boolean = true,
    val contextWindowTokens: Int = 32768,
    val maxOutputTokens: Int = 4096,
    val promptPricePerMillionUsd: Double = 0.0,
    val completionPricePerMillionUsd: Double = 0.0,
    val lastUpdatedEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class ToolParameterProperty(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null
)

@Serializable
data class ToolDefinitionSchema(
    val name: String,
    val description: String,
    val parameters: Map<String, ToolParameterProperty>,
    val requiredParameters: List<String>
)

@Serializable
data class AgentToolCallRequest(
    val id: String,
    val toolName: String,
    val arguments: JsonObject
)

@Serializable
data class AgentMessage(
    val role: String, // "system", "user", "assistant", "tool"
    val content: String,
    val toolCalls: List<AgentToolCallRequest> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null
)

@Serializable
data class AgentTurnResponse(
    val providerId: String,
    val modelId: String,
    val assistantText: String,
    val toolCalls: List<AgentToolCallRequest> = emptyList(),
    val promptTokensUsed: Int = 0,
    val completionTokensUsed: Int = 0,
    val finishReason: String = "stop",
    val usedJsonFallbackParser: Boolean = false
)

@Serializable
data class ProviderHealthStatus(
    val providerId: String,
    val isReachable: Boolean,
    val isKeyValid: Boolean,
    val statusMessage: String,
    val quotaSummary: String,
    val discoveredFreeModelsCount: Int = 0,
    val discoveredTotalModelsCount: Int = 0,
    val checkedAtEpochMs: Long = System.currentTimeMillis()
)
