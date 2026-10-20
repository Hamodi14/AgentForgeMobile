package com.agentforge.mobile.testing

import com.agentforge.mobile.storage.AgentForgeDao
import com.agentforge.mobile.storage.ChatMessageEntity
import com.agentforge.mobile.storage.ExecutionLogEntity
import com.agentforge.mobile.storage.FileBackupEntity
import com.agentforge.mobile.storage.ProjectEntity
import com.agentforge.mobile.storage.ProviderConfigEntity
import com.agentforge.mobile.storage.TaskSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Real in-memory implementation of [AgentForgeDao] for fast deterministic unit and integration testing.
 */
class FakeInMemoryDao : AgentForgeDao {
    private val projects = MutableStateFlow<List<ProjectEntity>>(emptyList())
    private val sessions = MutableStateFlow<List<TaskSessionEntity>>(emptyList())
    private val messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
    private val logs = MutableStateFlow<List<ExecutionLogEntity>>(emptyList())
    private val backups = MutableStateFlow<List<FileBackupEntity>>(emptyList())
    private val providers = MutableStateFlow<List<ProviderConfigEntity>>(emptyList())

    override suspend fun upsertProject(project: ProjectEntity) {
        projects.value = projects.value.filterNot { it.id == project.id } + project
    }

    override fun observeRecentProjects(): Flow<List<ProjectEntity>> =
        projects.map { it.sortedByDescending { p -> p.lastOpenedAtEpochMs } }

    override suspend fun getLastOpenedProject(): ProjectEntity? =
        projects.value.maxByOrNull { it.lastOpenedAtEpochMs }

    override suspend fun getProjectById(projectId: String): ProjectEntity? =
        projects.value.find { it.id == projectId }

    override suspend fun deleteProject(projectId: String) {
        projects.value = projects.value.filterNot { it.id == projectId }
    }

    override suspend fun upsertTaskSession(session: TaskSessionEntity) {
        sessions.value = sessions.value.filterNot { it.id == session.id } + session
    }

    override suspend fun updateTaskSession(session: TaskSessionEntity) {
        upsertTaskSession(session)
    }

    override suspend fun getTaskSessionById(taskId: String): TaskSessionEntity? =
        sessions.value.find { it.id == taskId }

    override fun observeLatestTaskForProject(projectId: String): Flow<TaskSessionEntity?> =
        sessions.map { list -> list.filter { it.projectId == projectId }.maxByOrNull { it.updatedAtEpochMs } }

    override suspend fun getLatestTaskForProject(projectId: String): TaskSessionEntity? =
        sessions.value.filter { it.projectId == projectId }.maxByOrNull { it.updatedAtEpochMs }

    override suspend fun clearTaskSessionsForProject(projectId: String) {
        sessions.value = sessions.value.filterNot { it.projectId == projectId }
    }

    override suspend fun insertChatMessage(message: ChatMessageEntity) {
        messages.value = messages.value + message
    }

    override fun observeChatMessages(projectId: String): Flow<List<ChatMessageEntity>> =
        messages.map { list -> list.filter { it.projectId == projectId }.sortedBy { it.timestampEpochMs } }

    override suspend fun getChatMessages(projectId: String): List<ChatMessageEntity> =
        messages.value.filter { it.projectId == projectId }.sortedBy { it.timestampEpochMs }

    override suspend fun clearChatMessagesForProject(projectId: String) {
        messages.value = messages.value.filterNot { it.projectId == projectId }
    }

    override suspend fun insertExecutionLog(log: ExecutionLogEntity) {
        logs.value = logs.value + log
    }

    override fun observeExecutionLogs(projectId: String): Flow<List<ExecutionLogEntity>> =
        logs.map { list -> list.filter { it.projectId == projectId }.sortedByDescending { it.startedAtEpochMs } }

    override suspend fun getExecutionLogs(projectId: String): List<ExecutionLogEntity> =
        logs.value.filter { it.projectId == projectId }.sortedByDescending { it.startedAtEpochMs }

    override suspend fun clearExecutionLogsForProject(projectId: String) {
        logs.value = logs.value.filterNot { it.projectId == projectId }
    }

    override suspend fun insertFileBackup(backup: FileBackupEntity) {
        backups.value = backups.value + backup
    }

    override fun observeFileBackups(projectId: String): Flow<List<FileBackupEntity>> =
        backups.map { list -> list.filter { it.projectId == projectId }.sortedByDescending { it.createdAtEpochMs } }

    override suspend fun getLatestBackupForFile(projectId: String, relativePath: String): FileBackupEntity? =
        backups.value
            .filter { it.projectId == projectId && it.relativePath == relativePath }
            .maxByOrNull { it.createdAtEpochMs }

    override suspend fun getBackupById(backupId: String): FileBackupEntity? =
        backups.value.find { it.id == backupId }

    override suspend fun getMostRecentBackupInProject(projectId: String): FileBackupEntity? =
        backups.value.filter { it.projectId == projectId }.maxByOrNull { it.createdAtEpochMs }

    override suspend fun clearBackupsForProject(projectId: String) {
        backups.value = backups.value.filterNot { it.projectId == projectId }
    }

    override suspend fun upsertProviderConfigs(configs: List<ProviderConfigEntity>) {
        providers.value = configs
    }

    override suspend fun upsertProviderConfig(config: ProviderConfigEntity) {
        providers.value = providers.value.filterNot { it.providerId == config.providerId } + config
    }

    override fun observeProviderConfigs(): Flow<List<ProviderConfigEntity>> =
        providers.map { it.sortedBy { c -> c.priorityOrder } }

    override suspend fun getProviderConfigs(): List<ProviderConfigEntity> =
        providers.value.sortedBy { it.priorityOrder }

    override suspend fun getProviderConfigById(providerId: String): ProviderConfigEntity? =
        providers.value.find { it.providerId == providerId }
}
