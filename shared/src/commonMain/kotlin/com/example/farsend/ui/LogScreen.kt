package com.example.farsend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.farsend.log.LogLevel
import com.example.farsend.log.FsLog
import com.example.farsend.resources.Res
import com.example.farsend.resources.ic_content_copy
import com.example.farsend.resources.ic_delete_sweep
import com.example.farsend.text.FsText
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.vectorResource

/**
 * In-app log viewer (desktop and iOS). Replaces the old iOS LogViewerView.
 * For iOS devices without a Mac/Xcode console and for desktop, which has no Logcat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val entries by FsLog.entries.collectAsState()
    var minLevel by remember { mutableStateOf(LogLevel.DEBUG) }
    val clipboard = LocalClipboard.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    PlatformBackHandler(onBack)

    Scaffold(
        topBar = {
            DetailTopBar(
                title = FsText.logTitle,
                onBack = onBack,
                actions = {
                    // "Copy" and "Clear" became icon buttons (label shown as a tooltip on long press /
                    // hover). Copy uses content_copy like the device ID and confirms with a snackbar like the
                    // ID box. Clear uses delete like Unpair.
                    TooltipIconButton(FsText.copyLabel, vectorResource(Res.drawable.ic_content_copy), onClick = {
                        scope.launch {
                            clipboard.writePlainText(FsLog.snapshot(minLevel))
                            snackbar.show(FsText.copied)
                        }
                    })
                    TooltipIconButton("Clear", vectorResource(Res.drawable.ic_delete_sweep), onClick = { FsLog.clear() })
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = minLevel == level,
                        onClick = { minLevel = level },
                        label = { Text(level.label) },
                    )
                }
            }
            val visible = entries.filter { it.level.ordinal >= minLevel.ordinal }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(visible) { e ->
                    Text(
                        e.line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (e.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
