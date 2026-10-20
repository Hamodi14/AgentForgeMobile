package com.agentforge.mobile.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Official Groq Cloud OpenAI-compatible API provider (`https://api.groq.com/openai/v1`).
 * Supports free developer tier rate-limited access with high-speed LPU inference and tool calling.
 */
class GroqProvider(
    httpClient: OkHttpClient,
    override val baseUrl: String = "https://api.groq.com/openai/v1"
) : OpenAiCompatibleBaseProvider(httpClient) {

    override val providerId: String = "groq"
    override val displayName: String = "Groq Cloud API"

    override suspend fun verifyConnectionAndKey(apiKey: String): Result<ProviderHealthStatus> =
        withContext(Dispatchers.IO) {
            val modelsRes = discoverModels(apiKey)
            modelsRes.fold(
                onSuccess = { models ->
                    Result.success(
                        ProviderHealthStatus(
                            providerId = providerId,
                            isReachable = true,
                            isKeyValid = true,
                            statusMessage = "Connected (${models.size} Groq models active)",
                            quotaSummary = "Groq Free Developer Tier (RPM/TPM rate limits apply)",
                            discoveredFreeModelsCount = models.count { it.isFreeTier },
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
                            rawMessage = "Groq API key is empty.",
                            isRetryableOnSameProvider = false
                        )
                    )
                )
            }
            val req = Request.Builder()
                .url(modelsUrl())
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()

            try {
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            AIProviderException(classifyHttpError(resp.code, body, null))
                        )
                    }
                    val root = json.parseToJsonElement(body).jsonObject
                    val data = root["data"]?.jsonArray ?: return@withContext Result.success(defaultCatalog())
                    val now = System.currentTimeMillis()
                    val models = mutableListOf<AIModelDescriptor>()

                    for (el in data) {
                        val obj = el.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content ?: continue
                        if (id.contains("whisper", ignoreCase = true) || id.contains("tts", ignoreCase = true)) {
                            continue
                        }
                        val ctx = obj["context_window"]?.jsonPrimitive?.intOrNull ?: 32768
                        models.add(
                            AIModelDescriptor(
                                providerId = providerId,
                                modelId = id,
                                displayName = "Groq: $id",
                                isFreeTier = true,
                                supportsNativeToolCalling = true,
                                contextWindowTokens = ctx,
                                lastUpdatedEpochMs = now
                            )
                        )
                    }
                    Result.success(if (models.isEmpty()) defaultCatalog() else models)
                }
            } catch (e: Exception) {
                Result.failure(
                    AIProviderException(
                        ProviderExceptionInfo(
                            errorType = ProviderErrorType.NETWORK_DISCONNECTED,
                            rawMessage = "Groq connection error: ${e.message}",
                            isRetryableOnSameProvider = true
                        ),
                        e
                    )
                )
            }
        }

    companion object {
        fun defaultCatalog(): List<AIModelDescriptor> = listOf(
            AIModelDescriptor(
                providerId = "groq",
                modelId = "llama-3.3-70b-versatile",
                displayName = "Llama 3.3 70B Versatile (Groq Free Tier)",
                isFreeTier = true,
                supportsNativeToolCalling = true,
                contextWindowTokens = 128000
            ),
            AIModelDescriptor(
                providerId = "groq",
                modelId = "qwen-qwq-32b",
                displayName = "Qwen QwQ 32B Reasoning (Groq Free Tier)",
                isFreeTier = true,
                supportsNativeToolCalling = true,
                contextWindowTokens = 128000
            )
        )
    }
}
