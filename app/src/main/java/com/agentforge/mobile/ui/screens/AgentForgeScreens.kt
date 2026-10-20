package com.agentforge.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agentforge.mobile.agent.OrchestratorUiState
import com.agentforge.mobile.project.ProjectFileNode
import com.agentforge.mobile.storage.AgentUserPreferences
import com.agentforge.mobile.storage.ChatMessageEntity
import com.agentforge.mobile.storage.ExecutionLogEntity
import com.agentforge.mobile.storage.FileBackupEntity
import com.agentforge.mobile.storage.ProjectEntity
import com.agentforge.mobile.storage.ProviderConfigEntity
import com.agentforge.mobile.storage.RoutingMode
import com.agentforge.mobile.storage.TaskRunStatus
import com.agentforge.mobile.ui.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MainNavTab {
    DASHBOARD,
    CHAT,
    EXPLORER,
    LOGS,
    PROVIDERS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentForgeRootScreen(
    viewModel: MainViewModel,
    onPickProjectDirectory: () -> Unit
) {
    val prefs by viewModel.preferences.collectAsState()
    val orchState by viewModel.orchestratorState.collectAsState()
    val currentProject by viewModel.currentProject.collectAsState()
    val banner by viewModel.statusBanner.collectAsState()

    var activeTab by remember { mutableStateOf(MainNavTab.DASHBOARD) }
    val isArabic = prefs.appLanguage == "ar"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isArabic) "AgentForge Mobile — وكيل البرمجة الذكي" else "AgentForge Mobile",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = currentProject?.name ?: if (isArabic) "لم يتم اختيار مشروع" else "No Project Selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleLanguage(if (isArabic) "en" else "ar") }) {
                        Icon(Icons.Default.Language, contentDescription = "Switch Language")
                    }
                    IconButton(onClick = { viewModel.toggleTheme(!prefs.isDarkTheme) }) {
                        Icon(Icons.Default.LightMode, contentDescription = "Toggle Theme")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = activeTab == MainNavTab.DASHBOARD,
                    onClick = { activeTab = MainNavTab.DASHBOARD },
                    icon = { Icon(Icons.Default.Build, contentDescription = null) },
                    label = { Text(if (isArabic) "الرئيسية" else "Dashboard") }
                )
                NavigationBarItem(
                    selected = activeTab == MainNavTab.CHAT,
                    onClick = { activeTab = MainNavTab.CHAT },
                    icon = { Icon(Icons.Default.Send, contentDescription = null) },
                    label = { Text(if (isArabic) "محادثة الوكيل" else "Agent Chat") }
                )
                NavigationBarItem(
                    selected = activeTab == MainNavTab.EXPLORER,
                    onClick = { activeTab = MainNavTab.EXPLORER },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text(if (isArabic) "المستكشف" else "Explorer") }
                )
                NavigationBarItem(
                    selected = activeTab == MainNavTab.LOGS,
                    onClick = { activeTab = MainNavTab.LOGS },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text(if (isArabic) "السجل" else "Logs") }
                )
                NavigationBarItem(
                    selected = activeTab == MainNavTab.PROVIDERS,
                    onClick = { activeTab = MainNavTab.PROVIDERS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(if (isArabic) "المزودون" else "Providers") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (banner != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clickable { viewModel.dismissBanner() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        text = banner.orEmpty(),
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // Destructive Action Approval Dialog
            val pendingApproval = orchState.pendingApproval
            if (pendingApproval != null) {
                AlertDialog(
                    onDismissRequest = {},
                    icon = { Icon(Icons.Default.Warning, contentDescription = null) },
                    title = {
                        Text(if (isArabic) "تأكيد عملية حساسة" else "Confirm Sensitive Operation")
                    },
                    text = {
                        Text(pendingApproval.promptMessage)
                    },
                    confirmButton = {
                        Button(onClick = { viewModel.resumeAgentTask(approvePendingAction = true) }) {
                            Text(if (isArabic) "موافقة ومتابعة" else "Approve & Continue")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { viewModel.cancelAgentTask() }) {
                            Text(if (isArabic) "رفض وإيقاف" else "Reject & Stop")
                        }
                    }
                )
            }

            when (activeTab) {
                MainNavTab.DASHBOARD -> DashboardScreen(
                    isArabic = isArabic,
                    project = currentProject,
                    orchState = orchState,
                    prefs = prefs,
                    onPickProject = onPickProjectDirectory,
                    onOpenChat = { activeTab = MainNavTab.CHAT },
                    onStartQuickTask = { prompt ->
                        viewModel.startAgentTask(prompt)
                        activeTab = MainNavTab.CHAT
                    },
                    onPause = { viewModel.pauseAgentTask() },
                    onResume = { viewModel.resumeAgentTask(false) },
                    onCancel = { viewModel.cancelAgentTask() },
                    onUndoLast = { viewModel.undoLastChange() },
                    onToggleAutoMode = { viewModel.setAutoExecutionMode(it) },
                    onSelectRoutingMode = { viewModel.setRoutingMode(it) }
                )

                MainNavTab.CHAT -> AgentChatScreen(
                    isArabic = isArabic,
                    orchState = orchState,
                    messages = viewModel.chatMessages.collectAsState().value,
                    onSendPrompt = { viewModel.startAgentTask(it) },
                    onPause = { viewModel.pauseAgentTask() },
                    onResume = { viewModel.resumeAgentTask(false) },
                    onCancel = { viewModel.cancelAgentTask() },
                    onClearHistory = { viewModel.clearProjectMemoryAndHistory() }
                )

                MainNavTab.EXPLORER -> ProjectExplorerScreen(
                    isArabic = isArabic,
                    viewModel = viewModel,
                    onPickProject = onPickProjectDirectory
                )

                MainNavTab.LOGS -> ExecutionLogsAndBackupsScreen(
                    isArabic = isArabic,
                    logs = viewModel.executionLogs.collectAsState().value,
                    backups = viewModel.fileBackups.collectAsState().value,
                    onRestoreBackup = { viewModel.restoreSpecificBackup(it) }
                )

                MainNavTab.PROVIDERS -> ProvidersSettingsScreen(
                    isArabic = isArabic,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
fun DashboardScreen(
    isArabic: Boolean,
    project: ProjectEntity?,
    orchState: OrchestratorUiState,
    prefs: AgentUserPreferences,
    onPickProject: () -> Unit,
    onOpenChat: () -> Unit,
    onStartQuickTask: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onUndoLast: () -> Unit,
    onToggleAutoMode: (Boolean) -> Unit,
    onSelectRoutingMode: (RoutingMode) -> Unit
) {
    var quickTaskText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Project Card
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isArabic) "المشروع الحالي" else "Current Project",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = project?.name ?: if (isArabic) "لم يتم فتح مجلد مشروع بعد" else "No Project Folder Opened",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (project != null) {
                            Text(
                                text = project.treeUriString,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${project.detectedLanguage} • ${project.detectedFramework}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Button(onClick = onPickProject) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (isArabic) "فتح مشروع" else "Open Project")
                    }
                }
            }
        }

        // 2. Active Model & Task Status Card
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isArabic) "حالة المهمة والوكيل" else "Task & Agent Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(localizeTaskStatus(orchState.status, isArabic))
                        }
                    )
                }

                Text(
                    text = if (isArabic) {
                        "المزود والنموذج النشط: ${orchState.activeProviderId.ifEmpty { prefs.manualProviderId }} / ${orchState.activeModelId.ifEmpty { prefs.manualModelId }}"
                    } else {
                        "Active Provider & Model: ${orchState.activeProviderId.ifEmpty { prefs.manualProviderId }} / ${orchState.activeModelId.ifEmpty { prefs.manualModelId }}"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )

                Text(
                    text = if (isArabic) {
                        "حالة الشبكة: ${if (orchState.isNetworkAvailable) "متصل" else "انقطاع اتصال (تم حفظ نقطة التوقف)"}"
                    } else {
                        "Network: ${if (orchState.isNetworkAvailable) "Connected" else "Offline (Checkpoint Saved)"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (orchState.isNetworkAvailable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                )

                // Step-grounded progress bar
                LinearProgressIndicator(
                    progress = { orchState.progressFraction },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isArabic) {
                            "الخطوات المكتملة: ${orchState.completedSteps} / ${orchState.totalPlannedSteps} (${(orchState.progressFraction * 100).toInt()}%)"
                        } else {
                            "Steps Completed: ${orchState.completedSteps} / ${orchState.totalPlannedSteps} (${(orchState.progressFraction * 100).toInt()}%)"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = if (isArabic) {
                            "الملفات المعدلة: ${orchState.modifiedFiles.size}"
                        } else {
                            "Modified Files: ${orchState.modifiedFiles.size}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = if (isArabic) "آخر عملية: ${orchState.lastOperationSummary}" else "Last Operation: ${orchState.lastOperationSummary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Task execution action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenChat,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isArabic) "محادثة الوكيل" else "Open Chat")
                    }
                    FilledTonalButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (isArabic) "إيقاف مؤقت" else "Pause")
                    }
                    FilledTonalButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (isArabic) "استئناف" else "Resume")
                    }
                    OutlinedButton(onClick = onCancel) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                    }
                }
            }
        }

        // 3. Autonomous Execution & Free-Only Routing Controls
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isArabic) "وضع التنفيذ التلقائي (Auto Mode)" else "Auto Execution Mode",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isArabic) {
                                "ينفذ القراءة والتعديل وإنشاء الملفات تلقائيًا، ويطلب التأكيد فقط قبل الحذف أو الأوامر الخطرة."
                            } else {
                                "Executes reads, edits, and file creation automatically; asks confirmation before deletions or destructive commands."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.autoExecutionMode,
                        onCheckedChange = onToggleAutoMode
                    )
                }

                Text(
                    text = if (isArabic) "سياسة اختيار النماذج:" else "Model Routing Policy:",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = prefs.routingMode == RoutingMode.FREE_ONLY,
                        onClick = { onSelectRoutingMode(RoutingMode.FREE_ONLY) },
                        label = { Text(if (isArabic) "مجاني فقط (الافتراضي)" else "Free-Only (Default)") }
                    )
                    FilterChip(
                        selected = prefs.routingMode == RoutingMode.CUSTOM_PRIORITY,
                        onClick = { onSelectRoutingMode(RoutingMode.CUSTOM_PRIORITY) },
                        label = { Text(if (isArabic) "أولوية مخصصة" else "Custom Priority") }
                    )
                    FilterChip(
                        selected = prefs.routingMode == RoutingMode.MANUAL,
                        onClick = { onSelectRoutingMode(RoutingMode.MANUAL) },
                        label = { Text(if (isArabic) "اختيار يدوي" else "Manual") }
                    )
                }

                OutlinedButton(
                    onClick = onUndoLast,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Undo, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isArabic) "تراجع عن آخر تعديل (استرجاع النسخة الاحتياطية)" else "Undo Last File Change (Restore Backup)")
                }
            }
        }

        // 4. Quick Task Launcher
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isArabic) "بدء مهمة برمجية جديدة" else "Start New Coding Task",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = quickTaskText,
                    onValueChange = { quickTaskText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    placeholder = {
                        Text(
                            if (isArabic) {
                                "مثال: افحص هذا المشروع بالكامل، واعرف سبب فشل تسجيل الدخول، وأصلح المشكلة، ثم اختبر التدفق وأخبرني بالملفات التي عدّلتها..."
                            } else {
                                "Example: Inspect this project, find why login fails, fix the bug, and list modified files..."
                            }
                        )
                    }
                )
                Button(
                    onClick = {
                        if (quickTaskText.isNotBlank()) {
                            onStartQuickTask(quickTaskText)
                            quickTaskText = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isArabic) "بدء تنفيذ المهمة على ملفات المشروع" else "Start Autonomous Execution")
                }
            }
        }
    }
}

