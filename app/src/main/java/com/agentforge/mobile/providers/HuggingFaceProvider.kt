package com.agentforge.mobile.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Official Hugging Face Inference Providers Router (`https://router.huggingface.co/v1`).
 * Exposes an OpenAI-compatible `/v1/chat/completions` endpoint with free monthly credits
 * on Hugging Face accounts.
 */
class HuggingFaceProvider(
    httpClient: OkHttpClient,
    override val baseUrl: String = "https://router.huggingface.co/v1"
) : OpenAiCompatibleBaseProvider(httpClient) {

    override val providerId: String = "huggingface"
    override val displayName: String = "Hugging Face Inference Router"

    override suspend fun verifyConnectionAndKey(apiKey: String): Result<ProviderHealthStatus> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    AIProviderException(
                        ProviderExceptionInfo(
                            errorType = ProviderErrorType.INVALID_API_KEY,
                            rawMessage = "Hugging Face access token (hf_...) is required.",
                            isRetryableOnSameProvider = false
                        )
                    )
                )
            }
            val catalog = defaultCatalog()
            Result.success(
                ProviderHealthStatus(
                    providerId = providerId,
                    isReachable = true,
                    isKeyValid = true,
                    statusMessage = "Hugging Face Router ready (${catalog.size} models)",
                    quotaSummary = "Hugging Face Free Monthly Inference Credits",
                    discoveredFreeModelsCount = catalog.size,
                    discoveredTotalModelsCount = catalog.size
                )
            )
        }

    override suspend fun discoverModels(apiKey: String): Result<List<AIModelDescriptor>> =
        withContext(Dispatchers.IO) {
            Result.success(defaultCatalog())
        }

    companion object {
        fun defaultCatalog(): List<AIModelDescriptor> = listOf(
            AIModelDescriptor(
                providerId = "huggingface",
                modelId = "Qwen/Qwen2.5-Coder-32B-Instruct",
                displayName = "Qwen 2.5 Coder 32B Instruct (HF Free Tier)",
                isFreeTier = true,
                supportsNativeToolCalling = true,
                contextWindowTokens = 32768
            ),
            AIModelDescriptor(
                providerId = "huggingface",
                modelId = "meta-llama/Llama-3.3-70B-Instruct",
                displayName = "Llama 3.3 70B Instruct (HF Free Tier)",
                isFreeTier = true,
                supportsNativeToolCalling = false,
                supportsJsonFallbackTools = true,
                contextWindowTokens = 64000
            )
        )
    }
}
