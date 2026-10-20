package com.agentforge.mobile.project

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class ProjectFileNode(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L,
    val lastModifiedEpochMs: Long = 0L,
    val depth: Int = 0
)

@Serializable
data class SearchMatch(
    val relativePath: String,
    val lineNumber: Int,
    val linePreview: String
)

sealed class FileOpResult<out T> {
    data class Success<T>(val data: T, val message: String = "OK") : FileOpResult<T>()
    data class Error(val errorMessage: String, val exception: Throwable? = null) : FileOpResult<Nothing>()

    val isSuccess: Boolean get() = this is Success<*>
}

/**
 * Clean abstraction for project file operations so both Android SAF (`SafProjectManager`)
 * and real local directory tests (`LocalDirectoryProjectFileSystem`) run through the exact
 * same agent tool and orchestrator pipeline.
 */
interface ProjectFileSystem {
    val fileTreeVersion: StateFlow<Long>

    fun hasActiveProject(): Boolean
    fun getProjectDisplayName(): String
    fun getProjectRootIdentifier(): String

    suspend fun listFiles(
        relativeSubDir: String = "",
        recursive: Boolean = true,
        includeIgnored: Boolean = false,
        maxEntries: Int = 500
    ): FileOpResult<List<ProjectFileNode>>

    suspend fun readFile(
        relativePath: String,
        maxBytes: Int = 256 * 1024
    ): FileOpResult<String>

    suspend fun createFile(
        relativePath: String,
        content: String,
        overwrite: Boolean = false
    ): FileOpResult<ProjectFileNode>

    suspend fun writeFile(
        relativePath: String,
        content: String
    ): FileOpResult<ProjectFileNode>

    suspend fun createDirectory(
        relativePath: String
    ): FileOpResult<ProjectFileNode>

    suspend fun renameFile(
        relativePath: String,
        newName: String
    ): FileOpResult<ProjectFileNode>

    suspend fun moveFile(
        sourceRelativePath: String,
        targetRelativePath: String
    ): FileOpResult<ProjectFileNode>

    suspend fun deleteFile(
        relativePath: String
    ): FileOpResult<Boolean>

    suspend fun searchInFiles(
        query: String,
        isRegex: Boolean = false,
        caseSensitive: Boolean = false,
        maxResults: Int = 100,
        maxFileBytes: Int = 256 * 1024
    ): FileOpResult<List<SearchMatch>>

    suspend fun ensureDefaultAgentIgnoreExists(): FileOpResult<Boolean>
}
