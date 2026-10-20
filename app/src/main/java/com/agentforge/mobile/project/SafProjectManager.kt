package com.agentforge.mobile.project

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.agentforge.mobile.security.PathValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Production implementation of [ProjectFileSystem] backed by Android's official
 * Storage Access Framework (`Intent.ACTION_OPEN_DOCUMENT_TREE` and `DocumentFile`).
 *
 * Persists URI permissions across device reboots via `takePersistableUriPermission`
 * and updates [fileTreeVersion] after every mutation so the UI Explorer refreshes immediately.
 */
class SafProjectManager(
    private val context: Context
) : ProjectFileSystem {

    private var activeTreeUri: Uri? = null
    private var activeRootDocument: DocumentFile? = null
    private var cachedIgnoreRules: AgentIgnoreRules = AgentIgnoreRules()

    private val _fileTreeVersion = MutableStateFlow(0L)
    override val fileTreeVersion: StateFlow<Long> = _fileTreeVersion.asStateFlow()

    /**
     * Binds and persists read/write access to a user-selected directory tree URI
     * returned from `ACTION_OPEN_DOCUMENT_TREE`.
     */
    fun bindProjectTreeUri(treeUri: Uri, takePersistablePermission: Boolean = true): FileOpResult<String> {
        return try {
            if (takePersistablePermission) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(treeUri, flags)
            }

            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            if (rootDoc == null || !rootDoc.exists() || !rootDoc.isDirectory || !rootDoc.canRead()) {
                return FileOpResult.Error(
                    "Selected directory URI is inaccessible or read permission was denied: $treeUri"
                )
            }

            activeTreeUri = treeUri
            activeRootDocument = rootDoc
            reloadIgnoreRulesSync(rootDoc)
            notifyTreeModified()
            FileOpResult.Success(rootDoc.name ?: "Project", "Bound project folder successfully.")
        } catch (e: SecurityException) {
            FileOpResult.Error(
                "Android SAF permission expired or was revoked for $treeUri. Please re-select the project folder.",
                e
            )
        } catch (e: Exception) {
            FileOpResult.Error("Failed to open project directory: ${e.message}", e)
        }
    }

    /**
     * Checks whether the persisted permission for [treeUriString] is still valid in Android's
     * `ContentResolver.persistedUriPermissions`.
     */
    fun hasValidPersistedPermission(treeUriString: String): Boolean {
        return try {
            val targetUri = Uri.parse(treeUriString)
            val persisted = context.contentResolver.persistedUriPermissions.any { perm ->
                perm.uri == targetUri && perm.isReadPermission && perm.isWritePermission
            }
            if (!persisted) return false
            val doc = DocumentFile.fromTreeUri(context, targetUri)
            doc != null && doc.exists() && doc.canRead()
        } catch (e: Exception) {
            false
        }
    }

    override fun hasActiveProject(): Boolean {
        val root = activeRootDocument ?: return false
        return root.exists() && root.canRead()
    }

    override fun getProjectDisplayName(): String {
        return activeRootDocument?.name ?: "No Project Selected"
    }

    override fun getProjectRootIdentifier(): String {
        return activeTreeUri?.toString() ?: ""
    }

    private fun notifyTreeModified() {
        _fileTreeVersion.value = System.currentTimeMillis()
    }

    private fun reloadIgnoreRulesSync(rootDoc: DocumentFile) {
        val ignoreFile = rootDoc.findFile(".agentignore")
        if (ignoreFile != null && ignoreFile.isFile && ignoreFile.canRead()) {
            val content = runCatching {
                context.contentResolver.openInputStream(ignoreFile.uri)?.use { stream ->
                    stream.bufferedReader(StandardCharsets.UTF_8).readText()
                } ?: ""
            }.getOrDefault("")
            cachedIgnoreRules = AgentIgnoreRules(content, includeDefaultExclusions = true)
        } else {
            cachedIgnoreRules = AgentIgnoreRules("", includeDefaultExclusions = true)
        }
    }

    override suspend fun ensureDefaultAgentIgnoreExists(): FileOpResult<Boolean> = withContext(Dispatchers.IO) {
        val root = activeRootDocument ?: return@withContext FileOpResult.Error("No active project folder selected.")
        val existing = root.findFile(".agentignore")
        if (existing != null && existing.exists()) {
            reloadIgnoreRulesSync(root)
            return@withContext FileOpResult.Success(false, ".agentignore already exists.")
        }
        val created = createFile(".agentignore", AgentIgnoreRules.DEFAULT_AGENTIGNORE_TEMPLATE, overwrite = false)
        return@withContext when (created) {
            is FileOpResult.Success -> {
                reloadIgnoreRulesSync(root)
                FileOpResult.Success(true, "Created default .agentignore file.")
            }
            is FileOpResult.Error -> created
        }
    }

    override suspend fun listFiles(
        relativeSubDir: String,
        recursive: Boolean,
        includeIgnored: Boolean,
        maxEntries: Int
    ): FileOpResult<List<ProjectFileNode>> = withContext(Dispatchers.IO) {
        val root = activeRootDocument ?: return@withContext FileOpResult.Error("No active project folder selected.")
        val validation = PathValidator.validateRelativePath(relativeSubDir, allowRootEmpty = true)
        if (!validation.isValid) {
            return@withContext FileOpResult.Error(validation.errorMessage ?: "Invalid directory path.")
        }

        val startDir = if (validation.segments.isEmpty()) {
            root
        } else {
            resolveDocumentNode(root, validation.segments)
                ?: return@withContext FileOpResult.Error("Directory not found: '${validation.normalizedPath}'")
        }

        if (!startDir.isDirectory) {
            return@withContext FileOpResult.Error("Path is not a directory: '${validation.normalizedPath}'")
        }

        reloadIgnoreRulesSync(root)
        val results = mutableListOf<ProjectFileNode>()

        fun traverse(currentDir: DocumentFile, prefixPath: String, depth: Int) {
            if (results.size >= maxEntries) return
            val children = currentDir.listFiles()
                .sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))

            for (child in children) {
                if (results.size >= maxEntries) break
                val name = child.name ?: continue
                val relPath = if (prefixPath.isEmpty()) name else "$prefixPath/$name"
                val isDir = child.isDirectory

                if (!includeIgnored && cachedIgnoreRules.isIgnored(relPath, isDir)) {
                    continue
                }

                results.add(
                    ProjectFileNode(
                        name = name,
                        relativePath = relPath,
                        isDirectory = isDir,
                        sizeBytes = if (isDir) 0L else child.length(),
                        lastModifiedEpochMs = child.lastModified(),
                        depth = depth
                    )
                )

                if (isDir && recursive) {
                    traverse(child, relPath, depth + 1)
                }
            }
        }

        traverse(startDir, validation.normalizedPath, validation.segments.size)
        FileOpResult.Success(results, "Listed ${results.size} entries.")
    }

    override suspend fun readFile(
        relativePath: String,
        maxBytes: Int
    ): FileOpResult<String> = withContext(Dispatchers.IO) {
        val root = activeRootDocument ?: return@withContext FileOpResult.Error("No active project folder selected.")
        val validation = PathValidator.validateRelativePath(relativePath, allowRootEmpty = false)
        if (!validation.isValid) {
            return@withContext FileOpResult.Error(validation.errorMessage ?: "Invalid file path.")
        }

        if (cachedIgnoreRules.isIgnored(validation.normalizedPath, isDirectory = false) &&
            validation.normalizedPath != ".agentignore"
        ) {
            return@withContext FileOpResult.Error(
                "Access blocked by .agentignore rules for '${validation.normalizedPath}'."
            )
        }

        val doc = resolveDocumentNode(root, validation.segments)
            ?: return@withContext FileOpResult.Error("File not found: '${validation.normalizedPath}'")

        if (!doc.isFile || !doc.canRead()) {
            return@withContext FileOpResult.Error("Cannot read '${validation.normalizedPath}': not a readable file.")
        }

        val length = doc.length()
        if (length > maxBytes) {
            return@withContext FileOpResult.Error(
                "File '${validation.normalizedPath}' is ${length / 1024} KB, which exceeds the configured read limit of ${maxBytes / 1024} KB."
            )
        }

        return@withContext try {
            val content = context.contentResolver.openInputStream(doc.uri)?.use { input ->
                val buffer = ByteArray(maxBytes.coerceAtMost(length.toInt().coerceAtLeast(4096)))
                val out = java.io.ByteArrayOutputStream()
                var totalRead = 0
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    totalRead += read
                    if (totalRead > maxBytes) {
                        return@withContext FileOpResult.Error(
                            "File '${validation.normalizedPath}' exceeded maximum read limit ($maxBytes bytes) during streaming."
                        )
                    }
                    out.write(buffer, 0, read)
                }
                out.toString(StandardCharsets.UTF_8.name())
            } ?: return@withContext FileOpResult.Error("Failed to open input stream for '${validation.normalizedPath}'.")

            FileOpResult.Success(content, "Read ${content.length} chars from '${validation.normalizedPath}'.")
        } catch (e: Exception) {
            FileOpResult.Error("Error reading '${validation.normalizedPath}': ${e.message}", e)
        }
    }

    override suspend fun createFile(
        relativePath: String,
        content: String,
        overwrite: Boolean
    ): FileOpResult<ProjectFileNode> = withContext(Dispatchers.IO) {
        val root = activeRootDocument ?: return@withContext FileOpResult.Error("No active project folder selected.")
        val validation = PathValidator.validateRelativePath(relativePath, allowRootEmpty = false)
        if (!validation.isValid) {
            return@withContext FileOpResult.Error(validation.errorMessage ?: "Invalid file path.")
        }

        val parentSegments = validation.segments.dropLast(1)
        val fileName = validation.segments.last()

        val parentDir = ensureDirectoriesExist(root, parentSegments)
            ?: return@withContext FileOpResult.Error("Failed to resolve or create parent directories for '${validation.normalizedPath}'.")

        val existing = parentDir.findFile(fileName)
        if (existing != null && existing.exists()) {
            if (!overwrite) {
                return@withContext FileOpResult.Error(
                    "File '${validation.normalizedPath}' already exists. Use write_file or apply_patch to modify it."
                )
            }
            return@withContext writeToDocumentFile(existing, validation.normalizedPath, validation.segments.size - 1, content)
        }

        val createdDoc = parentDir.createFile("application/octet-stream", fileName)
            ?: return@withContext FileOpResult.Error("SAF failed to create file '${validation.normalizedPath}'.")

        writeToDocumentFile(createdDoc, validation.normalizedPath, validation.segments.size - 1, content)
    }

    override suspend fun writeFile(
        relativePath: String,
        content: String
    ): FileOpResult<ProjectFileNode> = withContext(Dispatchers.IO) {
        val root = activeRootDocument ?: return@withContext FileOpResult.Error("No active project folder selected.")
        val validation = PathValidator.validateRelativePath(relativePath, allowRootEmpty = false)
        if (!validation.isValid) {
            return@withContext FileOpResult.Error(validation.errorMessage ?: "Invalid file path.")
        }

        val parentSegments = validation.segments.dropLast(1)
        val fileName = validation.segments.last()

        val parentDir = ensureDirectoriesExist(root, parentSegments)
            ?: return@withContext FileOpResult.Error("Failed to create parent directory for '${validation.normalizedPath}'.")

        val targetDoc = parentDir.findFile(fileName)
            ?: parentDir.createFile("application/octet-stream", fileName)
            ?: return@withContext FileOpResult.Error("Unable to open or create file '${validation.normalizedPath}'.")

        writeToDocumentFile(targetDoc, validation.normalizedPath, validation.segments.size - 1, content)
    }

    private fun writeToDocumentFile(
        doc: DocumentFile,
        normalizedPath: String,
        depth: Int,
        content: String
    ): FileOpResult<ProjectFileNode> {
        return try {
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: return FileOpResult.Error("Failed to open output stream for '$normalizedPath'.")

            if (normalizedPath == ".agentignore") {
                activeRootDocument?.let { reloadIgnoreRulesSync(it) }
            }
            notifyTreeModified()

            FileOpResult.Success(
                ProjectFileNode(
                    name = doc.name ?: normalizedPath.substringAfterLast('/'),
                    relativePath = normalizedPath,
                    isDirectory = false,
                    sizeBytes = bytes.size.toLong(),
                    lastModifiedEpochMs = doc.lastModified(),
                    depth = depth
                ),
                "Saved ${bytes.size} bytes to '$normalizedPath'."
            )
        } catch (e: Exception) {
            FileOpResult.Error("Failed writing to '$normalizedPath': ${e.message}", e)
        }
    }

    override suspend fun createDirectory(
        relativePath: String
    ): FileOpResult<ProjectFileNode> = withContext(Dispatchers.IO) {
        val root = activeRootDocument ?: return@withContext FileOpResult.Error("No active project folder selected.")
        val validation = PathValidator.validateRelativePath(relativePath, allowRootEmpty = false)
        if (!validation.isValid) {
            return@withContext FileOpResult.Error(validation.errorMessage ?: "Invalid directory path.")
        }

        val targetDir = ensureDirectoriesExist(root, validation.segments)
            ?: return@withContext FileOpResult.Error("Failed to create directory '${validation.normalizedPath}'.")

        notifyTreeModified()
        FileOpResult.Success(
            ProjectFileNode(
                name = targetDir.name ?: validation.segments.last(),
                relativePath = validation.normalizedPath,
                isDirectory = true,
                sizeBytes = 0L,
                lastModifiedEpochMs = targetDir.lastModified(),
                depth = validation.segments.size - 1
            ),
            "Directory '${validation.normalizedPath}' is ready."
        )
    }

    override suspend fun renameFile(
        relativePath: String,
        newName: String
    ): FileOpResult<ProjectFileNode> = withContext(Dispatchers.IO) {
        val root = activeRootDocument ?: return@withContext FileOpResult.Error("No active project folder selected.")
        val validation = PathValidator.validateRelativePath(relativePath, allowRootEmpty = false)
        if (!validation.isValid) {
            return@withContext FileOpResult.Error(validation.errorMessage ?: "Invalid source path.")
        }
        val cleanNewName = newName.trim()
        if (cleanNewName.isEmpty() || cleanNewName.contains('/') || cleanNewName.contains('\\') || cleanNewName == "..") {
            return@withContext FileOpResult.Error("Invalid new file name '$newName'. Name must not contain slashes or '..'.")
        }

        val doc = resolveDocumentNode(root, validation.segments)
            ?: return@withContext FileOpResult.Error("File not found: '${validation.normalizedPath}'")

        return@withContext try {
            val renamed = doc.renameTo(cleanNewName)
            if (!renamed) {
                return@withContext FileOpResult.Error("SAF provider failed to rename '${validation.normalizedPath}' to '$cleanNewName'.")
            }
            val parentPrefix = validation.segments.dropLast(1).joinToString("/")
            val newRelPath = if (parentPrefix.isEmpty()) cleanNewName else "$parentPrefix/$cleanNewName"
            notifyTreeModified()
            FileOpResult.Success(
                ProjectFileNode(
                    name = cleanNewName,
                    relativePath = newRelPath,
                    isDirectory = doc.isDirectory,
                    sizeBytes = if (doc.isDirectory) 0L else doc.length(),
                    lastModifiedEpochMs = doc.lastModified(),
                    depth = validation.segments.size - 1
                ),
                "Renamed '${validation.normalizedPath}' to '$newRelPath'."
            )
        } catch (e: Exception) {
            FileOpResult.Error("Error renaming '${validation.normalizedPath}': ${e.message}", e)
        }
    }

    override suspend fun moveFile(
        sourceRelativePath: String,
        targetRelativePath: String
    ): FileOpResult<ProjectFileNode> = withContext(Dispatchers.IO) {
        val root = activeRootDocument ?: return@withContext FileOpResult.Error("No active project folder selected.")
        val srcVal = PathValidator.validateRelativePath(sourceRelativePath, allowRootEmpty = false)
        val dstVal = PathValidator.validateRelativePath(targetRelativePath, allowRootEmpty = false)
        if (!srcVal.isValid) return@withContext FileOpResult.Error(srcVal.errorMessage ?: "Invalid source path.")
        if (!dstVal.isValid) return@withContext FileOpResult.Error(dstVal.errorMessage ?: "Invalid destination path.")

        val srcDoc = resolveDocumentNode(root, srcVal.segments)
            ?: return@withContext FileOpResult.Error("Source file not found: '${srcVal.normalizedPath}'")

        if (srcDoc.isDirectory) {
            return@withContext FileOpResult.Error("Moving entire directories is not supported directly; move individual files or use rename_file.")
        }

        // Read source content, write to destination, verify, then delete source
        val readResult = readFile(srcVal.normalizedPath, maxBytes = 5 * 1024 * 1024)
        if (readResult is FileOpResult.Error) return@withContext readResult
        val content = (readResult as FileOpResult.Success).data

        val writeResult = createFile(dstVal.normalizedPath, content, overwrite = true)
        if (writeResult is FileOpResult.Error) return@withContext writeResult

        val deleted = srcDoc.delete()
        notifyTreeModified()
        if (!deleted) {
            return@withContext FileOpResult.Error(
                "Copied file to '${dstVal.normalizedPath}', but SAF could not delete original '${srcVal.normalizedPath}'."
            )
        }

        FileOpResult.Success(
            (writeResult as FileOpResult.Success).data,
            "Moved '${srcVal.normalizedPath}' to '${dstVal.normalizedPath}'."
        )
    }

    override suspend fun deleteFile(
        relativePath: String
    ): FileOpResult<Boolean> = withContext(Dispatchers.IO) {
        val root = activeRootDocument ?: return@withContext FileOpResult.Error("No active project folder selected.")
        val validation = PathValidator.validateRelativePath(relativePath, allowRootEmpty = false)
        if (!validation.isValid) {
            return@withContext FileOpResult.Error(validation.errorMessage ?: "Invalid path.")
        }

        val doc = resolveDocumentNode(root, validation.segments)
            ?: return@withContext FileOpResult.Error("Target not found: '${validation.normalizedPath}'")

        return@withContext try {
            val deleted = doc.delete()
            notifyTreeModified()
            if (deleted) {
                FileOpResult.Success(true, "Deleted '${validation.normalizedPath}'.")
            } else {
                FileOpResult.Error("SAF provider refused to delete '${validation.normalizedPath}'.")
            }
        } catch (e: Exception) {
            FileOpResult.Error("Error deleting '${validation.normalizedPath}': ${e.message}", e)
        }
    }

    override suspend fun searchInFiles(
        query: String,
        isRegex: Boolean,
        caseSensitive: Boolean,
        maxResults: Int,
        maxFileBytes: Int
    ): FileOpResult<List<SearchMatch>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext FileOpResult.Error("Search query cannot be empty.")
        }
        val listResult = listFiles("", recursive = true, includeIgnored = false, maxEntries = 1000)
        if (listResult is FileOpResult.Error) return@withContext listResult

        val files = (listResult as FileOpResult.Success).data.filter { !it.isDirectory && it.sizeBytes in 1..maxFileBytes.toLong() }
        val matches = mutableListOf<SearchMatch>()

        val regex = if (isRegex) {
            runCatching {
                if (caseSensitive) Regex(query) else Regex(query, RegexOption.IGNORE_CASE)
            }.getOrElse {
                return@withContext FileOpResult.Error("Invalid regular expression '$query': ${it.message}")
            }
        } else null

        for (fileNode in files) {
            if (matches.size >= maxResults) break
            val readRes = readFile(fileNode.relativePath, maxBytes = maxFileBytes)
            if (readRes is FileOpResult.Success) {
                val lines = readRes.data.lines()
                for ((idx, line) in lines.withIndex()) {
                    val matched = if (regex != null) {
                        regex.containsMatchIn(line)
                    } else {
                        line.contains(query, ignoreCase = !caseSensitive)
                    }
                    if (matched) {
                        matches.add(
                            SearchMatch(
                                relativePath = fileNode.relativePath,
                                lineNumber = idx + 1,
                                linePreview = line.trim().take(200)
                            )
                        )
                        if (matches.size >= maxResults) break
                    }
                }
            }
        }

        FileOpResult.Success(matches, "Found ${matches.size} matches across project files.")
    }

    private fun resolveDocumentNode(root: DocumentFile, segments: List<String>): DocumentFile? {
        var current: DocumentFile = root
        for (segment in segments) {
            val next = current.findFile(segment) ?: return null
            current = next
        }
        return current
    }

    private fun ensureDirectoriesExist(root: DocumentFile, dirSegments: List<String>): DocumentFile? {
        var current: DocumentFile = root
        for (segment in dirSegments) {
            val existing = current.findFile(segment)
            current = when {
                existing != null && existing.isDirectory -> existing
                existing != null && !existing.isDirectory -> return null
                else -> current.createDirectory(segment) ?: return null
            }
        }
        return current
    }
}
