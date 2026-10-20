package com.agentforge.mobile.project

import com.agentforge.mobile.security.PathValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Real file-system implementation of [ProjectFileSystem] operating on a bounded `java.io.File`
 * root directory. Used both for real local sandbox/test execution and when a direct directory
 * path is accessible within app-permitted storage.
 *
 * Enforces the exact same [PathValidator] canonical path boundary checks and [AgentIgnoreRules]
 * as [SafProjectManager].
 */
class LocalDirectoryProjectFileSystem(
    private var rootDirectory: File
) : ProjectFileSystem {

    private val _fileTreeVersion = MutableStateFlow(0L)
    override val fileTreeVersion: StateFlow<Long> = _fileTreeVersion.asStateFlow()

    private var cachedIgnoreRules: AgentIgnoreRules = AgentIgnoreRules()

    init {
        if (rootDirectory.exists() && rootDirectory.isDirectory) {
            reloadIgnoreRules()
        }
    }

    fun setRootDirectory(dir: File) {
        rootDirectory = dir
        reloadIgnoreRules()
        notifyModified()
    }

    private fun notifyModified() {
        _fileTreeVersion.value = System.currentTimeMillis()
    }

    private fun reloadIgnoreRules() {
        val ignoreFile = File(rootDirectory, ".agentignore")
        cachedIgnoreRules = if (ignoreFile.exists() && ignoreFile.isFile) {
            AgentIgnoreRules(ignoreFile.readText(StandardCharsets.UTF_8), includeDefaultExclusions = true)
        } else {
            AgentIgnoreRules("", includeDefaultExclusions = true)
        }
    }

    override fun hasActiveProject(): Boolean =
        rootDirectory.exists() && rootDirectory.isDirectory && rootDirectory.canRead()

    override fun getProjectDisplayName(): String = rootDirectory.name

    override fun getProjectRootIdentifier(): String = rootDirectory.canonicalPath

    private fun resolveSafeFile(relativePath: String, allowRootEmpty: Boolean = false): FileOpResult<Pair<File, String>> {
        if (!hasActiveProject()) {
            return FileOpResult.Error("No active project directory is open.")
        }
        val validation = PathValidator.validateRelativePath(relativePath, allowRootEmpty = allowRootEmpty)
        if (!validation.isValid) {
            return FileOpResult.Error(validation.errorMessage ?: "Invalid path: '$relativePath'")
        }
        val rootCanonical = rootDirectory.canonicalFile
        val targetFile = if (validation.normalizedPath.isEmpty()) {
            rootCanonical
        } else {
            File(rootCanonical, validation.normalizedPath).canonicalFile
        }

        val rootPrefix = rootCanonical.path + File.separator
        if (targetFile != rootCanonical && !targetFile.path.startsWith(rootPrefix)) {
            return FileOpResult.Error("Security violation: Path '$relativePath' escapes project root.")
        }
        return FileOpResult.Success(targetFile to validation.normalizedPath)
    }

    override suspend fun ensureDefaultAgentIgnoreExists(): FileOpResult<Boolean> = withContext(Dispatchers.IO) {
        val ignoreFile = File(rootDirectory, ".agentignore")
        if (ignoreFile.exists()) {
            reloadIgnoreRules()
            return@withContext FileOpResult.Success(false, ".agentignore already exists.")
        }
        ignoreFile.writeText(AgentIgnoreRules.DEFAULT_AGENTIGNORE_TEMPLATE, StandardCharsets.UTF_8)
        reloadIgnoreRules()
        notifyModified()
        FileOpResult.Success(true, "Created default .agentignore.")
    }

    override suspend fun listFiles(
        relativeSubDir: String,
        recursive: Boolean,
        includeIgnored: Boolean,
        maxEntries: Int
    ): FileOpResult<List<ProjectFileNode>> = withContext(Dispatchers.IO) {
        val resolved = resolveSafeFile(relativeSubDir, allowRootEmpty = true)
        if (resolved is FileOpResult.Error) return@withContext resolved
        val (startDir, normSubDir) = (resolved as FileOpResult.Success).data

        if (!startDir.exists() || !startDir.isDirectory) {
            return@withContext FileOpResult.Error("Directory not found: '$normSubDir'")
        }

        reloadIgnoreRules()
        val nodes = mutableListOf<ProjectFileNode>()

        fun walk(current: File, relPrefix: String, depth: Int) {
            if (nodes.size >= maxEntries) return
            val children = (current.listFiles() ?: emptyArray())
                .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))

            for (child in children) {
                if (nodes.size >= maxEntries) break
                val relPath = if (relPrefix.isEmpty()) child.name else "$relPrefix/${child.name}"
                val isDir = child.isDirectory
                if (!includeIgnored && cachedIgnoreRules.isIgnored(relPath, isDir)) {
                    continue
                }
                nodes.add(
                    ProjectFileNode(
                        name = child.name,
                        relativePath = relPath,
                        isDirectory = isDir,
                        sizeBytes = if (isDir) 0L else child.length(),
                        lastModifiedEpochMs = child.lastModified(),
                        depth = depth
                    )
                )
                if (isDir && recursive) {
                    walk(child, relPath, depth + 1)
                }
            }
        }

        val initialDepth = if (normSubDir.isEmpty()) 0 else normSubDir.split('/').size
        walk(startDir, normSubDir, initialDepth)
        FileOpResult.Success(nodes, "Listed ${nodes.size} entries.")
    }

    override suspend fun readFile(
        relativePath: String,
        maxBytes: Int
    ): FileOpResult<String> = withContext(Dispatchers.IO) {
        val resolved = resolveSafeFile(relativePath, allowRootEmpty = false)
        if (resolved is FileOpResult.Error) return@withContext resolved
        val (file, normPath) = (resolved as FileOpResult.Success).data

        if (cachedIgnoreRules.isIgnored(normPath, isDirectory = false) && normPath != ".agentignore") {
            return@withContext FileOpResult.Error("Access blocked by .agentignore rules for '$normPath'.")
        }
        if (!file.exists() || !file.isFile) {
            return@withContext FileOpResult.Error("File not found: '$normPath'")
        }
        if (file.length() > maxBytes) {
            return@withContext FileOpResult.Error(
                "File '$normPath' (${file.length()} bytes) exceeds max read size ($maxBytes bytes)."
            )
        }
        val text = file.readText(StandardCharsets.UTF_8)
        FileOpResult.Success(text, "Read ${text.length} chars from '$normPath'.")
    }

    override suspend fun createFile(
        relativePath: String,
        content: String,
        overwrite: Boolean
    ): FileOpResult<ProjectFileNode> = withContext(Dispatchers.IO) {
        val resolved = resolveSafeFile(relativePath, allowRootEmpty = false)
        if (resolved is FileOpResult.Error) return@withContext resolved
        val (file, normPath) = (resolved as FileOpResult.Success).data

        if (file.exists() && !overwrite) {
            return@withContext FileOpResult.Error("File '$normPath' already exists.")
        }
        file.parentFile?.mkdirs()
        file.writeText(content, StandardCharsets.UTF_8)
        if (normPath == ".agentignore") reloadIgnoreRules()
        notifyModified()

        FileOpResult.Success(
            ProjectFileNode(
                name = file.name,
                relativePath = normPath,
                isDirectory = false,
                sizeBytes = file.length(),
                lastModifiedEpochMs = file.lastModified(),
                depth = normPath.count { it == '/' }
            ),
            "Created '$normPath' (${file.length()} bytes)."
        )
    }

    override suspend fun writeFile(
        relativePath: String,
        content: String
    ): FileOpResult<ProjectFileNode> = createFile(relativePath, content, overwrite = true)

    override suspend fun createDirectory(
        relativePath: String
    ): FileOpResult<ProjectFileNode> = withContext(Dispatchers.IO) {
        val resolved = resolveSafeFile(relativePath, allowRootEmpty = false)
        if (resolved is FileOpResult.Error) return@withContext resolved
        val (dir, normPath) = (resolved as FileOpResult.Success).data

        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext FileOpResult.Error("Failed to create directory '$normPath'.")
        }
        notifyModified()
        FileOpResult.Success(
            ProjectFileNode(
                name = dir.name,
                relativePath = normPath,
                isDirectory = true,
                sizeBytes = 0L,
                lastModifiedEpochMs = dir.lastModified(),
                depth = normPath.count { it == '/' }
            ),
            "Directory '$normPath' created."
        )
    }

    override suspend fun renameFile(
        relativePath: String,
        newName: String
    ): FileOpResult<ProjectFileNode> = withContext(Dispatchers.IO) {
        val cleanName = newName.trim()
        if (cleanName.isEmpty() || cleanName.contains('/') || cleanName.contains('\\') || cleanName == "..") {
            return@withContext FileOpResult.Error("Invalid target file name: '$newName'")
        }
        val parentPrefix = relativePath.replace('\\', '/').trim('/').substringBeforeLast('/', "")
        val targetRel = if (parentPrefix.isEmpty()) cleanName else "$parentPrefix/$cleanName"
        moveFile(relativePath, targetRel)
    }

    override suspend fun moveFile(
        sourceRelativePath: String,
        targetRelativePath: String
    ): FileOpResult<ProjectFileNode> = withContext(Dispatchers.IO) {
        val srcRes = resolveSafeFile(sourceRelativePath, allowRootEmpty = false)
        val dstRes = resolveSafeFile(targetRelativePath, allowRootEmpty = false)
        if (srcRes is FileOpResult.Error) return@withContext srcRes
        if (dstRes is FileOpResult.Error) return@withContext dstRes

        val (srcFile, srcNorm) = (srcRes as FileOpResult.Success).data
        val (dstFile, dstNorm) = (dstRes as FileOpResult.Success).data

        if (!srcFile.exists()) {
            return@withContext FileOpResult.Error("Source file does not exist: '$srcNorm'")
        }
        dstFile.parentFile?.mkdirs()
        if (dstFile.exists()) dstFile.delete()

        val moved = srcFile.renameTo(dstFile)
        if (!moved) {
            srcFile.copyTo(dstFile, overwrite = true)
            srcFile.delete()
        }
        notifyModified()
        FileOpResult.Success(
            ProjectFileNode(
                name = dstFile.name,
                relativePath = dstNorm,
                isDirectory = dstFile.isDirectory,
                sizeBytes = if (dstFile.isDirectory) 0L else dstFile.length(),
                lastModifiedEpochMs = dstFile.lastModified(),
                depth = dstNorm.count { it == '/' }
            ),
            "Moved '$srcNorm' to '$dstNorm'."
        )
    }

    override suspend fun deleteFile(
        relativePath: String
    ): FileOpResult<Boolean> = withContext(Dispatchers.IO) {
        val resolved = resolveSafeFile(relativePath, allowRootEmpty = false)
        if (resolved is FileOpResult.Error) return@withContext resolved
        val (file, normPath) = (resolved as FileOpResult.Success).data

        if (!file.exists()) {
            return@withContext FileOpResult.Error("Target does not exist: '$normPath'")
        }
        val deleted = file.deleteRecursively()
        notifyModified()
        if (deleted) {
            FileOpResult.Success(true, "Deleted '$normPath'.")
        } else {
            FileOpResult.Error("Failed to delete '$normPath'.")
        }
    }

    override suspend fun searchInFiles(
        query: String,
        isRegex: Boolean,
        caseSensitive: Boolean,
        maxResults: Int,
        maxFileBytes: Int
    ): FileOpResult<List<SearchMatch>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext FileOpResult.Error("Query cannot be blank.")
        val listRes = listFiles("", recursive = true, includeIgnored = false, maxEntries = 1000)
        if (listRes is FileOpResult.Error) return@withContext listRes

        val matches = mutableListOf<SearchMatch>()
        val regex = if (isRegex) {
            runCatching {
                if (caseSensitive) Regex(query) else Regex(query, RegexOption.IGNORE_CASE)
            }.getOrElse {
                return@withContext FileOpResult.Error("Invalid regex '$query': ${it.message}")
            }
        } else null

        for (node in (listRes as FileOpResult.Success).data) {
            if (matches.size >= maxResults) break
            if (node.isDirectory || node.sizeBytes !in 1..maxFileBytes.toLong()) continue
            val readRes = readFile(node.relativePath, maxFileBytes)
            if (readRes is FileOpResult.Success) {
                readRes.data.lines().forEachIndexed { idx, line ->
                    if (matches.size < maxResults) {
                        val hit = regex?.containsMatchIn(line)
                            ?: line.contains(query, ignoreCase = !caseSensitive)
                        if (hit) {
                            matches.add(
                                SearchMatch(
                                    relativePath = node.relativePath,
                                    lineNumber = idx + 1,
                                    linePreview = line.trim().take(200)
                                )
                            )
                        }
                    }
                }
            }
        }
        FileOpResult.Success(matches, "Found ${matches.size} matches.")
    }
}
