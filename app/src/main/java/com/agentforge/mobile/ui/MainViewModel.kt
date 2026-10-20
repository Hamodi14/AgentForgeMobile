package com.agentforge.mobile.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.agentforge.mobile.AgentForgeApp
import com.agentforge.mobile.agent.OrchestratorUiState
import com.agentforge.mobile.project.DiffEngine
import com.agentforge.mobile.project.FileOpResult
import com.agentforge.mobile.project.ProjectFileNode
import com.agentforge.mobile.project.ProjectInspector
import com.agentforge.mobile.project.SearchMatch
import com.agentforge.mobile.providers.AIModelDescriptor
import com.agentforge.mobile.storage.AgentUserPreferences
import com.agentforge.mobile.storage.ChatMessageEntity
import com.agentforge.mobile.storage.ExecutionLogEntity
import com.agentforge.mobile.storage.FileBackupEntity
import com.agentforge.mobile.storage.ProjectEntity
import com.agentforge.mobile.storage.ProviderConfigEntity
import com.agentforge.mobile.storage.RoutingMode
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val app: AgentForgeApp) : ViewModel() {

    val preferences: StateFlow<AgentUserPreferences> = app.preferencesRepository.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentUserPreferences())

    val orchestratorState: StateFlow<OrchestratorUiState> = app.orchestrator.uiState

    val recentProjects: StateFlow<List<ProjectEntity>> = app.database.dao().observeRecentProjects()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val providerConfigs: StateFlow<List<ProviderConfigEntity>> = app.database.dao().observeProviderConfigs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentProject = MutableStateFlow<ProjectEntity?>(null)
    val currentProject: StateFlow<ProjectEntity?> = _currentProject.asStateFlow()

    private val _projectFiles = MutableStateFlow<List<ProjectFileNode>>(emptyList())
    val projectFiles: StateFlow<List<ProjectFileNode>> = _projectFiles.asStateFlow()

    private val _selectedFilePath = MutableStateFlow<String?>(null)
    val selectedFilePath: StateFlow<String?> = _selectedFilePath.asStateFlow()

    private val _selectedFileContent = MutableStateFlow<String>("")
    val selectedFileContent: StateFlow<String> = _selectedFileContent.asStateFlow()

    private val _selectedFileDiff = MutableStateFlow<String>("")
    val selectedFileDiff: StateFlow<String> = _selectedFileDiff.asStateFlow()

    private val _searchMatches = MutableStateFlow<List<SearchMatch>>(emptyList())
    val searchMatches: StateFlow<List<SearchMatch>> = _searchMatches.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _providerCatalogs = MutableStateFlow<Map<String, List<AIModelDescriptor>>>(emptyMap())
    val providerCatalogs: StateFlow<Map<String, List<AIModelDescriptor>>> = _providerCatalogs.asStateFlow()

    val chatMessages: StateFlow<List<ChatMessageEntity>> = _currentProject
        .flatMapLatest { proj ->
            if (proj == null) flowOf(emptyList())
            else app.database.dao().observeChatMessages(proj.id)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val executionLogs: StateFlow<List<ExecutionLogEntity>> = _currentProject
        .flatMapLatest { proj ->
            if (proj == null) flowOf(emptyList())
            else app.database.dao().observeExecutionLogs(proj.id)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val fileBackups: StateFlow<List<FileBackupEntity>> = _currentProject
        .flatMapLatest { proj ->
            if (proj == null) flowOf(emptyList())
            else app.database.dao().observeFileBackups(proj.id)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        // Sync initial model catalogs
        val initialMap = app.providersMap.keys.associateWith { id ->
            app.modelRouter.getCachedCatalog(id)
        }
        _providerCatalogs.value = initialMap

        // Restore last opened project if its SAF permission is still valid
        viewModelScope.launch {
            val lastProj = app.database.dao().getLastOpenedProject()
            if (lastProj != null && app.safProjectManager.hasValidPersistedPermission(lastProj.treeUriString)) {
                openProjectFromUri(Uri.parse(lastProj.treeUriString), takePersistable = false)
            }
        }

        // Automatically refresh Explorer whenever SAF fileTreeVersion updates
        viewModelScope.launch {
            app.safProjectManager.fileTreeVersion.collect {
                if (app.safProjectManager.hasActiveProject()) {
                    refreshFileTree()
                }
            }
        }
    }

    fun openProjectFromUri(treeUri: Uri, takePersistable: Boolean = true) {
        viewModelScope.launch {
            when (val res = app.safProjectManager.bindProjectTreeUri(treeUri, takePersistable)) {
                is FileOpResult.Success -> {
                    app.safProjectManager.ensureDefaultAgentIgnoreExists()
                    val inspection = (ProjectInspector.inspect(app.safProjectManager) as? FileOpResult.Success)?.data
                    val projectId = treeUri.toString().hashCode().toUInt().toString(16)
                    val entity = ProjectEntity(
                        id = projectId,
                        name = res.data,
                        treeUriString = treeUri.toString(),
                        detectedLanguage = inspection?.primaryLanguage ?: "Unknown",
                        detectedFramework = inspection?.detectedFrameworks?.joinToString(", ") ?: "Standard",
                        lastOpenedAtEpochMs = System.currentTimeMillis()
                    )
                    app.database.dao().upsertProject(entity)
                    _currentProject.value = entity
                    refreshFileTree()
                    _statusBanner.value = "Opened project '${entity.name}'"
                }
                is FileOpResult.Error -> {
                    _statusBanner.value = res.errorMessage
                }
            }
        }
    }

    fun refreshFileTree() {
        viewModelScope.launch {
            when (val res = app.safProjectManager.listFiles("", recursive = true, includeIgnored = false)) {
                is FileOpResult.Success -> _projectFiles.value = res.data
                is FileOpResult.Error -> _statusBanner.value = res.errorMessage
            }
        }
    }

    fun openFileInEditor(relativePath: String) {
        viewModelScope.launch {
            when (val res = app.safProjectManager.readFile(relativePath, preferences.value.maxFileReadBytes)) {
                is FileOpResult.Success -> {
                    _selectedFilePath.value = relativePath
                    _selectedFileContent.value = res.data
                    val projId = _currentProject.value?.id
                    if (projId != null) {
                        val backup = app.database.dao().getLatestBackupForFile(projId, relativePath)
                        _selectedFileDiff.value = if (backup != null) {
                            DiffEngine.computeUnifiedDiff(relativePath, backup.originalContent, res.data)
                        } else {
                            "No backup history yet for $relativePath"
                        }
                    }
                }
                is FileOpResult.Error -> _statusBanner.value = res.errorMessage
            }
        }
    }

    fun saveEditorFile(relativePath: String, newContent: String) {
        val proj = _currentProject.value ?: return
        viewModelScope.launch {
            app.backupManager.createBackupForFile(proj.id, null, relativePath, "Manual editor save")
            when (val res = app.safProjectManager.writeFile(relativePath, newContent)) {
                is FileOpResult.Success -> {
                    _selectedFileContent.value = newContent
                    openFileInEditor(relativePath)
                    _statusBanner.value = "Saved '$relativePath'"
                }
                is FileOpResult.Error -> _statusBanner.value = res.errorMessage
            }
        }
    }

    fun createNewProjectItem(relativePath: String, isDirectory: Boolean, initialContent: String = "") {
        viewModelScope.launch {
            val res = if (isDirectory) {
                app.safProjectManager.createDirectory(relativePath)
            } else {
                app.safProjectManager.createFile(relativePath, initialContent, overwrite = false)
            }
            when (res) {
                is FileOpResult.Success -> {
                    refreshFileTree()
                    _statusBanner.value = res.message
                }
                is FileOpResult.Error -> _statusBanner.value = res.errorMessage
            }
        }
    }

    fun renameProjectItem(relativePath: String, newName: String) {
        viewModelScope.launch {
            when (val res = app.safProjectManager.renameFile(relativePath, newName)) {
                is FileOpResult.Success -> {
                    refreshFileTree()
                    _statusBanner.value = res.message
                }
                is FileOpResult.Error -> _statusBanner.value = res.errorMessage
            }
        }
    }

    fun deleteProjectItem(relativePath: String) {
        val proj = _currentProject.value ?: return
        viewModelScope.launch {
            app.backupManager.createBackupForFile(proj.id, null, relativePath, "Pre-delete backup")
            when (val res = app.safProjectManager.deleteFile(relativePath)) {
                is FileOpResult.Success -> {
                    if (_selectedFilePath.value == relativePath) {
                        _selectedFilePath.value = null
                        _selectedFileContent.value = ""
                    }
                    refreshFileTree()
                    _statusBanner.value = "Deleted '$relativePath' (Backup saved)"
                }
                is FileOpResult.Error -> _statusBanner.value = res.errorMessage
            }
        }
    }

    fun searchInProjectFiles(query: String) {
        viewModelScope.launch {
            when (val res = app.safProjectManager.searchInFiles(query)) {
                is FileOpResult.Success -> _searchMatches.value = res.data
                is FileOpResult.Error -> _statusBanner.value = res.errorMessage
            }
        }
    }

    fun undoLastChange() {
        val proj = _currentProject.value ?: return
        viewModelScope.launch {
            when (val res = app.backupManager.undoMostRecentChangeInProject(proj.id)) {
                is FileOpResult.Success -> {
                    refreshFileTree()
                    _statusBanner.value = "Restored '${res.data.relativePath}' from latest backup."
                }
                is FileOpResult.Error -> _statusBanner.value = res.errorMessage
            }
        }
    }

    fun restoreSpecificBackup(backupId: String) {
        viewModelScope.launch {
            when (val res = app.backupManager.restoreBackupById(backupId)) {
                is FileOpResult.Success -> {
                    refreshFileTree()
                    _statusBanner.value = "Restored '${res.data.relativePath}'."
                }
                is FileOpResult.Error -> _statusBanner.value = res.errorMessage
            }
        }
    }

    // Agent Task Controls
    fun startAgentTask(userPrompt: String) {
        val proj = _currentProject.value
        if (proj == null) {
            _statusBanner.value = "Please select a project folder first using 'Open Project'."
            return
        }
        if (userPrompt.isBlank()) return
        app.orchestrator.startTaskAsync(
            scope = viewModelScope,
            projectId = proj.id,
            userGoal = userPrompt.trim(),
            preferences = preferences.value
        )
    }

    fun pauseAgentTask() {
        val proj = _currentProject.value ?: return
        viewModelScope.launch {
            app.orchestrator.pauseActiveTask(proj.id)
        }
    }

    fun resumeAgentTask(approvePendingAction: Boolean = false) {
        val proj = _currentProject.value ?: return
        app.orchestrator.resumeTaskAsync(
            scope = viewModelScope,
            projectId = proj.id,
            preferences = preferences.value,
            approvePendingAction = approvePendingAction
        )
    }

    fun cancelAgentTask() {
        val proj = _currentProject.value ?: return
        viewModelScope.launch {
            app.orchestrator.cancelActiveTask(proj.id)
        }
    }

    fun clearProjectMemoryAndHistory() {
        val proj = _currentProject.value ?: return
        viewModelScope.launch {
            app.database.dao().clearChatMessagesForProject(proj.id)
            app.database.dao().clearExecutionLogsForProject(proj.id)
            app.database.dao().clearTaskSessionsForProject(proj.id)
            _statusBanner.value = "Cleared local conversation and task memory for '${proj.name}'."
        }
    }

    // Provider & Key Settings
    fun saveProviderApiKey(providerId: String, rawKey: String) {
        app.secretManager.saveApiKey(providerId, rawKey)
        app.modelRouter.clearInvalidKeyFlag(providerId)
        _statusBanner.value = "Saved encrypted API key for $providerId in Android Keystore."
    }

    fun deleteProviderApiKey(providerId: String) {
        app.secretManager.deleteApiKey(providerId)
        _statusBanner.value = "Removed API key for $providerId."
    }

    fun getMaskedKey(providerId: String): String? = app.secretManager.getMaskedApiKey(providerId)

    fun testAndDiscoverProvider(config: ProviderConfigEntity) {
        viewModelScope.launch {
            val provider = app.providersMap[config.providerId] ?: return@launch
            val apiKey = app.secretManager.getApiKey(config.providerId).orEmpty()
            val healthRes = provider.verifyConnectionAndKey(apiKey)
            healthRes.fold(
                onSuccess = { health ->
                    val models = provider.discoverModels(apiKey).getOrDefault(emptyList())
                    if (models.isNotEmpty()) {
                        app.modelRouter.registerCatalog(config.providerId, models)
                        _providerCatalogs.value = _providerCatalogs.value.toMutableMap().apply {
                            put(config.providerId, models)
                        }
                    }
                    app.database.dao().upsertProviderConfig(
                        config.copy(
                            lastCatalogSyncEpochMs = System.currentTimeMillis(),
                            lastKnownStatus = health.statusMessage,
                            lastQuotaInfo = health.quotaSummary
                        )
                    )
                    _statusBanner.value = "${config.displayName}: ${health.statusMessage}"
                },
                onFailure = { err ->
                    val cleanMsg = app.secretManager.redactSecrets(err.message ?: "Connection failed", app.providersMap.keys)
                    app.database.dao().upsertProviderConfig(
                        config.copy(lastKnownStatus = "Error: $cleanMsg")
                    )
                    _statusBanner.value = "${config.displayName}: $cleanMsg"
                }
            )
        }
    }

    fun updateProviderConfig(updated: ProviderConfigEntity) {
        viewModelScope.launch {
            app.database.dao().upsertProviderConfig(updated)
        }
    }

    fun toggleTheme(isDark: Boolean) {
        viewModelScope.launch { app.preferencesRepository.setDarkTheme(isDark) }
    }

    fun toggleLanguage(lang: String) {
        viewModelScope.launch { app.preferencesRepository.setAppLanguage(lang) }
    }

    fun setAutoExecutionMode(enabled: Boolean) {
        viewModelScope.launch { app.preferencesRepository.setAutoExecutionMode(enabled) }
    }

    fun setRoutingMode(mode: RoutingMode) {
        viewModelScope.launch { app.preferencesRepository.setRoutingMode(mode) }
    }

    fun setManualModel(providerId: String, modelId: String) {
        viewModelScope.launch { app.preferencesRepository.setManualSelection(providerId, modelId) }
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    class Factory(private val app: AgentForgeApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(app) as T
        }
    }
}
