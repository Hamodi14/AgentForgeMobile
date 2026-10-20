package com.agentforge.mobile.routing

import com.agentforge.mobile.providers.AIModelDescriptor
import com.agentforge.mobile.providers.AIProvider
import com.agentforge.mobile.providers.AIProviderException
import com.agentforge.mobile.providers.AgentMessage
import com.agentforge.mobile.providers.AgentTurnResponse
import com.agentforge.mobile.providers.ProviderErrorType
import com.agentforge.mobile.providers.ProviderExceptionInfo
import com.agentforge.mobile.providers.ToolDefinitionSchema
import com.agentforge.mobile.security.SecretStore
import com.agentforge.mobile.storage.ProviderConfigEntity
import com.agentforge.mobile.storage.RoutingMode
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable

@Serializable
data class FailoverEvent(
    val fromProviderId: String,
    val fromModelId: String,
    val toProviderId: String,
    val toModelId: String,
    val errorType: ProviderErrorType,
    val reason: String,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

data class RoutedTurnOutcome(
    val response: AgentTurnResponse,
    val failoverEvents: List<FailoverEvent>
)

/**
 * Smart Provider & Model Router.
 *
 * Responsibilities:
 * 1. Enforces [RoutingMode.FREE_ONLY] by default — strictly blocks any model where `isFreeTier == false`
 *    or any provider with paid pricing, even when all free providers are exhausted.
 * 2. Supports [RoutingMode.MANUAL] (user picks exact provider + model) and [RoutingMode.CUSTOM_PRIORITY]
 *    (user orders providers and preferred coding/analysis models).
 * 3. Handles transient rate-limits (`RATE_LIMIT_EXCEEDED`) using the provider's `Retry-After` header
 *    up to `maxRetriesPerProvider`, and immediately fails over to the next eligible provider on
 *    `QUOTA_EXHAUSTED`, `SERVER_UNAVAILABLE`, or `TOOL_CALLING_UNSUPPORTED`.
 * 4. Never retries `INVALID_API_KEY` endlessly — marks the provider unavailable and surfaces a clear error.
 * 5. Compresses context seamlessly when handing off an in-progress task to a new provider/model.
 */
class SmartModelRouter(
    private val providers: Map<String, AIProvider>,
    private val secretStore: SecretStore
) {
    private val modelCatalogCache = mutableMapOf<String, List<AIModelDescriptor>>()
    private val exhaustedModelsUntilEpochMs = mutableMapOf<String, Long>()
    private val invalidKeyProviders = mutableSetOf<String>()

    fun registerCatalog(providerId: String, models: List<AIModelDescriptor>) {
        modelCatalogCache[providerId] = models
    }

    fun getCachedCatalog(providerId: String): List<AIModelDescriptor> {
        return modelCatalogCache[providerId].orEmpty()
    }

    fun clearInvalidKeyFlag(providerId: String) {
        invalidKeyProviders.remove(providerId)
    }

    /**
     * Selects the ordered list of candidate `(AIProvider, AIModelDescriptor)` pairs eligible
     * for the current task according to [routingMode], [providerConfigs], and [freeOnlyEnforced].
     */
    fun resolveCandidateChain(
        routingMode: RoutingMode,
        providerConfigs: List<ProviderConfigEntity>,
        manualProviderId: String? = null,
        manualModelId: String? = null,
        requireFreeOnly: Boolean = (routingMode == RoutingMode.FREE_ONLY)
    ): List<Pair<AIProvider, AIModelDescriptor>> {
        val now = System.currentTimeMillis()
        val enabledConfigs = providerConfigs
            .filter { it.isEnabled && it.providerId !in invalidKeyProviders && secretStore.hasApiKey(it.providerId) }
            .sortedBy { it.priorityOrder }

        val candidates = mutableListOf<Pair<AIProvider, AIModelDescriptor>>()

        if (routingMode == RoutingMode.MANUAL && manualProviderId != null && manualModelId != null) {
            val provider = providers[manualProviderId]
            if (provider != null && secretStore.hasApiKey(manualProviderId)) {
                val catalog = modelCatalogCache[manualProviderId].orEmpty()
                val model = catalog.find { it.modelId == manualModelId }
                    ?: AIModelDescriptor(
                        providerId = manualProviderId,
                        modelId = manualModelId,
                        displayName = manualModelId,
                        isFreeTier = !requireFreeOnly,
                        supportsNativeToolCalling = true
                    )
                if (!requireFreeOnly || model.isFreeTier) {
                    candidates.add(provider to model)
                }
            }
            return candidates
        }

        for (config in enabledConfigs) {
            val provider = providers[config.providerId] ?: continue
            val catalog = modelCatalogCache[config.providerId].orEmpty()

            // Put preferred coding model first if present in catalog, then remaining models
            val sortedModels = catalog.sortedWith(
                compareByDescending<AIModelDescriptor> { it.modelId == config.preferredCodingModelId }
                    .thenByDescending { it.modelId == config.preferredAnalysisModelId }
                    .thenByDescending { it.supportsNativeToolCalling }
            )

            for (model in sortedModels) {
                if (requireFreeOnly && (!model.isFreeTier || model.promptPricePerMillionUsd > 0.0 || model.completionPricePerMillionUsd > 0.0)) {
                    continue
                }
                val cooldownKey = "${provider.providerId}:${model.modelId}"
                val cooldownUntil = exhaustedModelsUntilEpochMs[cooldownKey] ?: 0L
                if (cooldownUntil > now) {
                    continue
                }
                candidates.add(provider to model)
            }
        }

        return candidates
    }

    /**
     * Executes a model turn across the candidate chain with automatic retry/backoff and
     * state-preserving provider failover.
     */
    suspend fun executeRoutedTurn(
        routingMode: RoutingMode,
        providerConfigs: List<ProviderConfigEntity>,
        messages: List<AgentMessage>,
        tools: List<ToolDefinitionSchema>,
        taskHandOffSummary: String,
        manualProviderId: String? = null,
        manualModelId: String? = null,
        retryWaitSeconds: Int = 2,
        maxRetriesPerProvider: Int = 1
    ): Result<RoutedTurnOutcome> {
        val enforceFreeOnly = (routingMode == RoutingMode.FREE_ONLY)
        val candidateChain = resolveCandidateChain(
            routingMode = routingMode,
            providerConfigs = providerConfigs,
            manualProviderId = manualProviderId,
            manualModelId = manualModelId,
            requireFreeOnly = enforceFreeOnly
        )

        if (candidateChain.isEmpty()) {
            val msg = if (enforceFreeOnly) {
                "لا يوجد أي نموذج مجاني متاح حاليًا (جميع الخيارات المجانية استُنفدت أو لم يتم إدخال مفتاح API صالح). تم حفظ تقدم المهمة بأمان لاستئنافها لاحقًا دون أي استهلاك مدفوع."
            } else {
                "No enabled AI providers with valid API keys are available. Please configure an API key in AI Providers settings."
            }
            return Result.failure(
                AIProviderException(
                    ProviderExceptionInfo(
                        errorType = ProviderErrorType.QUOTA_EXHAUSTED,
                        rawMessage = msg,
                        isRetryableOnSameProvider = false
                    )
                )
            )
        }

        val failoverEvents = mutableListOf<FailoverEvent>()
        var lastError: AIProviderException? = null

        for ((index, pair) in candidateChain.withIndex()) {
            val (provider, model) = pair
            val apiKey = secretStore.getApiKey(provider.providerId).orEmpty()

            // Double-guard: never send to a paid model in FREE_ONLY mode
            if (enforceFreeOnly && !model.isFreeTier) {
                continue
            }

            // Prepare context adapted to the target model's context window & handoff state
            val adaptedMessages = if (index > 0 && taskHandOffSummary.isNotBlank()) {
                buildHandOffMessages(messages, taskHandOffSummary, model.contextWindowTokens)
            } else {
                trimMessagesToContextWindow(messages, model.contextWindowTokens)
            }

            var attempt = 0
            while (attempt <= maxRetriesPerProvider) {
                val res = provider.generateTurn(
                    apiKey = apiKey,
                    model = model,
                    messages = adaptedMessages,
                    tools = tools
                )

                if (res.isSuccess) {
                    return Result.success(
                        RoutedTurnOutcome(
                            response = res.getOrThrow(),
                            failoverEvents = failoverEvents
                        )
                    )
                }

                val ex = res.exceptionOrNull() as? AIProviderException
                    ?: AIProviderException(
                        ProviderExceptionInfo(
                            errorType = ProviderErrorType.BAD_REQUEST,
                            rawMessage = res.exceptionOrNull()?.message ?: "Unknown provider error",
                            isRetryableOnSameProvider = false
                        )
                    )
                lastError = ex

                when (ex.info.errorType) {
                    ProviderErrorType.INVALID_API_KEY -> {
                        // Do NOT retry invalid API keys endlessly
                        invalidKeyProviders.add(provider.providerId)
                        break
                    }
                    ProviderErrorType.QUOTA_EXHAUSTED -> {
                        // Put model on cooldown for 15 minutes and switch immediately
                        exhaustedModelsUntilEpochMs["${provider.providerId}:${model.modelId}"] =
                            System.currentTimeMillis() + 15 * 60 * 1000L
                        break
                    }
                    ProviderErrorType.RATE_LIMIT_EXCEEDED, ProviderErrorType.SERVER_UNAVAILABLE -> {
                        if (attempt < maxRetriesPerProvider) {
                            val waitSec = (ex.info.retryAfterSeconds ?: retryWaitSeconds.toLong()).coerceIn(1L, 15L)
                            delay(waitSec * 1000L)
                            attempt++
                            continue
                        } else {
                            exhaustedModelsUntilEpochMs["${provider.providerId}:${model.modelId}"] =
                                System.currentTimeMillis() + 60_000L
                            break
                        }
                    }
                    ProviderErrorType.NETWORK_DISCONNECTED -> {
                        // Network is down on device; stop immediately so orchestrator can checkpoint
                        return Result.failure(ex)
                    }
                    ProviderErrorType.TOOL_CALLING_UNSUPPORTED -> {
                        // Retry once with native tool calling disabled (using JSON fallback)
                        if (model.supportsNativeToolCalling) {
                            val fallbackModel = model.copy(supportsNativeToolCalling = false)
                            val fallbackRes = provider.generateTurn(
                                apiKey = apiKey,
                                model = fallbackModel,
                                messages = adaptedMessages,
                                tools = tools
                            )
                            if (fallbackRes.isSuccess) {
                                return Result.success(
                                    RoutedTurnOutcome(
                                        response = fallbackRes.getOrThrow(),
                                        failoverEvents = failoverEvents
                                    )
                                )
                            }
                        }
                        break
                    }
                    else -> break
                }
            }

            // Record failover event if there is a next candidate
            if (index + 1 < candidateChain.size && lastError != null) {
                val (nextProvider, nextModel) = candidateChain[index + 1]
                failoverEvents.add(
                    FailoverEvent(
                        fromProviderId = provider.providerId,
                        fromModelId = model.modelId,
                        toProviderId = nextProvider.providerId,
                        toModelId = nextModel.modelId,
                        errorType = lastError.info.errorType,
                        reason = lastError.info.rawMessage
                    )
                )
            }
        }

        return Result.failure(
            lastError ?: AIProviderException(
                ProviderExceptionInfo(
                    errorType = ProviderErrorType.QUOTA_EXHAUSTED,
                    rawMessage = "All candidate models failed or exhausted their free quota.",
                    isRetryableOnSameProvider = false
                )
            )
        )
    }

    /**
     * Builds a compact handoff conversation when switching providers mid-task so the new model
     * receives the original user goal, current plan/step, modified files, and recent tool outputs
     * without resending the entire raw history unnecessarily.
     */
    fun buildHandOffMessages(
        originalMessages: List<AgentMessage>,
        taskHandOffSummary: String,
        maxContextTokens: Int
    ): List<AgentMessage> {
        val systemMsg = originalMessages.firstOrNull { it.role == "system" }
        val tailMessages = originalMessages.filter { it.role != "system" }.takeLast(6)

        val handOffNotice = AgentMessage(
            role = "user",
            content = buildString {
                appendLine("[PROVIDER FAILOVER CONTEXT RESUME]")
                appendLine("You are resuming an in-progress coding task after the previous model reached its quota/rate limit.")
                appendLine("Continue from the last completed checkpoint without repeating already-finished file writes:")
                appendLine(taskHandOffSummary)
            }
        )

        val combined = buildList {
            if (systemMsg != null) add(systemMsg)
            add(handOffNotice)
            addAll(tailMessages)
        }
        return trimMessagesToContextWindow(combined, maxContextTokens)
    }

    private fun trimMessagesToContextWindow(
        messages: List<AgentMessage>,
        maxContextTokens: Int
    ): List<AgentMessage> {
        val charBudget = (maxContextTokens * 3).coerceAtLeast(12_000)
        var usedChars = 0
        val systemMsg = messages.firstOrNull { it.role == "system" }
        if (systemMsg != null) {
            usedChars += systemMsg.content.length
        }

        val keptNonSystem = mutableListOf<AgentMessage>()
        for (msg in messages.filter { it.role != "system" }.asReversed()) {
            if (usedChars + msg.content.length > charBudget && keptNonSystem.isNotEmpty()) {
                break
            }
            keptNonSystem.add(0, msg)
            usedChars += msg.content.length
        }

        return buildList {
            if (systemMsg != null) add(systemMsg)
            addAll(keptNonSystem)
        }
    }
}
