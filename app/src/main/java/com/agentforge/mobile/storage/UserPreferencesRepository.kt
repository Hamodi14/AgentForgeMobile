package com.agentforge.mobile.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "agentforge_user_prefs")

data class AgentUserPreferences(
    val isDarkTheme: Boolean = true,
    val appLanguage: String = "ar", // "ar" (RTL) or "en" (LTR)
    val autoExecutionMode: Boolean = true,
    val routingMode: RoutingMode = RoutingMode.FREE_ONLY, // Default MUST be FREE_ONLY
    val manualProviderId: String = "openrouter",
    val manualModelId: String = "openrouter/free",
    val maxLoopIterations: Int = 15,
    val maxTaskTimeoutMinutes: Int = 10,
    val maxFileReadBytes: Int = 256 * 1024, // 256 KB per file read limit
    val retryWaitPolicySeconds: Int = 5,
    val maxRetriesPerProvider: Int = 2,
    val paidCostCapUsd: Double = 0.0,
    val acknowledgedExternalSendNotice: Boolean = false
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val IS_DARK_THEME = booleanPreferencesKey("is_dark_theme")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val AUTO_EXECUTION_MODE = booleanPreferencesKey("auto_execution_mode")
        val ROUTING_MODE = stringPreferencesKey("routing_mode")
        val MANUAL_PROVIDER_ID = stringPreferencesKey("manual_provider_id")
        val MANUAL_MODEL_ID = stringPreferencesKey("manual_model_id")
        val MAX_LOOP_ITERATIONS = intPreferencesKey("max_loop_iterations")
        val MAX_TASK_TIMEOUT_MINUTES = intPreferencesKey("max_task_timeout_minutes")
        val MAX_FILE_READ_BYTES = intPreferencesKey("max_file_read_bytes")
        val RETRY_WAIT_POLICY_SECONDS = intPreferencesKey("retry_wait_policy_seconds")
        val MAX_RETRIES_PER_PROVIDER = intPreferencesKey("max_retries_per_provider")
        val PAID_COST_CAP_USD = doublePreferencesKey("paid_cost_cap_usd")
        val ACK_EXTERNAL_NOTICE = booleanPreferencesKey("ack_external_notice")
    }

    val preferencesFlow: Flow<AgentUserPreferences> = context.dataStore.data.map { prefs ->
        AgentUserPreferences(
            isDarkTheme = prefs[Keys.IS_DARK_THEME] ?: true,
            appLanguage = prefs[Keys.APP_LANGUAGE] ?: "ar",
            autoExecutionMode = prefs[Keys.AUTO_EXECUTION_MODE] ?: true,
            routingMode = prefs[Keys.ROUTING_MODE]?.let {
                runCatching { RoutingMode.valueOf(it) }.getOrDefault(RoutingMode.FREE_ONLY)
            } ?: RoutingMode.FREE_ONLY,
            manualProviderId = prefs[Keys.MANUAL_PROVIDER_ID] ?: "openrouter",
            manualModelId = prefs[Keys.MANUAL_MODEL_ID] ?: "openrouter/free",
            maxLoopIterations = prefs[Keys.MAX_LOOP_ITERATIONS] ?: 15,
            maxTaskTimeoutMinutes = prefs[Keys.MAX_TASK_TIMEOUT_MINUTES] ?: 10,
            maxFileReadBytes = prefs[Keys.MAX_FILE_READ_BYTES] ?: (256 * 1024),
            retryWaitPolicySeconds = prefs[Keys.RETRY_WAIT_POLICY_SECONDS] ?: 5,
            maxRetriesPerProvider = prefs[Keys.MAX_RETRIES_PER_PROVIDER] ?: 2,
            paidCostCapUsd = prefs[Keys.PAID_COST_CAP_USD] ?: 0.0,
            acknowledgedExternalSendNotice = prefs[Keys.ACK_EXTERNAL_NOTICE] ?: false
        )
    }

    suspend fun setDarkTheme(isDark: Boolean) {
        context.dataStore.edit { it[Keys.IS_DARK_THEME] = isDark }
    }

    suspend fun setAppLanguage(lang: String) {
        context.dataStore.edit { it[Keys.APP_LANGUAGE] = lang }
    }

    suspend fun setAutoExecutionMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_EXECUTION_MODE] = enabled }
    }

    suspend fun setRoutingMode(mode: RoutingMode) {
        context.dataStore.edit { it[Keys.ROUTING_MODE] = mode.name }
    }

    suspend fun setManualSelection(providerId: String, modelId: String) {
        context.dataStore.edit {
            it[Keys.MANUAL_PROVIDER_ID] = providerId
            it[Keys.MANUAL_MODEL_ID] = modelId
        }
    }

    suspend fun setExecutionLimits(maxIterations: Int, maxTimeoutMinutes: Int, maxFileBytes: Int) {
        context.dataStore.edit {
            it[Keys.MAX_LOOP_ITERATIONS] = maxIterations.coerceIn(1, 100)
            it[Keys.MAX_TASK_TIMEOUT_MINUTES] = maxTimeoutMinutes.coerceIn(1, 120)
            it[Keys.MAX_FILE_READ_BYTES] = maxFileBytes.coerceIn(16 * 1024, 5 * 1024 * 1024)
        }
    }

    suspend fun setRetryPolicy(waitSeconds: Int, maxRetries: Int) {
        context.dataStore.edit {
            it[Keys.RETRY_WAIT_POLICY_SECONDS] = waitSeconds.coerceIn(1, 120)
            it[Keys.MAX_RETRIES_PER_PROVIDER] = maxRetries.coerceIn(0, 5)
        }
    }

    suspend fun setPaidCostCapUsd(capUsd: Double) {
        context.dataStore.edit {
            it[Keys.PAID_COST_CAP_USD] = capUsd.coerceAtLeast(0.0)
        }
    }

    suspend fun acknowledgeExternalSendNotice() {
        context.dataStore.edit { it[Keys.ACK_EXTERNAL_NOTICE] = true }
    }
}
