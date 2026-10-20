package com.agentforge.mobile.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.util.UUID

/**
 * Official Google Gemini REST API provider (`https://generativelanguage.googleapis.com/v1beta`).
 *
 * Supports:
 * - Dynamic model discovery via `GET /v1beta/models?key=...`
 * - Native `functionDeclarations` & `functionCall` / `functionResponse` parts
 * - Free-tier AI Studio API key usage (Gemini 2.5 Flash / Gemini 2.5 Pro free quota tiers)
 */
class GeminiProvider(
    private val httpClient: OkHttpClient,
    override val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta"
) : AIProvider {

    override val providerId: String = "gemini"
    override val displayName: String = "Google Gemini API"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun cancelInFlightRequests() {
        for (call in httpClient.dispatcher.queuedCalls()) {
            if (call.request().tag(String::class.java) == providerId) call.cancel()
        }
        for (call in httpClient.dispatcher.runningCalls()) {
            if (call.request().tag(String::class.java) == providerId) call.cancel()
        }
    }

    override suspend fun verifyConnectionAndKey(apiKey: String): Result<ProviderHealthStatus> =
        withContext(Dispatchers.IO) {
            val modelsRes = discoverModels(apiKey)
            modelsRes.fold(
                onSuccess = { models ->
                    val freeCount = models.count { it.isFreeTier }
                    Result.success(
                        ProviderHealthStatus(
                            providerId = providerId,
                            isReachable = true,
                            isKeyValid = true,
                            statusMessage = "Connected (${models.size} Gemini models available)",
                            quotaSummary = "Google AI Studio Free Tier (RPM/RPD enforced per model)",
                            discoveredFreeModelsCount = freeCount,
                            discoveredTotalModelsCount = models.size
                        )
                    )
                },
                onFailure = { Result.failure(it) }
            )
        }

    override suspend fun discoverModels(apiKey: String): Result<List<AIModelDescriptor>> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    AIProviderException(
                        ProviderExceptionInfo(
                            errorType = ProviderErrorType.INVALID_API_KEY,
                            rawMessage = "Gemini API key is empty.",
                            isRetryableOnSameProvider = false
                        )
                    )
                )
            }
            val url = "${baseUrl.trimEnd('/')}/models?key=${apiKey.trim()}"
            val req = Request.Builder()
                .url(url)
                .tag(String::class.java, providerId)
                .get()
                .build()

            try {
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            AIProviderException(
                                OpenAiCompatibleBaseProvider.classifyHttpError(resp.code, body, null)
                            )
                        )
                    }
                    val root = json.parseToJsonElement(body).jsonObject
                    val modelsArr = root["models"]?.jsonArray ?: return@withContext Result.success(defaultCatalog())
                    val now = System.currentTimeMillis()
                    val list = mutableListOf<AIModelDescriptor>()

                    for (el in modelsArr) {
                        val obj = el.jsonObject
                        val rawName = obj["name"]?.jsonPrimitive?.content ?: continue
                        val modelId = rawName.removePrefix("models/")
                        val methods = obj["supportedGenerationMethods"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.content }
                            ?.toSet() ?: emptySet()

                        if ("generateContent" !in methods) continue

                        val displayName = obj["displayName"]?.jsonPrimitive?.content ?: modelId
                        val inputLimit = obj["inputTokenLimit"]?.jsonPrimitive?.intOrNull ?: 1_048_576
                        val outputLimit = obj["outputTokenLimit"]?.jsonPrimitive?.intOrNull ?: 8192

                        val isFreeEligible = modelId.contains("flash", ignoreCase = true) ||
                            modelId.contains("pro", ignoreCase = true)

                        list.add(
                            AIModelDescriptor(
                                providerId = providerId,
                                modelId = modelId,
                                displayName = displayName,
                                isFreeTier = isFreeEligible,
                                supportsNativeToolCalling = true,
                                contextWindowTokens = inputLimit,
                                maxOutputTokens = outputLimit,
                                lastUpdatedEpochMs = now
                            )
                        )
                    }
                    Result.success(if (list.isEmpty()) defaultCatalog() else list)
                }
            } catch (e: Exception) {
                Result.failure(
                    AIProviderException(
                        ProviderExceptionInfo(
                            errorType = ProviderErrorType.NETWORK_DISCONNECTED,
                            rawMessage = "Error connecting to Gemini API: ${e.message}",
                            isRetryableOnSameProvider = true
                        ),
                        e
                    )
                )
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
                        rawMessage = "Gemini API key is empty.",
                        isRetryableOnSameProvider = false
                    )
                )
            )
        }

        val cleanModelId = model.modelId.removePrefix("models/")
        val url = "${baseUrl.trimEnd('/')}/models/$cleanModelId:generateContent?key=${apiKey.trim()}"

        val systemMessage = messages.firstOrNull { it.role == "system" }?.content
        val nonSystemMessages = messages.filter { it.role != "system" }

        val requestPayload = buildJsonObject {
            if (!systemMessage.isNullOrBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", systemMessage) })
                    })
                })
            }

            put("contents", buildJsonArray {
                for (msg in nonSystemMessages) {
                    val geminiRole = if (msg.role == "assistant") "model" else "user"
                    add(buildJsonObject {
                        put("role", geminiRole)
                        put("parts", buildJsonArray {
                            if (msg.role == "tool" && msg.toolName != null) {
                                add(buildJsonObject {
                                    put("functionResponse", buildJsonObject {
                                        put("name", msg.toolName)
                                        put("response", buildJsonObject {
                                            put("result", msg.content)
                                        })
                                    })
                                })
                            } else {
                                if (msg.content.isNotEmpty()) {
                                    add(buildJsonObject { put("text", msg.content) })
                                }
                                for (tc in msg.toolCalls) {
                                    add(buildJsonObject {
                                        put("functionCall", buildJsonObject {
                                            put("name", tc.toolName)
                                            put("args", tc.arguments)
                                        })
                                    })
                                }
                            }
                        })
                    })
                }
            })

            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            for (tool in tools) {
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", buildJsonObject {
                                        put("type", "OBJECT")
                                        put("properties", buildJsonObject {
                                            for ((paramName, prop) in tool.parameters) {
                                                put(paramName, buildJsonObject {
                                                    put("type", prop.type.uppercase())
                                                    put("description", prop.description)
                                                })
                                            }
                                        })
                                        put("required", JsonArray(tool.requiredParameters.map { JsonPrimitive(it) }))
                                    })
                                })
                            }
                        })
                    })
                })
            }

            put("generationConfig", buildJsonObject {
                put("temperature", temperature)
            })
        }

        val req = Request.Builder()
            .url(url)
            .tag(String::class.java, providerId)
            .header("Content-Type", "application/json")
            .post(requestPayload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                val rawBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val retryAfter = resp.header("Retry-After")?.toLongOrNull()
                    return@withContext Result.failure(
                        AIProviderException(
                            OpenAiCompatibleBaseProvider.classifyHttpError(resp.code, rawBody, retryAfter)
                        )
                    )
                }

                val root = json.parseToJsonElement(rawBody).jsonObject
                val candidates = root["candidates"]?.jsonArray
                if (candidates.isNullOrEmpty()) {
                    return@withContext Result.failure(
                        AIProviderException(
                            ProviderExceptionInfo(
                                errorType = ProviderErrorType.REQUEST_REJECTED,
                                rawMessage = "Gemini returned no candidates: $rawBody",
                                isRetryableOnSameProvider = false
                            )
                        )
                    )
                }

                val firstCandidate = candidates[0].jsonObject
                val contentObj = firstCandidate["content"]?.jsonObject
                val parts = contentObj?.get("parts")?.jsonArray ?: JsonArray(emptyList())

                val textBuilder = StringBuilder()
                val toolCalls = mutableListOf<AgentToolCallRequest>()

                for (part in parts) {
                    val pObj = part.jsonObject
                    pObj["text"]?.jsonPrimitive?.content?.let { textBuilder.append(it) }
                    val fnCall = pObj["functionCall"]?.jsonObject
                    if (fnCall != null) {
                        val fnName = fnCall["name"]?.jsonPrimitive?.content ?: continue
                        val args = fnCall["args"] as? JsonObject ?: JsonObject(emptyMap())
                        toolCalls.add(
                            AgentToolCallRequest(
                                id = "gemini_call_${UUID.randomUUID().toString().take(8)}",
                                toolName = fnName,
                                arguments = args
                            )
                        )
                    }
                }

                val usage = root["usageMetadata"]?.jsonObject
                val promptTokens = usage?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0
                val completionTokens = usage?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0

                Result.success(
                    AgentTurnResponse(
                        providerId = providerId,
                        modelId = cleanModelId,
                        assistantText = textBuilder.toString(),
                        toolCalls = toolCalls,
                        promptTokensUsed = promptTokens,
                        completionTokensUsed = completionTokens,
                        finishReason = firstCandidate["finishReason"]?.jsonPrimitive?.content ?: "STOP"
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(
                AIProviderException(
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.NETWORK_DISCONNECTED,
                        rawMessage = "Gemini request error: ${e.message}",
                        isRetryableOnSameProvider = true
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
        val res = generateTurn(apiKey, model, messages, tools, temperature)
        res.fold(
            onSuccess = {
                if (it.assistantText.isNotEmpty()) emit(StreamChunk.TextDelta(it.assistantText))
                emit(StreamChunk.Completed(it))
            },
            onFailure = { err ->
                emit(
                    StreamChunk.Error(
                        err as? AIProviderException ?: AIProviderException(
                            ProviderExceptionInfo(
                                errorType = ProviderErrorType.BAD_REQUEST,
                                rawMessage = err.message ?: "Gemini error",
                                isRetryableOnSameProvider = false
                            )
                        )
                    )
                )
            }
        )
    }

    companion object {
        fun defaultCatalog(): List<AIModelDescriptor> = listOf(
            AIModelDescriptor(
                providerId = "gemini",
                modelId = "gemini-2.5-flash",
                displayName = "Gemini 2.5 Flash (Free Tier Eligible)",
                isFreeTier = true,
                supportsNativeToolCalling = true,
                contextWindowTokens = 1_048_576
            ),
            AIModelDescriptor(
                providerId = "gemini",
                modelId = "gemini-2.5-pro",
                displayName = "Gemini 2.5 Pro (Free Tier Rate-Limited)",
                isFreeTier = true,
                supportsNativeToolCalling = true,
                contextWindowTokens = 1_048_576
            )
        )
    }
}
