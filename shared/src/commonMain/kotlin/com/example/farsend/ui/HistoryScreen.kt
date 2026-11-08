package com.example.farsend.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.farsend.AppGraph
import com.example.farsend.AppInfo
import com.example.farsend.data.PairingLogEntry
import com.example.farsend.data.PairingLogKind
import com.example.farsend.data.TransferItem
import com.example.farsend.data.TransferKind
import com.example.farsend.data.TransferState
import com.example.farsend.log.FsLog
import com.example.farsend.platform.formatDateTime
import com.example.farsend.resources.Res
import com.example.farsend.resources.ic_call_made
import com.example.farsend.resources.ic_call_received
import com.example.farsend.resources.ic_cancel
import com.example.farsend.resources.ic_check_circle
import com.example.farsend.resources.ic_delete_sweep
import com.example.farsend.resources.ic_error_outline
import com.example.farsend.resources.ic_hourglass_empty
import com.example.farsend.resources.ic_link_off
import com.example.farsend.text.FsText
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

private const val TAG = "${AppInfo.NAME}/History"

private const val TAB_TRANSFERS = 0
private const val TAB_PAIRING = 1

/**
 * History screen. A PrimaryTabRow switches "Transfers | Pairing".
 * Opened from the history icon at the right of the Device Hub top bar.
 * Both histories are saved (survive restarts, no limit). "Clear all" in the top bar (with confirmation)
 * clears only the current tab; the Transfers tab keeps running rows. Android still posts system notifications.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(graph: AppGraph, onBack: () -> Unit) {
    val transfers by graph.transfers.items.collectAsState()
    val pairingLog by graph.pairing.history.entries.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(TAB_TRANSFERS) }
    var confirmClear by remember { mutableStateOf(false) }

    PlatformBackHandler(onBack)

    val canClear = when (tab) {
        TAB_TRANSFERS -> transfers.any { it.state != TransferState.Running }
        else -> pairingLog.isNotEmpty()
    }

    Scaffold(
        topBar = {
            Column {
                DetailTopBar(
                    title = FsText.history,
                    onBack = onBack,
                    actions = {
                        TooltipIconButton(
                            "Clear all", vectorResource(Res.drawable.ic_delete_sweep), // delete_sweep, matching the Logs screen
                            enabled = canClear,
                            onClick = { confirmClear = true },
                        )
                    },
                )
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(
                        selected = tab == TAB_TRANSFERS,
                        onClick = { tab = TAB_TRANSFERS; FsLog.d(TAG, "tab transfers") },
                        text = { Text("Transfers") },
                    )
                    Tab(
                        selected = tab == TAB_PAIRING,
                        onClick = { tab = TAB_PAIRING; FsLog.d(TAG, "tab pairing") },
                        text = { Text("Pairing") },
                    )
                }
            }
        },
    ) { pad ->
        when (tab) {
            TAB_TRANSFERS -> if (transfers.isEmpty()) {
                EmptyHistory("No transfers yet.", Modifier.padding(pad))
            } else {
                LazyColumn(
                    Modifier.padding(pad).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(transfers, key = { it.id }) { item ->
                        TransferRow(item, onCancel = {
                            FsLog.d(TAG, "cancel #${item.id}")
                            graph.transfers.cancel(item.id)
                        })
                    }
                }
            }
            else -> if (pairingLog.isEmpty()) {
                EmptyHistory("No pairing history yet.", Modifier.padding(pad))
            } else {
                LazyColumn(
                    Modifier.padding(pad).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(pairingLog, key = { it.id }) { PairingLogRow(it) }
                }
            }
        }
    }

    if (confirmClear) {
        val isTransfers = tab == TAB_TRANSFERS
        ConfirmDialog(
            title = if (isTransfers) "Clear all transfer history?" else "Clear all pairing history?",
            text = if (isTransfers) {
                "Transfers in progress are kept. Saved files are not deleted. This can't be undone."
            } else {
                "Paired devices are not removed. This can't be undone."
            },
            confirmLabel = "Clear",
            onConfirm = {
                if (isTransfers) {
                    FsLog.i(TAG, "clear transfer history (${transfers.size} rows)")
                    graph.transfers.clearFinished()
                } else {
                    FsLog.i(TAG, "clear pairing history (${pairingLog.size} rows)")
                    graph.pairing.history.clear()
                }
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun EmptyHistory(text: String, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun pairingLabel(kind: PairingLogKind): Pair<String, DrawableResource> = when (kind) {
    PairingLogKind.RequestSent -> "Pairing requested" to Res.drawable.ic_call_made
    PairingLogKind.SentAccepted -> "Request allowed" to Res.drawable.ic_check_circle
    PairingLogKind.SentRejected -> "Request declined" to Res.drawable.ic_cancel
    PairingLogKind.SentFailed -> "Couldn't send request" to Res.drawable.ic_error_outline
    PairingLogKind.RequestReceived -> "Pairing request received" to Res.drawable.ic_call_received
    PairingLogKind.ReceivedAccepted -> "You allowed the request" to Res.drawable.ic_check_circle
    PairingLogKind.ReceivedRejected -> "You cancelled the request" to Res.drawable.ic_cancel
    PairingLogKind.ReceivedTimedOut -> "No response (cancelled automatically)" to Res.drawable.ic_hourglass_empty
    PairingLogKind.UnpairedByMe -> "You unpaired" to Res.drawable.ic_link_off
    PairingLogKind.UnpairedByPeer -> "Other device unpaired" to Res.drawable.ic_link_off
}

@Composable
private fun PairingLogRow(e: PairingLogEntry) {
    val (label, icon) = pairingLabel(e.kind)
    val isError = e.kind == PairingLogKind.SentFailed
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                vectorResource(icon), contentDescription = null,
                Modifier.size(24.dp),
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall)
                MarqueeText("${e.peerName} (${e.shortId}…)", style = MaterialTheme.typography.bodySmall)
                e.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    formatDateTime(e.at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun TransferRow(item: TransferItem, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarqueeText(item.fileName, Modifier.weight(1f))
                if (item.state == TransferState.Running) {
                    TextButton(onClick = onCancel) { Text(FsText.cancel) }
                }
            }
            val kindLabel = FsText.transferKindLabel(item.kind)
            val retryHint = FsText.resumeHint(item.kind)
            when (item.state) {
                TransferState.Running -> {
                    if (item.indeterminate) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        MarqueeText(
                            FsText.waitingText(item.kind) + " · " + item.peerName,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        val p = if (item.total > 0) item.done.toFloat() / item.total else 0f
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        MarqueeText(
                            "$kindLabel · ${formatMb(item.done)} / ${formatMb(item.total)} · ${item.peerName}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                TransferState.Completed ->
                    MarqueeText("${FsText.transferDoneTitle(item.kind)} · ${item.peerName}", style = MaterialTheme.typography.bodySmall)
                TransferState.Failed ->
                    MarqueeText(
                        "$kindLabel failed: ${item.error ?: "?"} ($retryHint)",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error),
                    )
                TransferState.Cancelled ->
                    MarqueeText("$kindLabel cancelled ($retryHint)", style = MaterialTheme.typography.bodySmall)
            }
            // show the start time when the row has one (hand-edited history files may not)
            if (item.startedAt > 0) {
                Text(
                    formatDateTime(item.startedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
