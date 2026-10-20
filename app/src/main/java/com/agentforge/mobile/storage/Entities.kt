package com.agentforge.mobile.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class TaskRunStatus {
    IDLE,
    ANALYZING,
    EXECUTING,
    TESTING,
    AWAITING_APPROVAL,
    PAUSED,
    COMPLETED,
    FAILED
}

@Serializable
enum class RoutingMode {
    FREE_ONLY,
    MANUAL,
    CUSTOM_PRIORITY
}

@Entity(tableName = "projects")
@Serializable
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val treeUriString: String,
    val detectedLanguage: String = "Unknown",
    val detectedFramework: String = "Unknown",
    val lastOpenedAtEpochMs: Long = System.currentTimeMillis(),
    val customIgnoreRules: String = ""
)

@Entity(
    tableName = "task_sessions",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
@Serializable
data class TaskSessionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val userGoal: String,
    val status: TaskRunStatus,
    val planJson: String = "[]",
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val contextSummary: String = "",
    val inspectedFilesJson: String = "[]",
    val modifiedFilesJson: String = "[]",
    val toolHistorySummaryJson: String = "[]",
    val activeProviderId: String = "",
    val activeModelId: String = "",
    val pendingApprovalJson: String? = null,
    val lastSafeCheckpointStep: Int = 0,
    val loopIterationCount: Int = 0,
    val lastErrorMessage: String? = null,
    val finalReport: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("taskId")]
)
@Serializable
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val taskId: String?,
    val role: String, // "user", "assistant", "tool", "system_event"
    val content: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val toolName: String? = null,
    val isSuccess: Boolean = true,
    val timestampEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "execution_logs",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("taskId")]
)
@Serializable
data class ExecutionLogEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val taskId: String,
    val stepIndex: Int,
    val toolName: String,
    val targetFilePath: String?,
    val providerId: String,
    val modelId: String,
    val isFailoverEvent: Boolean = false,
    val failoverReason: String? = null,
    val isSuccess: Boolean,
    val summaryMessage: String,
    val errorDetail: String? = null,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long
)

@Entity(
    tableName = "file_backups",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("relativePath")]
)
@Serializable
data class FileBackupEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val taskId: String?,
    val relativePath: String,
    val originalContent: String,
    val backupReason: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "provider_configs")
@Serializable
data class ProviderConfigEntity(
    @PrimaryKey val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val isEnabled: Boolean,
    val priorityOrder: Int,
    val preferredCodingModelId: String,
    val preferredAnalysisModelId: String,
    val supportsNativeToolCalling: Boolean,
    val maxContextTokens: Int,
    val isFreeTierVerified: Boolean,
    val lastCatalogSyncEpochMs: Long = 0L,
    val lastKnownStatus: String = "Not Checked",
    val lastQuotaInfo: String = "Dynamic / Provider-managed",
    val maxCostCapUsd: Double = 0.0
)
