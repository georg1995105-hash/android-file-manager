package io.personal.filemanager

import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.personal.filemanager.data.IncomingFiles
import io.personal.filemanager.data.IncomingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.personal.filemanager.ui.AppRoot
import io.personal.filemanager.ui.theme.FileManagerTheme

class MainActivity : ComponentActivity() {
    private var incomingPath by mutableStateOf<String?>(null)
    private var importJob: Job? = null

    private fun openIncoming(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_VIEW) return
        importJob?.cancel()
        importJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { IncomingFiles.import(this@MainActivity, incoming) }
            when (result) {
                is IncomingResult.Imported -> {
                    incomingPath = result.file.absolutePath
                    Toast.makeText(this@MainActivity,
                        "Открыта копия документа: исходный файл не изменяется.", Toast.LENGTH_LONG).show()
                }
                is IncomingResult.Error ->
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openIncoming(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            FileManagerTheme {
                AppRoot(incomingPath = incomingPath, onIncomingConsumed = { incomingPath = null })
            }
        }
        openIncoming(intent)
    }
}
