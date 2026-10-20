package com.agentforge.mobile

import android.app.Application
import com.agentforge.mobile.agent.AgentOrchestrator
import com.agentforge.mobile.execution.CommandExecutor
import com.agentforge.mobile.execution.EnvironmentInspector
import com.agentforge.mobile.project.BackupManager
import com.agentforge.mobile.project.SafProjectManager
import com.agentforge.mobile.providers.AIProvider
import com.agentforge.mobile.providers.GeminiProvider
import com.agentforge.mobile.providers.GitHubModelsProvider
import com.agentforge.mobile.providers.GroqProvider
import com.agentforge.mobile.providers.HuggingFaceProvider
import com.agentforge.mobile.providers.OpenRouterProvider
import com.agentforge.mobile.routing.SmartModelRouter
import com.agentforge.mobile.security.KeystoreSecretManager
import com.agentforge.mobile.storage.AgentForgeDatabase
import com.agentforge.mobile.storage.ProviderConfigEntity
import com.agentforge.mobile.storage.UserPreferencesRepository
import com.agentforge.mobile.tools.AgentToolkitRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AgentForgeApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var database: AgentForgeDatabase
        private set

    lateinit var preferencesRepository: UserPreferencesRepository
        private set

    lateinit var secretManager: KeystoreSecretManager
        private set

    lateinit var safProjectManager: SafProjectManager
        private set

    lateinit var backupManager: BackupManager
        private set

    lateinit var environmentInspector: EnvironmentInspector
        private set

    lateinit var commandExecutor: CommandExecutor
        private set

    lateinit var providersMap: Map<String, AIProvider>
        private set

    lateinit var modelRouter: SmartModelRouter
        private set

    lateinit var toolkitRegistry: AgentToolkitRegistry
        private set

    lateinit var orchestrator: AgentOrchestrator
        private set

    override fun onCreate() {
        super.onCreate()

        database = AgentForgeDatabase.getInstance(this)
        preferencesRepository = UserPreferencesRepository(this)
        secretManager = KeystoreSecretManager(this)
        safProjectManager = SafProjectManager(this)
        backupManager = BackupManager(database.dao(), safProjectManager)
        environmentInspector = EnvironmentInspector(this)
        commandExecutor = CommandExecutor(this, environmentInspector)

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val openRouter = OpenRouterProvider(okHttpClient)
        val gemini = GeminiProvider(okHttpClient)
        val groq = GroqProvider(okHttpClient)
        val githubModels = GitHubModelsProvider(okHttpClient)
        val huggingFace = HuggingFaceProvider(okHttpClient)

        providersMap = mapOf(
            openRouter.providerId to openRouter,
            gemini.providerId to gemini,
            groq.providerId to groq,
            githubModels.providerId to githubModels,
            huggingFace.providerId to huggingFace
        )

        modelRouter = SmartModelRouter(providersMap, secretManager).apply {
            registerCatalog(openRouter.providerId, OpenRouterProvider.defaultCatalog())
            registerCatalog(gemini.providerId, GeminiProvider.defaultCatalog())
            registerCatalog(groq.providerId, GroqProvider.defaultCatalog())
            registerCatalog(githubModels.providerId, GitHubModelsProvider.defaultCatalog())
            registerCatalog(huggingFace.providerId, HuggingFaceProvider.defaultCatalog())
        }

        toolkitRegistry = AgentToolkitRegistry(
            fileSystem = safProjectManager,
            backupManager = backupManager,
            commandExecutor = commandExecutor,
            environmentInspector = environmentInspector
        )

        orchestrator = AgentOrchestrator(
            dao = database.dao(),
            toolkit = toolkitRegistry,
            router = modelRouter,
            secretRedactor = { text -> secretManager.redactSecrets(text, providersMap.keys) }
        )

        applicationScope.launch {
            seedDefaultProviderConfigsIfEmpty()
        }
    }

    private suspend fun seedDefaultProviderConfigsIfEmpty() {
        val existing = database.dao().getProviderConfigs()
        if (existing.isNotEmpty()) return

        val defaults = listOf(
            ProviderConfigEntity(
                providerId = "openrouter",
                displayName = "OpenRouter (Free Catalog Router)",
                baseUrl = "https://openrouter.ai/api/v1",
                isEnabled = true,
                priorityOrder = 1,
                preferredCodingModelId = "openrouter/free",
                preferredAnalysisModelId = "qwen/qwen-2.5-coder-32b-instruct:free",
                supportsNativeToolCalling = true,
                maxContextTokens = 128000,
                isFreeTierVerified = true,
                lastQuotaInfo = "Free :free models & openrouter/free router"
            ),
            ProviderConfigEntity(
                providerId = "gemini",
                displayName = "Google Gemini API (AI Studio)",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                isEnabled = true,
                priorityOrder = 2,
                preferredCodingModelId = "gemini-2.5-flash",
                preferredAnalysisModelId = "gemini-2.5-pro",
                supportsNativeToolCalling = true,
                maxContextTokens = 1048576,
                isFreeTierVerified = true,
                lastQuotaInfo = "AI Studio Free Tier (RPM/RPD limits)"
            ),
            ProviderConfigEntity(
                providerId = "groq",
                displayName = "Groq Cloud API",
                baseUrl = "https://api.groq.com/openai/v1",
                isEnabled = true,
                priorityOrder = 3,
                preferredCodingModelId = "llama-3.3-70b-versatile",
                preferredAnalysisModelId = "qwen-qwq-32b",
                supportsNativeToolCalling = true,
                maxContextTokens = 128000,
                isFreeTierVerified = true,
                lastQuotaInfo = "Groq Free Developer Tier (Rate-limited)"
            ),
            ProviderConfigEntity(
                providerId = "github_models",
                displayName = "GitHub Models Inference API",
                baseUrl = "https://models.github.ai/inference",
                isEnabled = true,
                priorityOrder = 4,
                preferredCodingModelId = "openai/gpt-4o-mini",
                preferredAnalysisModelId = "meta/Llama-3.3-70B-Instruct",
                supportsNativeToolCalling = true,
                maxContextTokens = 64000,
                isFreeTierVerified = true,
                lastQuotaInfo = "GitHub Account Free Tier"
            ),
            ProviderConfigEntity(
                providerId = "huggingface",
                displayName = "Hugging Face Inference Router",
                baseUrl = "https://router.huggingface.co/v1",
                isEnabled = true,
                priorityOrder = 5,
                preferredCodingModelId = "Qwen/Qwen2.5-Coder-32B-Instruct",
                preferredAnalysisModelId = "meta-llama/Llama-3.3-70B-Instruct",
                supportsNativeToolCalling = true,
                maxContextTokens = 32768,
                isFreeTierVerified = true,
                lastQuotaInfo = "HF Free Monthly Inference Credits"
            )
        )
        database.dao().upsertProviderConfigs(defaults)
    }
}