@Composable
fun AgentChatScreen(
    isArabic: Boolean,
    orchState: OrchestratorUiState,
    messages: List<ChatMessageEntity>,
    onSendPrompt: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onClearHistory: () -> Unit
) {
    var inputPrompt by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Top task bar inside chat
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${localizeTaskStatus(orchState.status, isArabic)} • ${orchState.activeProviderId.ifEmpty { "Router" }}/${orchState.activeModelId.ifEmpty { "Auto" }}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = orchState.lastOperationSummary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row {
                    IconButton(onClick = onPause) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause")
                    }
                    IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                    }
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Stop, contentDescription = "Cancel")
                    }
                    IconButton(onClick = onClearHistory) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Memory")
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                val containerColor = when (msg.role) {
                    "user" -> MaterialTheme.colorScheme.primaryContainer
                    "tool" -> MaterialTheme.colorScheme.surfaceVariant
                    "system_event" -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surface
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = containerColor)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = when (msg.role) {
                                    "user" -> if (isArabic) "أنت" else "You"
                                    "tool" -> if (isArabic) "أداة: ${msg.toolName.orEmpty()}" else "Tool: ${msg.toolName.orEmpty()}"
                                    "system_event" -> if (isArabic) "مدير النماذج / النظام" else "System / Model Router"
                                    else -> "AgentForge (${msg.providerId.orEmpty()}/${msg.modelId.orEmpty()})"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = formatTime(msg.timestampEpochMs),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = msg.content,
                            style = if (msg.role == "tool") {
                                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            } else {
                                MaterialTheme.typography.bodyMedium
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputPrompt,
                onValueChange = { inputPrompt = it },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                placeholder = {
                    Text(if (isArabic) "اطلب من الوكيل تنفيذ مهمة برمجية في المشروع..." else "Ask the agent to execute a coding task...")
                }
            )
            Button(
                onClick = {
                    if (inputPrompt.isNotBlank()) {
                        onSendPrompt(inputPrompt)
                        inputPrompt = ""
                    }
                }
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
fun ProjectExplorerScreen(
    isArabic: Boolean,
    viewModel: MainViewModel,
    onPickProject: () -> Unit
) {
    val files by viewModel.projectFiles.collectAsState()
    val selectedPath by viewModel.selectedFilePath.collectAsState()
    val selectedContent by viewModel.selectedFileContent.collectAsState()
    val selectedDiff by viewModel.selectedFileDiff.collectAsState()
    val searchMatches by viewModel.searchMatches.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var newFileName by remember { mutableStateOf("") }
    var showDiffTab by remember { mutableStateOf(false) }
    var editableText by remember(selectedPath, selectedContent) { mutableStateOf(selectedContent) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onPickProject) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (isArabic) "فتح مشروع" else "Open Project")
            }
            OutlinedButton(onClick = { viewModel.refreshFileTree() }) {
                Icon(Icons.Default.Refresh, contentDescription = null)
            }
            OutlinedTextField(
                value = newFileName,
                onValueChange = { newFileName = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(if (isArabic) "مسار ملف/مجلد جديد..." else "New file/folder path...") }
            )
            IconButton(
                onClick = {
                    if (newFileName.isNotBlank()) {
                        viewModel.createNewProjectItem(newFileName.trim(), isDirectory = false)
                        newFileName = ""
                    }
                }
            ) {
                Icon(Icons.Default.Description, contentDescription = "Create File")
            }
            IconButton(
                onClick = {
                    if (newFileName.isNotBlank()) {
                        viewModel.createNewProjectItem(newFileName.trim(), isDirectory = true)
                        newFileName = ""
                    }
                }
            ) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "Create Folder")
            }
        }

        // Search inside project files
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(if (isArabic) "بحث نصي داخل ملفات المشروع..." else "Search inside project files...") }
            )
            FilledTonalButton(onClick = { viewModel.searchInProjectFiles(searchQuery) }) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(if (isArabic) "بحث" else "Search")
            }
        }

        if (searchMatches.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(searchMatches) { match ->
                        Text(
                            text = "${match.relativePath}:${match.lineNumber} — ${match.linePreview}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.openFileInEditor(match.relativePath) }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        // Split view: Tree list + Editor/Diff viewer
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // File tree column
            Card(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxSize()
            ) {
                LazyColumn(modifier = Modifier.padding(8.dp)) {
                    items(files, key = { it.relativePath }) { node ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!node.isDirectory) {
                                        viewModel.openFileInEditor(node.relativePath)
                                    }
                                }
                                .padding(
                                    start = (node.depth * 10).dp,
                                    top = 6.dp,
                                    bottom = 6.dp,
                                    end = 4.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (node.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (node.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = node.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // File Editor & Diff viewer column
            Card(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxSize()
            ) {
                if (selectedPath == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isArabic) "اختر ملفًا لعرضه أو تعديله" else "Select a file to view, edit, or inspect diff",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedPath.orEmpty(),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = !showDiffTab,
                                    onClick = { showDiffTab = false },
                                    label = { Text(if (isArabic) "المحرر" else "Editor") }
                                )
                                FilterChip(
                                    selected = showDiffTab,
                                    onClick = { showDiffTab = true },
                                    label = { Text("Diff") }
                                )
                            }
                        }

                        if (!showDiffTab) {
                            OutlinedTextField(
                                value = editableText,
                                onValueChange = { editableText = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { viewModel.saveEditorFile(selectedPath.orEmpty(), editableText) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (isArabic) "حفظ في الملف الأصلي" else "Save to Original File")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.deleteProjectItem(selectedPath.orEmpty()) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = selectedDiff,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExecutionLogsAndBackupsScreen(
    isArabic: Boolean,
    logs: List<ExecutionLogEntity>,
    backups: List<FileBackupEntity>,
    onRestoreBackup: (String) -> Unit
) {
    var showBackups by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !showBackups,
                onClick = { showBackups = false },
                label = { Text(if (isArabic) "سجل العمليات (${logs.size})" else "Execution Logs (${logs.size})") }
            )
            FilterChip(
                selected = showBackups,
                onClick = { showBackups = true },
                label = { Text(if (isArabic) "النسخ الاحتياطية (${backups.size})" else "File Backups (${backups.size})") }
            )
        }

        if (!showBackups) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs, key = { it.id }) { log ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (log.isFailoverEvent) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else if (log.isSuccess) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            }
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "#${log.stepIndex} • ${log.toolName} ${log.targetFilePath?.let { "($it)" }.orEmpty()}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (log.isSuccess) "SUCCESS" else "FAILED",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "Model: ${log.providerId} / ${log.modelId} • ${formatTime(log.startedAtEpochMs)} → ${formatTime(log.completedAtEpochMs)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = log.summaryMessage,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            if (!log.errorDetail.isNullOrBlank()) {
                                Text(
                                    text = "Error: ${log.errorDetail}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(backups, key = { it.id }) { backup ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = backup.relativePath,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${backup.backupReason} • ${formatTime(backup.createdAtEpochMs)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            FilledTonalButton(onClick = { onRestoreBackup(backup.id) }) {
                                Icon(Icons.Default.Undo, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(if (isArabic) "استرجاع" else "Restore")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProvidersSettingsScreen(
    isArabic: Boolean,
    viewModel: MainViewModel
) {
    val configs by viewModel.providerConfigs.collectAsState()
    val catalogs by viewModel.providerCatalogs.collectAsState()
    val prefs by viewModel.preferences.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Security, contentDescription = null)
                    Column {
                        Text(
                            text = if (isArabic) "حماية المفاتيح عبر Android Keystore" else "Android Keystore Encryption",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isArabic) {
                                "تُشفر مفاتيح API محليًا بمعيار AES-256-GCM ولا تُحفظ في الكود أو السجلات. تنبيه أمني: التشفير يحمي المفاتيح داخل بيئة أندرويد القياسية، لكنه لا يمنع استخراجها إذا كان الهاتف مخترقًا بصلاحيات Root."
                            } else {
                                "API keys are encrypted with AES-256-GCM in Android Keystore and redacted from logs. Note: Keystore protects app storage on standard devices, but cannot guarantee protection against kernel/root memory inspection on a compromised device."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        items(configs, key = { it.providerId }) { config ->
            var keyInput by remember { mutableStateOf("") }
            val masked = viewModel.getMaskedKey(config.providerId)
            val models = catalogs[config.providerId].orEmpty()

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${config.priorityOrder}. ${config.displayName}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = config.baseUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.isEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.updateProviderConfig(config.copy(isEnabled = enabled))
                            }
                        )
                    }

                    Text(
                        text = if (isArabic) {
                            "الحالة: ${config.lastKnownStatus} • الحصة: ${config.lastQuotaInfo}"
                        } else {
                            "Status: ${config.lastKnownStatus} • Quota: ${config.lastQuotaInfo}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Text(
                        text = if (masked != null) {
                            if (isArabic) "المفتاح المحفوظ: $masked" else "Saved Key: $masked"
                        } else {
                            if (isArabic) "لم يتم إدخال مفتاح API بعد" else "No API Key Saved"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            placeholder = {
                                Text(if (isArabic) "أدخل أو حدّث API Key..." else "Enter or update API Key...")
                            }
                        )
                        Button(
                            onClick = {
                                if (keyInput.isNotBlank()) {
                                    viewModel.saveProviderApiKey(config.providerId, keyInput)
                                    keyInput = ""
                                }
                            }
                        ) {
                            Text(if (isArabic) "حفظ" else "Save")
                        }
                        if (masked != null) {
                            OutlinedButton(onClick = { viewModel.deleteProviderApiKey(config.providerId) }) {
                                Text(if (isArabic) "حذف" else "Delete")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.testAndDiscoverProvider(config) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (isArabic) "اختبار الاتصال واكتشاف النماذج" else "Test Connection & Discover Models")
                        }
                    }

                    if (models.isNotEmpty()) {
                        Text(
                            text = if (isArabic) {
                                "النماذج المكتشفة (${models.count { it.isFreeTier }} مجانية من أصل ${models.size}):"
                            } else {
                                "Discovered Models (${models.count { it.isFreeTier }} Free / ${models.size} Total):"
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                        models.take(5).forEach { m ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.updateProviderConfig(config.copy(preferredCodingModelId = m.modelId))
                                        if (prefs.routingMode == RoutingMode.MANUAL) {
                                            viewModel.setManualModel(config.providerId, m.modelId)
                                        }
                                    }
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${if (m.modelId == config.preferredCodingModelId) "★ " else ""}${m.displayName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (m.modelId == config.preferredCodingModelId) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = "${if (m.isFreeTier) "FREE" else "PAID"} • ${if (m.supportsNativeToolCalling) "Tools: Native" else "Tools: JSON"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (m.isFreeTier) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun localizeTaskStatus(status: TaskRunStatus, isArabic: Boolean): String = when (status) {
    TaskRunStatus.IDLE -> if (isArabic) "متوقف" else "Idle"
    TaskRunStatus.ANALYZING -> if (isArabic) "قيد التحليل" else "Analyzing"
    TaskRunStatus.EXECUTING -> if (isArabic) "ينفذ" else "Executing"
    TaskRunStatus.TESTING -> if (isArabic) "يختبر" else "Testing"
    TaskRunStatus.AWAITING_APPROVAL -> if (isArabic) "ينتظر موافقة" else "Awaiting Approval"
    TaskRunStatus.PAUSED -> if (isArabic) "متوقف مؤقتًا" else "Paused"
    TaskRunStatus.COMPLETED -> if (isArabic) "مكتمل" else "Completed"
    TaskRunStatus.FAILED -> if (isArabic) "متوقف لعائق" else "Blocked"
}

private fun formatTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
}
