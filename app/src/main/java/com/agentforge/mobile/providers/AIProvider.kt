package com.agentforge.mobile.providers

import kotlinx.coroutines.flow.Flow

/**
 * Unified interface implemented by every AI model provider in AgentForge Mobile
 * (`OpenRouterProvider`, `GeminiProvider`, `GroqProvider`, `GitHubModelsProvider`,
 * `HuggingFaceProvider`, and custom OpenAI-compatible endpoints).
 */
interface AIProvider {
    val providerId: String
    val displayName: String
    val baseUrl: String

    /**
     * Verifies network connectivity and API key validity against the provider's official endpoint,
     * returning quota/rate-limit headers when exposed by the provider.
     */
    suspend fun verifyConnectionAndKey(apiKey: String): Result<ProviderHealthStatus>

    /**
     * Dynamically discovers available models from the provider's official catalog API,
     * distinguishing free-tier models from paid models.
     */
    suspend fun discoverModels(apiKey: String): Result<List<AIModelDescriptor>>

    /**
     * Sends a conversation turn with optional tool schemas and returns a structured
     * [AgentTurnResponse] (either via native tool calling or validated JSON tool fallback).
     */
    suspend fun generateTurn(
        apiKey: String,
        model: AIModelDescriptor,
        messages: List<AgentMessage>,
        tools: List<ToolDefinitionSchema>,
        temperature: Double = 0.1
    ): Result<AgentTurnResponse>

    /**
     * Streams incremental text deltas when supported by the provider, emitting the final
     * [AgentTurnResponse] upon completion.
     */
    fun streamTurn(
        apiKey: String,
        model: AIModelDescriptor,
        messages: List<AgentMessage>,
        tools: List<ToolDefinitionSchema>,
        temperature: Double = 0.1
    ): Flow<StreamChunk>

    /**
     * Cancels any in-flight HTTP calls associated with the current task.
     */
    fun cancelInFlightRequests()
}

sealed class StreamChunk {
    data class TextDelta(val delta: String) : StreamChunk()
    data class Completed(val response: AgentTurnResponse) : StreamChunk()
    data class Error(val exception: AIProviderException) : StreamChunk()
}
