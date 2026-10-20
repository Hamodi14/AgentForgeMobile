package com.agentforge.mobile.project

import com.agentforge.mobile.storage.AgentForgeDao
import com.agentforge.mobile.storage.FileBackupEntity
import java.util.UUID

/**
 * Manages automatic and on-demand file snapshots before mutating operations (`write_file`,
 * `apply_patch`, `delete_file`) and supports one-tap undo or selective backup restoration.
 */
class BackupManager(
    private val dao: AgentForgeDao,
    private val fileSystem: ProjectFileSystem
) {
    suspend fun createBackupForFile(
        projectId: String,
        taskId: String?,
        relativePath: String,
        reason: String
    ): FileOpResult<FileBackupEntity> {
        val readResult = fileSystem.readFile(relativePath, maxBytes = 2 * 1024 * 1024)
        if (readResult is FileOpResult.Error) {
            return FileOpResult.Error("Cannot create backup for '$relativePath': ${readResult.errorMessage}")
        }
        val currentContent = (readResult as FileOpResult.Success).data
        val entity = FileBackupEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            taskId = taskId,
            relativePath = relativePath,
            originalContent = currentContent,
            backupReason = reason,
            createdAtEpochMs = System.currentTimeMillis()
        )
        dao.insertFileBackup(entity)
        return FileOpResult.Success(entity, "Backup created for '$relativePath' (id=${entity.id.take(8)}).")
    }

    suspend fun restoreBackupById(backupId: String): FileOpResult<ProjectFileNode> {
        val backup = dao.getBackupById(backupId)
            ?: return FileOpResult.Error("Backup ID '$backupId' not found.")
        return fileSystem.writeFile(backup.relativePath, backup.originalContent)
    }

    suspend fun restoreLatestBackupForFile(
        projectId: String,
        relativePath: String
    ): FileOpResult<ProjectFileNode> {
        val backup = dao.getLatestBackupForFile(projectId, relativePath)
            ?: return FileOpResult.Error("No backup found for file '$relativePath'.")
        return fileSystem.writeFile(backup.relativePath, backup.originalContent)
    }

    suspend fun undoMostRecentChangeInProject(projectId: String): FileOpResult<ProjectFileNode> {
        val latest = dao.getMostRecentBackupInProject(projectId)
            ?: return FileOpResult.Error("No backups available to undo in this project.")
        return fileSystem.writeFile(latest.relativePath, latest.originalContent)
    }
}
