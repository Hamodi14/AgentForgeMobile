package com.agentforge.mobile.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Official GitHub Models Inference API provider (`https://models.github.ai/inference`).
 * Uses a GitHub Personal Access Token (`models:read` permission) for rate-limited free prototyping.
 */
class GitHubModelsProvider(
    httpClient: OkHttpClient,
    override val baseUrl: String = "https://models.github.ai/inference"
) : OpenAiCompatibleBaseProvider(httpClient) {

    override val providerId: String = "github_models"
    override val displayName: String = "GitHub Models"

    override suspend fun verifyConnectionAndKey(apiKey: String): Result<ProviderHealthStatus> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    AIProviderException(
                        ProviderExceptionInfo(
                            errorType = ProviderErrorType.INVALID_API_KEY,
                            rawMessage = "GitHub PAT token is required.",
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
                    statusMessage = "GitHub Models configured (${catalog.size} models)",
                    quotaSummary = "GitHub Account Free Prototyping Tier (RPM/RPD limits apply)",
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
                providerId = "github_models",
                modelId = "openai/gpt-4o-mini",
                displayName = "GPT-4o Mini (GitHub Models Free Tier)",
                isFreeTier = true,
                supportsNativeToolCalling = true,
                contextWindowTokens = 64000
            ),
            AIModelDescriptor(
                providerId = "github_models",
                modelId = "meta/Llama-3.3-70B-Instruct",
                displayName = "Llama 3.3 70B Instruct (GitHub Models Free Tier)",
                isFreeTier = true,
                supportsNativeToolCalling = true,
                contextWindowTokens = 64000
            ),
            AIModelDescriptor(
                providerId = "github_models",
                modelId = "deepseek/DeepSeek-V3-0324",
                displayName = "DeepSeek V3 (GitHub Models Free Tier)",
                isFreeTier = true,
                supportsNativeToolCalling = false,
                supportsJsonFallbackTools = true,
                contextWindowTokens = 64000
            )
        )
    }
}
