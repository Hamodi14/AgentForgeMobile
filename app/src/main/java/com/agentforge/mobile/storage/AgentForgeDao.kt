package com.agentforge.mobile.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentForgeDao {

    // Projects
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity)

    @Query("SELECT * FROM projects ORDER BY lastOpenedAtEpochMs DESC")
    fun observeRecentProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY lastOpenedAtEpochMs DESC LIMIT 1")
    suspend fun getLastOpenedProject(): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :projectId LIMIT 1")
    suspend fun getProjectById(projectId: String): ProjectEntity?

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: String)

    // Task Sessions
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTaskSession(session: TaskSessionEntity)

    @Update
    suspend fun updateTaskSession(session: TaskSessionEntity)

    @Query("SELECT * FROM task_sessions WHERE id = :taskId LIMIT 1")
    suspend fun getTaskSessionById(taskId: String): TaskSessionEntity?

    @Query("SELECT * FROM task_sessions WHERE projectId = :projectId ORDER BY updatedAtEpochMs DESC LIMIT 1")
    fun observeLatestTaskForProject(projectId: String): Flow<TaskSessionEntity?>

    @Query("SELECT * FROM task_sessions WHERE projectId = :projectId ORDER BY updatedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestTaskForProject(projectId: String): TaskSessionEntity?

    @Query("DELETE FROM task_sessions WHERE projectId = :projectId")
    suspend fun clearTaskSessionsForProject(projectId: String)

    // Chat Messages
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE projectId = :projectId ORDER BY timestampEpochMs ASC")
    fun observeChatMessages(projectId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE projectId = :projectId ORDER BY timestampEpochMs ASC")
    suspend fun getChatMessages(projectId: String): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE projectId = :projectId")
    suspend fun clearChatMessagesForProject(projectId: String)

    // Execution Logs
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExecutionLog(log: ExecutionLogEntity)

    @Query("SELECT * FROM execution_logs WHERE projectId = :projectId ORDER BY startedAtEpochMs DESC")
    fun observeExecutionLogs(projectId: String): Flow<List<ExecutionLogEntity>>

    @Query("SELECT * FROM execution_logs WHERE projectId = :projectId ORDER BY startedAtEpochMs DESC")
    suspend fun getExecutionLogs(projectId: String): List<ExecutionLogEntity>

    @Query("DELETE FROM execution_logs WHERE projectId = :projectId")
    suspend fun clearExecutionLogsForProject(projectId: String)

    // File Backups
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFileBackup(backup: FileBackupEntity)

    @Query("SELECT * FROM file_backups WHERE projectId = :projectId ORDER BY createdAtEpochMs DESC")
    fun observeFileBackups(projectId: String): Flow<List<FileBackupEntity>>

    @Query("SELECT * FROM file_backups WHERE projectId = :projectId AND relativePath = :relativePath ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun getLatestBackupForFile(projectId: String, relativePath: String): FileBackupEntity?

    @Query("SELECT * FROM file_backups WHERE id = :backupId LIMIT 1")
    suspend fun getBackupById(backupId: String): FileBackupEntity?

    @Query("SELECT * FROM file_backups WHERE projectId = :projectId ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun getMostRecentBackupInProject(projectId: String): FileBackupEntity?

    @Query("DELETE FROM file_backups WHERE projectId = :projectId")
    suspend fun clearBackupsForProject(projectId: String)

    // Provider Configs
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProviderConfigs(configs: List<ProviderConfigEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProviderConfig(config: ProviderConfigEntity)

    @Query("SELECT * FROM provider_configs ORDER BY priorityOrder ASC")
    fun observeProviderConfigs(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_configs ORDER BY priorityOrder ASC")
    suspend fun getProviderConfigs(): List<ProviderConfigEntity>

    @Query("SELECT * FROM provider_configs WHERE providerId = :providerId LIMIT 1")
    suspend fun getProviderConfigById(providerId: String): ProviderConfigEntity?
}
