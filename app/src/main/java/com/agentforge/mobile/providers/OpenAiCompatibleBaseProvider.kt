package com.agentforge.mobile.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

/**
 * Shared HTTP + JSON implementation for OpenAI-compatible chat completion providers
 * (OpenRouter, Groq, GitHub Models, Hugging Face Inference Router).
 *
 * Handles native `tools` serialization, automatic fallback to [JsonToolCallFallbackParser]
 * when a model does not support native function calling, HTTP status and Retry-After header
 * parsing, and request cancellation.
 */
abstract class OpenAiCompatibleBaseProvider(
    protected val httpClient: OkHttpClient
) : AIProvider {

    protected val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    protected open fun additionalHeaders(apiKey: String): Map<String, String> = emptyMap()

    protected open fun chatCompletionsUrl(): String = "${baseUrl.trimEnd('/')}/chat/completions"

    protected open fun modelsUrl(): String = "${baseUrl.trimEnd('/')}/models"

    override fun cancelInFlightRequests() {
        for (call in httpClient.dispatcher.queuedCalls()) {
            if (call.request().tag(String::class.java) == providerId) call.cancel()
        }
        for (call in httpClient.dispatcher.runningCalls()) {
            if (call.request().tag(String::class.java) == providerId) call.cancel()
        }
    }

    override suspend fun generateTurn(
        apiKey: String,
        model: AIModelDescriptor,
        messages: List<AgentMessage>,
        tools: List<ToolDefinitionSchema>,
        temperature: Double
    ): Result<AgentTurnResponse> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                AIProviderException(
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.INVALID_API_KEY,
                        rawMessage = "API key for $displayName is empty.",
                        isRetryableOnSameProvider = false
                    )
                )
            )
        }

        val useNativeTools = model.supportsNativeToolCalling && tools.isNotEmpty()
        val effectiveMessages = if (!useNativeTools && tools.isNotEmpty()) {
            injectFallbackToolInstructions(messages, tools)
        } else {
            messages
        }

        val payload = buildChatCompletionPayload(
            modelId = model.modelId,
            messages = effectiveMessages,
            tools = if (useNativeTools) tools else emptyList(),
            temperature = temperature
        )

        val requestBuilder = Request.Builder()
            .url(chatCompletionsUrl())
            .tag(String::class.java, providerId)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")

        for ((k, v) in additionalHeaders(apiKey)) {
            requestBuilder.header(k, v)
        }

        val request = requestBuilder
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    val classified = classifyHttpError(response.code, rawBody, retryAfter)
                    return@withContext Result.failure(AIProviderException(classified))
                }
                return@withContext parseChatCompletionResponse(
                    rawBody = rawBody,
                    model = model,
                    allowedTools = tools,
                    usedNativeTools = useNativeTools
                )
            }
        } catch (e: UnknownHostException) {
            Result.failure(
                AIProviderException(
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.NETWORK_DISCONNECTED,
                        rawMessage = "Network unreachable while contacting $displayName: ${e.message}",
                        isRetryableOnSameProvider = true
                    ),
                    e
                )
            )
        } catch (e: SocketTimeoutException) {
            Result.failure(
                AIProviderException(
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.NETWORK_DISCONNECTED,
                        rawMessage = "Connection timed out while waiting for $displayName: ${e.message}",
                        isRetryableOnSameProvider = true
                    ),
                    e
                )
            )
        } catch (e: IOException) {
            Result.failure(
                AIProviderException(
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.NETWORK_DISCONNECTED,
                        rawMessage = "Network I/O error with $displayName: ${e.message}",
                        isRetryableOnSameProvider = true
                    ),
                    e
                )
            )
        } catch (e: Exception) {
            Result.failure(
                AIProviderException(
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.BAD_REQUEST,
                        rawMessage = "Unexpected error from $displayName: ${e.message}",
                        isRetryableOnSameProvider = false
                    ),
                    e
                )
            )
        }
    }

    override fun streamTurn(
        apiKey: String,
        model: AIModelDescriptor,
        messages: List<AgentMessage>,
        tools: List<ToolDefinitionSchema>,
        temperature: Double
    ): Flow<StreamChunk> = flow {
        val result = generateTurn(apiKey, model, messages, tools, temperature)
        result.fold(
            onSuccess = { turn ->
                if (turn.assistantText.isNotEmpty()) {
                    emit(StreamChunk.TextDelta(turn.assistantText))
                }
                emit(StreamChunk.Completed(turn))
            },
            onFailure = { err ->
                val providerEx = err as? AIProviderException ?: AIProviderException(
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.BAD_REQUEST,
                        rawMessage = err.message ?: "Unknown error",
                        isRetryableOnSameProvider = false
                    ),
                    err
                )
                emit(StreamChunk.Error(providerEx))
            }
        )
    }

    protected fun injectFallbackToolInstructions(
        messages: List<AgentMessage>,
        tools: List<ToolDefinitionSchema>
    ): List<AgentMessage> {
        val suffix = JsonToolCallFallbackParser.buildFallbackSystemPromptInstruction(tools)
        val result = messages.toMutableList()
        val sysIdx = result.indexOfFirst { it.role == "system" }
        if (sysIdx >= 0) {
            val old = result[sysIdx]
            result[sysIdx] = old.copy(content = old.content + "
" + suffix)
        } else {
            result.add(0, AgentMessage(role = "system", content = suffix))
        }
        return result
    }

    protected fun buildChatCompletionPayload(
        modelId: String,
        messages: List<AgentMessage>,
        tools: List<ToolDefinitionSchema>,
        temperature: Double
    ): JsonObject = buildJsonObject {
        put("model", modelId)
        put("temperature", temperature)
        put("messages", buildJsonArray {
            for (msg in messages) {
                add(buildJsonObject {
                    put("role", msg.role)
                    put("content", msg.content)
                    if (msg.toolCallId != null) {
                        put("tool_call_id", msg.toolCallId)
                    }
                    if (msg.toolName != null && msg.role == "tool") {
                        put("name", msg.toolName)
                    }
                    if (msg.toolCalls.isNotEmpty() && msg.role == "assistant") {
                        put("tool_calls", buildJsonArray {
                            for (tc in msg.toolCalls) {
                                add(buildJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", tc.toolName)
                                        put("arguments", tc.arguments.toString())
                                    })
                                })
                            }
                        })
                    }
                })
            }
        })

        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                for (tool in tools) {
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    for ((paramName, prop) in tool.parameters) {
                                        put(paramName, buildJsonObject {
                                            put("type", prop.type)
                                            put("description", prop.description)
                                            if (!prop.enumValues.isNullOrEmpty()) {
                                                put("enum", JsonArray(prop.enumValues.map { JsonPrimitive(it) }))
                                            }
                                        })
                                    }
                                })
                                put("required", JsonArray(tool.requiredParameters.map { JsonPrimitive(it) }))
                            })
                        })
                    })
                }
            })
            put("tool_choice", "auto")
        }
    }

    protected fun parseChatCompletionResponse(
        rawBody: String,
        model: AIModelDescriptor,
        allowedTools: List<ToolDefinitionSchema>,
        usedNativeTools: Boolean
    ): Result<AgentTurnResponse> {
        return try {
            val root = json.parseToJsonElement(rawBody).jsonObject
            if ("error" in root) {
                val errObj = root["error"]
                val msg = (errObj as? JsonObject)?.get("message")?.jsonPrimitive?.content
                    ?: errObj.toString()
                val classified = classifyHttpError(400, msg, null)
                return Result.failure(AIProviderException(classified))
            }

            val choices = root["choices"]?.jsonArray
            if (choices.isNullOrEmpty()) {
                return Result.failure(
                    AIProviderException(
                        ProviderExceptionInfo(
                            errorType = ProviderErrorType.BAD_REQUEST,
                            rawMessage = "Empty choices array in response from $displayName",
                            isRetryableOnSameProvider = false
                        )
                    )
                )
            }

            val firstChoice = choices[0].jsonObject
            val finishReason = firstChoice["finish_reason"]?.jsonPrimitive?.content ?: "stop"
            val messageObj = firstChoice["message"]?.jsonObject ?: JsonObject(emptyMap())
            val contentText = messageObj["content"]?.let {
                if (it is JsonPrimitive) it.content else it.toString()
            }.orEmpty()

            val usageObj = root["usage"]?.jsonObject
            val promptTokens = usageObj?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0
            val completionTokens = usageObj?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0

            val nativeToolCalls = mutableListOf<AgentToolCallRequest>()
            val toolCallsElement: JsonElement? = messageObj["tool_calls"]
            if (toolCallsElement is JsonArray) {
                for (tcEl in toolCallsElement) {
                    val tcObj = tcEl.jsonObject
                    val id = tcObj["id"]?.jsonPrimitive?.content
                        ?: "call_${UUID.randomUUID().toString().take(8)}"
                    val fnObj = tcObj["function"]?.jsonObject ?: continue
                    val fnName = fnObj["name"]?.jsonPrimitive?.content ?: continue
                    val rawArgs = fnObj["arguments"]?.jsonPrimitive?.content ?: "{}"
                    val parsedArgs = runCatching {
                        json.parseToJsonElement(rawArgs).jsonObject
                    }.getOrDefault(JsonObject(emptyMap()))

                    nativeToolCalls.add(
                        AgentToolCallRequest(
                            id = id,
                            toolName = fnName,
                            arguments = parsedArgs
                        )
                    )
                }
            }

            if (nativeToolCalls.isNotEmpty()) {
                Result.success(
                    AgentTurnResponse(
                        providerId = providerId,
                        modelId = model.modelId,
                        assistantText = contentText,
                        toolCalls = nativeToolCalls,
                        promptTokensUsed = promptTokens,
                        completionTokensUsed = completionTokens,
                        finishReason = finishReason,
                        usedJsonFallbackParser = false
                    )
                )
            } else {
                // Attempt validated JSON tool-call fallback parsing if content contains json_tool_call
                val fallback = JsonToolCallFallbackParser.extractAndValidateToolCalls(contentText, allowedTools)
                Result.success(
                    AgentTurnResponse(
                        providerId = providerId,
                        modelId = model.modelId,
                        assistantText = fallback.cleanedAssistantText,
                        toolCalls = fallback.validToolCalls,
                        promptTokensUsed = promptTokens,
                        completionTokensUsed = completionTokens,
                        finishReason = finishReason,
                        usedJsonFallbackParser = fallback.validToolCalls.isNotEmpty()
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(
                AIProviderException(
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.BAD_REQUEST,
                        rawMessage = "Failed parsing response from $displayName: ${e.message}",
                        isRetryableOnSameProvider = false
                    ),
                    e
                )
            )
        }
    }

    companion object {
        fun classifyHttpError(
            statusCode: Int,
            rawBody: String,
            retryAfterSeconds: Long?
        ): ProviderExceptionInfo {
            val lower = rawBody.lowercase()
            return when {
                statusCode == 401 || lower.contains("invalid api key") || lower.contains("unauthorized") || lower.contains("incorrect api key") -> {
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.INVALID_API_KEY,
                        httpStatusCode = statusCode,
                        rawMessage = "Invalid or unauthorized API key (HTTP $statusCode).",
                        isRetryableOnSameProvider = false
                    )
                }
                statusCode == 402 || lower.contains("insufficient_quota") || lower.contains("credits") || lower.contains("quota exceeded") || lower.contains("billing") -> {
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.QUOTA_EXHAUSTED,
                        httpStatusCode = statusCode,
                        rawMessage = "Provider quota or free credits exhausted (HTTP $statusCode).",
                        isRetryableOnSameProvider = false
                    )
                }
                statusCode == 429 || lower.contains("rate limit") || lower.contains("too many requests") -> {
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.RATE_LIMIT_EXCEEDED,
                        httpStatusCode = statusCode,
                        retryAfterSeconds = retryAfterSeconds ?: 5L,
                        rawMessage = "Rate limit reached (HTTP 429). Retry after ${retryAfterSeconds ?: 5L}s.",
                        isRetryableOnSameProvider = true
                    )
                }
                lower.contains("context_length_exceeded") || lower.contains("maximum context length") || lower.contains("token limit") -> {
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.CONTEXT_LENGTH_EXCEEDED,
                        httpStatusCode = statusCode,
                        rawMessage = "Context length exceeded for model (HTTP $statusCode).",
                        isRetryableOnSameProvider = false
                    )
                }
                lower.contains("tool") && (lower.contains("not supported") || lower.contains("unsupported")) -> {
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.TOOL_CALLING_UNSUPPORTED,
                        httpStatusCode = statusCode,
                        rawMessage = "Selected model does not support native tool calling (HTTP $statusCode).",
                        isRetryableOnSameProvider = false
                    )
                }
                statusCode in 500..599 -> {
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.SERVER_UNAVAILABLE,
                        httpStatusCode = statusCode,
                        retryAfterSeconds = retryAfterSeconds ?: 3L,
                        rawMessage = "Provider server error (HTTP $statusCode).",
                        isRetryableOnSameProvider = true
                    )
                }
                statusCode == 403 -> {
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.REQUEST_REJECTED,
                        httpStatusCode = statusCode,
                        rawMessage = "Request rejected by provider policy or permissions (HTTP 403).",
                        isRetryableOnSameProvider = false
                    )
                }
                else -> {
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.BAD_REQUEST,
                        httpStatusCode = statusCode,
                        rawMessage = "Provider returned HTTP $statusCode: ${rawBody.take(240)}",
                        isRetryableOnSameProvider = false
                    )
                }
            }
        }
    }
}
