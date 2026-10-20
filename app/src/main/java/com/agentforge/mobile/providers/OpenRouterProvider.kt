package com.agentforge.mobile.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Official OpenRouter API integration (`https://openrouter.ai/api/v1`).
 *
 * Dynamically queries `GET /api/v1/models` and identifies genuinely free models by inspecting:
 * 1. `pricing.prompt == "0"` and `pricing.completion == "0"`
 * 2. Model IDs ending in `:free` or `openrouter/free`
 * 3. `supported_parameters` containing `"tools"` for native tool calling capability.
 */
class OpenRouterProvider(
    httpClient: OkHttpClient,
    override val baseUrl: String = "https://openrouter.ai/api/v1"
) : OpenAiCompatibleBaseProvider(httpClient) {

    override val providerId: String = "openrouter"
    override val displayName: String = "OpenRouter"

    override fun additionalHeaders(apiKey: String): Map<String, String> = mapOf(
        "HTTP-Referer" to "https://agentforge.mobile.app",
        "X-Title" to "AgentForge Mobile"
    )

    override suspend fun verifyConnectionAndKey(apiKey: String): Result<ProviderHealthStatus> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    AIProviderException(
                        ProviderExceptionInfo(
                            errorType = ProviderErrorType.INVALID_API_KEY,
                            rawMessage = "OpenRouter API key is required.",
                            isRetryableOnSameProvider = false
                        )
                    )
                )
            }
            val keyReq = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/auth/key")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()

            try {
                httpClient.newCall(keyReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            AIProviderException(classifyHttpError(resp.code, body, null))
                        )
                    }
                    val dataObj = runCatching {
                        json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                    }.getOrNull()
                    val usage = dataObj?.get("usage")?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val limit = dataObj?.get("limit")?.jsonPrimitive?.doubleOrNull
                    val isFreeTierKey = dataObj?.get("is_free_tier")?.jsonPrimitive?.content == "true"

                    val modelsRes = discoverModels(apiKey)
                    val models = modelsRes.getOrDefault(defaultCatalog())
                    val freeCount = models.count { it.isFreeTier }

                    Result.success(
                        ProviderHealthStatus(
                            providerId = providerId,
                            isReachable = true,
                            isKeyValid = true,
                            statusMessage = "Connected ($freeCount free models discovered)",
                            quotaSummary = if (limit != null) "Usage: $$usage / Limit: $$limit" else "Free tier active ($isFreeTierKey)",
                            discoveredFreeModelsCount = freeCount,
                            discoveredTotalModelsCount = models.size
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(
                    AIProviderException(
                        ProviderExceptionInfo(
                            errorType = ProviderErrorType.NETWORK_DISCONNECTED,
                            rawMessage = "Failed to verify OpenRouter key: ${e.message}",
                            isRetryableOnSameProvider = true
                        ),
                        e
                    )
                )
            }
        }

    override suspend fun discoverModels(apiKey: String): Result<List<AIModelDescriptor>> =
        withContext(Dispatchers.IO) {
            val reqBuilder = Request.Builder()
                .url(modelsUrl())
                .get()
            if (apiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
            }
            try {
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(
                            AIProviderException(classifyHttpError(resp.code, body, null))
                        )
                    }
                    val root = json.parseToJsonElement(body).jsonObject
                    val dataArr = root["data"]?.jsonArray ?: return@withContext Result.success(defaultCatalog())
                    val now = System.currentTimeMillis()
                    val discovered = mutableListOf<AIModelDescriptor>()

                    // Always include openrouter/free smart router entry first
                    discovered.add(
                        AIModelDescriptor(
                            providerId = providerId,
                            modelId = "openrouter/free",
                            displayName = "OpenRouter Free Smart Router (Auto Free)",
                            isFreeTier = true,
                            supportsNativeToolCalling = true,
                            contextWindowTokens = 128000,
                            lastUpdatedEpochMs = now
                        )
                    )

                    for (el in dataArr) {
                        val obj = el.jsonObject
                        val id = obj["id"]?.jsonPrimitive?.content ?: continue
                        val name = obj["name"]?.jsonPrimitive?.content ?: id
                        val ctx = obj["context_length"]?.jsonPrimitive?.intOrNull ?: 32768
                        val pricing = obj["pricing"]?.jsonObject
                        val promptPrice = pricing?.get("prompt")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                        val completionPrice = pricing?.get("completion")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0

                        val isFree = id.endsWith(":free") || id == "openrouter/free" ||
                            (promptPrice == 0.0 && completionPrice == 0.0)

                        val supportedParams = obj["supported_parameters"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.content }
                            ?.toSet() ?: emptySet()

                        val supportsTools = "tools" in supportedParams || "tool_choice" in supportedParams || id.endsWith(":free")

                        discovered.add(
                            AIModelDescriptor(
                                providerId = providerId,
                                modelId = id,
                                displayName = name,
                                isFreeTier = isFree,
                                supportsNativeToolCalling = supportsTools,
                                contextWindowTokens = ctx,
                                promptPricePerMillionUsd = promptPrice * 1_000_000.0,
                                completionPricePerMillionUsd = completionPrice * 1_000_000.0,
                                lastUpdatedEpochMs = now
                            )
                        )
                    }
                    Result.success(discovered)
                }
            } catch (e: Exception) {
                Result.failure(
                    AIProviderException(
                        ProviderExceptionInfo(
                            errorType = ProviderErrorType.NETWORK_DISCONNECTED,
                            rawMessage = "Could not fetch OpenRouter catalog: ${e.message}",
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
                providerId = "openrouter",
                modelId = "openrouter/free",
                displayName = "OpenRouter Free Router (Auto-Selects Free Tool Model)",
                isFreeTier = true,
                supportsNativeToolCalling = true,
                contextWindowTokens = 128000
            ),
            AIModelDescriptor(
                providerId = "openrouter",
                modelId = "qwen/qwen-2.5-coder-32b-instruct:free",
                displayName = "Qwen 2.5 Coder 32B Instruct (Free)",
                isFreeTier = true,
                supportsNativeToolCalling = true,
                contextWindowTokens = 32768
            ),
            AIModelDescriptor(
                providerId = "openrouter",
                modelId = "meta-llama/llama-3.3-70b-instruct:free",
                displayName = "Llama 3.3 70B Instruct (Free)",
                isFreeTier = true,
                supportsNativeToolCalling = true,
                contextWindowTokens = 128000
            ),
            AIModelDescriptor(
                providerId = "openrouter",
                modelId = "deepseek/deepseek-chat:free",
                displayName = "DeepSeek V3 Chat (Free)",
                isFreeTier = true,
                supportsNativeToolCalling = false,
                supportsJsonFallbackTools = true,
                contextWindowTokens = 64000
            )
        )
    }
}
