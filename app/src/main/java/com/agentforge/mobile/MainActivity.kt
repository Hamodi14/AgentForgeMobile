package com.agentforge.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.agentforge.mobile.ui.MainViewModel
import com.agentforge.mobile.ui.screens.AgentForgeRootScreen
import com.agentforge.mobile.ui.theme.AgentForgeTheme

/**
 * Main entry-point Activity for AgentForge Mobile.
 *
 * Registers the official Android Storage Access Framework directory picker
 * (`ActivityResultContracts.OpenDocumentTree` / `Intent.ACTION_OPEN_DOCUMENT_TREE`)
 * and binds persistent read/write access to the user-selected project folder.
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as AgentForgeApp
        viewModel = ViewModelProvider(this, MainViewModel.Factory(app))[MainViewModel::class.java]

        setContent {
            val prefs by viewModel.preferences.collectAsState()

            val openDocumentTreeLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { treeUri ->
                if (treeUri != null) {
                    viewModel.openProjectFromUri(treeUri, takePersistable = true)
                }
            }

            AgentForgeTheme(
                darkTheme = prefs.isDarkTheme,
                languageCode = prefs.appLanguage
            ) {
                AgentForgeRootScreen(
                    viewModel = viewModel,
                    onPickProjectDirectory = {
                        openDocumentTreeLauncher.launch(null)
                    }
                )
            }
        }
    }
}
