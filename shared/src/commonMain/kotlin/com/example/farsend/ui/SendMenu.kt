package com.example.farsend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import com.example.farsend.AppGraph
import com.example.farsend.AppInfo
import com.example.farsend.data.Peer
import com.example.farsend.log.FsLog
import com.example.farsend.text.FsText
import kotlinx.coroutines.launch

private const val TAG = "${AppInfo.NAME}/Send"

/**
 * "Send" to [peer]: like FlyingCarpet's "Send from:", choose the source first, then open the picker.
 * Choices: the platform's pickers ([UiHooks.sendOptions]) + Text + Clipboard (LocalSend's "Text" / "Paste").
 * moved out of the browse screen (removed with the shared folder) so Device Hub's peer rows open it.
 * [onDismiss] is called when the menu (and the text dialog, if opened) closes.
 */
@Composable
internal fun SendMenu(graph: AppGraph, hooks: UiHooks, peer: Peer, onDismiss: () -> Unit) {
    var showTextInput by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    if (showTextInput) {
        TextInputDialog(
            title = "Send text to ${peer.name}",
            initial = "",
            hint = "Text to send (multiple lines OK)",
            multiline = true,
            okLabel = FsText.send,
            onDismiss = onDismiss,
        ) { text ->
            FsLog.d(TAG, "send: text (${text.length} chars) -> ${peer.name}")
            graph.transfers.send(peer, emptyList(), listOf(text))
            onDismiss()
        }
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to ${peer.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose what to send.", style = MaterialTheme.typography.bodySmall)
                for (opt in hooks.sendOptions) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            FsLog.d(TAG, "send: picker ${opt.id}")
                            hooks.pickSendItems(opt) { pick ->
                                if (pick == null) {
                                    FsLog.d(TAG, "send: picker cancelled")
                                } else {
                                    FsLog.d(TAG, "send: ${pick.items.size} items -> ${peer.name}")
                                    graph.transfers.send(peer, pick.items, pick.onFinished)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(opt.label) }
                }
                OutlinedButton(
                    onClick = { showTextInput = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Text") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            // Read before dismissing: onDismiss disposes this composable and
                            // cancels this scope at the next suspension point. Everything
                            // after the read runs without suspending, so it completes.
                            val text = clipboard.readPlainText()
                            onDismiss()
                            if (!text.isNullOrEmpty()) {
                                FsLog.d(TAG, "send: clipboard text (${text.length} chars) -> ${peer.name}")
                                graph.transfers.send(peer, emptyList(), listOf(text))
                            } else {
                                val pick = runCatching { hooks.readClipboardImage() }
                                    .onFailure { FsLog.w(TAG, "clipboard image read failed: ${it.message ?: it}") }
                                    .getOrNull()
                                if (pick != null && pick.items.isNotEmpty()) {
                                    FsLog.d(TAG, "send: clipboard image -> ${peer.name}")
                                    graph.transfers.send(peer, pick.items, pick.onFinished)
                                } else {
                                    FsLog.d(TAG, "send: clipboard is empty")
                                    snackbar.show(FsText.clipboardEmpty)
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Clipboard") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(FsText.cancel) } },
    )
}
