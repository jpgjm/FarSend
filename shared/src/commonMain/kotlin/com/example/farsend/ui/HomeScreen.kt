package com.example.farsend.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.farsend.AppGraph
import com.example.farsend.AppInfo
import com.example.farsend.data.PairingCode
import com.example.farsend.data.Peer
import com.example.farsend.data.PeerPresence
import com.example.farsend.data.Presence
import com.example.farsend.log.FsLog
import com.example.farsend.net.IrohNode
import com.example.farsend.resources.Res
import com.example.farsend.resources.ic_add
import com.example.farsend.resources.ic_change_circle
import com.example.farsend.resources.ic_chevron_right
import com.example.farsend.resources.ic_close
import com.example.farsend.resources.ic_content_copy
import com.example.farsend.resources.ic_device_hub
import com.example.farsend.resources.ic_edit
import com.example.farsend.resources.ic_folder_open
import com.example.farsend.resources.ic_history
import com.example.farsend.resources.ic_keyboard
import com.example.farsend.resources.ic_link_off
import com.example.farsend.resources.ic_lock_reset
import com.example.farsend.resources.ic_published_with_changes
import com.example.farsend.resources.ic_qr_code
import com.example.farsend.resources.ic_qr_code_scanner
import com.example.farsend.resources.ic_refresh
import com.example.farsend.resources.ic_restart_alt
import com.example.farsend.resources.ic_send
import com.example.farsend.resources.ic_settings
import com.example.farsend.resources.ic_share
import com.example.farsend.resources.ic_sync_problem
import com.example.farsend.text.FsText
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.vectorResource

private const val TAG = "${AppInfo.NAME}/Home"

/** Navigation bar tabs. Device Hub is the default. */
private const val TAB_SETTINGS = "settings"
private const val TAB_DEVICE_HUB = "deviceHub"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    graph: AppGraph,
    hooks: UiHooks,
    onOpenLogs: () -> Unit,
    /** Opens the history screen (Transfers | Pairing). */
    onOpenTransfers: () -> Unit,
) {
    val settings = graph.settings
    val node = graph.node

    val status by node.status.collectAsState()
    val peers by settings.peers.collectAsState()
    val relay by settings.relayUrl.collectAsState()
    // the ID changes on "Reset device ID" and the name can be changed in Settings
    val endpointId by settings.endpointIdFlow.collectAsState()
    val customName by settings.customDeviceName.collectAsState()
    val deviceName = customName.ifBlank { settings.defaultDeviceName }
    val alwaysListen by settings.alwaysListen.collectAsState()
    val autoAccept by settings.autoAcceptIncoming.collectAsState()
    val clipboard = LocalClipboard.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    // copy the device ID (tapping the ID box)
    val copyEndpointId: () -> Unit = {
        FsLog.d(TAG, "copy endpoint id")
        scope.launch {
            clipboard.writePlainText(endpointId)
            snackbar.show(FsText.copied)
        }
    }

    var tab by rememberSaveable { mutableStateOf(TAB_DEVICE_HUB) }
    var showQr by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var editRelay by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(false) }

    // instead of one screen with everything, a navigation bar switches Settings / Device Hub.
    // Device Hub is the default. History and log screens (detail screens) are outside this Scaffold, so no bar.
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(hooks.appTitle) },
                actions = {
                    // entry to the History (transfers / pairing) screen
                    if (tab == TAB_DEVICE_HUB) {
                        TooltipIconButton(FsText.history, vectorResource(Res.drawable.ic_history), onClick = {
                            FsLog.d(TAG, "open transfers from Device Hub")
                            onOpenTransfers()
                        })
                    }
                },
            )
        },
        bottomBar = {
            // swapped to Device Hub left / Settings right. The bar is a snackbar anchor
            NavigationBar(Modifier.snackbarAnchor()) {
                NavigationBarItem(
                    selected = tab == TAB_DEVICE_HUB,
                    onClick = {
                        FsLog.d(TAG, "tab -> Device Hub")
                        tab = TAB_DEVICE_HUB
                    },
                    icon = { Icon(vectorResource(Res.drawable.ic_device_hub), contentDescription = null) },
                    label = { Text("Device Hub") },
                )
                NavigationBarItem(
                    selected = tab == TAB_SETTINGS,
                    onClick = {
                        FsLog.d(TAG, "tab -> Settings")
                        tab = TAB_SETTINGS
                    },
                    icon = { Icon(vectorResource(Res.drawable.ic_settings), contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
        floatingActionButton = {
            // "Add by QR code" and "Enter manually" moved from the header icons to the FAB menu
            if (tab == TAB_DEVICE_HUB) {
                AddPeerFabMenu(
                    canScanQr = hooks.canScanQr,
                    onScanQr = {
                        hooks.scanQr(
                            onResult = { text ->
                                if (text != null) {
                                    showAdd = PairingCode.decode(text) ?: (text to null)
                                }
                            },
                            onMessage = { snackbar.show(it) },
                        )
                    },
                    onManual = { showAdd = "" to null },
                )
            }
        },
    ) { pad ->
        when (tab) {
            TAB_DEVICE_HUB -> DeviceHubTab(
                graph = graph,
                hooks = hooks,
                modifier = Modifier.padding(pad),
                status = status,
                peers = peers,
                endpointId = endpointId,
                deviceName = deviceName,
                onRestart = { node.requestRestart() },
                onCopyId = copyEndpointId,
                onShowQr = { showQr = true },
            )
            else -> SettingsTab(
                graph = graph,
                hooks = hooks,
                modifier = Modifier.padding(pad),
                status = status,
                relay = relay,
                deviceName = deviceName,
                onEditName = { editName = true },
                alwaysListen = alwaysListen,
                autoAccept = autoAccept,
                onEditRelay = { editRelay = true },
                onOpenLogs = onOpenLogs,
            )
        }
    }

    if (showQr) {
        val code = PairingCode.encode(endpointId, deviceName)
        AlertDialog(
            onDismissRequest = { showQr = false },
            confirmButton = { TextButton(onClick = { showQr = false }) { Text(FsText.close) } },
            title = { Text("This device's QR code") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = rememberQrCodePainter(code),
                        contentDescription = "QR",
                        modifier = Modifier.size(280.dp).background(Color.White).padding(12.dp),
                    )
                }
            },
        )
    }

    showAdd?.let { (initialId, initialName) ->
        AddPeerDialog(
            initialId = initialId,
            initialName = initialName ?: "",
            selfId = endpointId,
            onDismiss = { showAdd = null },
            onAdd = { id, name ->
                val peer = Peer(id, name)
                showAdd = null
                // adding only sends a request. The peer becomes paired once the other device allows it
                // (nothing is kept if declined or undelivered). While waiting it shows under "Pending"
                if (settings.isPaired(peer.id)) {
                    settings.addPeer(peer) // already paired: just rename
                } else {
                    FsLog.i(TAG, "add peer -> pair request only: ${peer.name} (${peer.shortId})")
                    snackbar.show("Pairing request sent. Waiting for the other device to approve.")
                }
                graph.pairing.requestTo(peer)
            },
        )
    }

    if (editRelay) {
        TextInputDialog(
            "Your own relay URL (leave empty for the public relay)", relay,
            hint = "e.g. https://relay.example.com",
            onDismiss = { editRelay = false },
        ) {
            settings.setRelayUrl(it)
            editRelay = false
            node.requestRestartNow()
        }
    }

    if (editName) {
        TextInputDialog(
            "${FsText.deviceNameTitle} (leave empty for \"${settings.defaultDeviceName}\")", customName,
            hint = settings.defaultDeviceName,
            onDismiss = { editName = false },
        ) {
            settings.setDeviceName(it)
            editName = false
        }
    }
}

/**
 * Device Hub tab: This device (ID / QR / Share), paired devices (with online status), pending requests.
 * The bottom-right FAB menu (Add by QR code / Enter manually) lives in HomeScreen's Scaffold.
 */
@Composable
private fun DeviceHubTab(
    graph: AppGraph,
    hooks: UiHooks,
    modifier: Modifier,
    status: IrohNode.Status,
    peers: List<Peer>,
    endpointId: String,
    deviceName: String,
    onRestart: () -> Unit,
    onCopyId: () -> Unit,
    onShowQr: () -> Unit,
) {
    val outgoing by graph.pairing.outgoing.collectAsState()
    /** Target of the unpair confirmation dialog. */
    var confirmUnpair by remember { mutableStateOf<Peer?>(null) }
    /** Target of the "Send" menu (on the peer row; was the browse screen's top bar). */
    var sendTo by remember { mutableStateOf<Peer?>(null) }
    val presence by graph.presence.states.collectAsState()
    /** Bumped by the refresh button: restarts the loop below = check now and count 30 s again. */
    var refreshTick by remember { mutableStateOf(0) }
    /** A check round is running (the refresh button shows a spinner meanwhile). */
    var checking by remember { mutableStateOf(false) }

    // check which paired devices are online every 30 s while Device Hub is visible
    // (lifecycle STARTED: app in front on Android / iOS, window shown on desktop). Restarts when the
    // paired list changes so a new device is checked right away.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val peerIds = peers.map { it.id }
    // also restarts when the node comes online, so devices are checked right away instead of 30 s later
    val nodeOnline = status is IrohNode.Status.Online
    LaunchedEffect(lifecycle, peerIds, nodeOnline, refreshTick) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            FsLog.d(TAG, "presence checks on (${peerIds.size} devices)")
            try {
                while (true) {
                    checking = true
                    try {
                        graph.presence.checkAll()
                    } finally {
                        checking = false
                    }
                    delay(Presence.INTERVAL_MS)
                }
            } finally {
                FsLog.d(TAG, "presence checks off")
            }
        }
    }

    sendTo?.let { target ->
        SendMenu(graph, hooks, target, onDismiss = { sendTo = null })
    }

    confirmUnpair?.let { target ->
        ConfirmDialog(
            title = "Unpair this device?",
            text = "${FsText.peerWithId(target.name, target.shortId)} will be unpaired. This device is also removed from the other device automatically (if it's offline, the next time it connects to this device).",
            confirmLabel = "Unpair",
            onConfirm = {
                FsLog.i(TAG, "unpair confirmed by user: ${target.name} (${target.shortId})")
                graph.pairing.unpair(target)
                confirmUnpair = null
            },
            onDismiss = { confirmUnpair = null },
        )
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("This device") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // the status line (online, relay URL) is plain text instead of marquee (may wrap)
                    Text(statusText(status), Modifier.weight(1f))
                    // on every platform (was iOS only). Stops the iroh node and starts a new one.
                    // disabled while the node is starting or stopping (repeated taps crashed iOS)
                    val stopping by graph.node.stopping.collectAsState()
                    TooltipIconButton(
                        "Restart", vectorResource(Res.drawable.ic_restart_alt), onClick = onRestart,
                        enabled = !stopping && status !is IrohNode.Status.Starting,
                    )
                }
                // The device ID is built like Canta's (package name in AppInfoDialog):
                // a rounded (shapes.small) secondaryContainer Row with a small content_copy icon + text;
                // tapping the whole Row copies. Being the theme's secondary color, it always differs from
                // the surrounding card (surfaceContainerLow). Full card width, monospace text.
                // back to plain text instead of marquee (all digits visible, wraps on narrow screens).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(onClick = onCopyId)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        vectorResource(Res.drawable.ic_content_copy),
                        contentDescription = FsText.copyLabel,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        endpointId,
                        Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TooltipIconButton("Show QR code", vectorResource(Res.drawable.ic_qr_code), onClick = onShowQr)
                    // Windows uses "Share" too (its Copy button is gone); tapping the ID box copies
                    if (hooks.canShareText) {
                        // remember the button rect and pass it as the share sheet (iPad popover)
                        // origin. boundsInWindow is in px, so convert to dp/pt.
                        var anchor by remember { mutableStateOf<ShareAnchor?>(null) }
                        val density = LocalDensity.current.density
                        TooltipIconButton(
                            "Share", vectorResource(Res.drawable.ic_share),
                            onClick = {
                                val code = PairingCode.encode(endpointId, deviceName)
                                FsLog.d(TAG, "share sheet: anchor=$anchor")
                                hooks.shareText(code, anchor)
                            },
                            modifier = Modifier.onGloballyPositioned { coords ->
                                val b = coords.boundsInWindow()
                                anchor = ShareAnchor(
                                    x = b.left / density,
                                    y = b.top / density,
                                    width = b.width / density,
                                    height = b.height / density,
                                )
                            },
                        )
                    }
                }
            }
        }

        item {
            // "Add by QR code" and "Enter manually" moved to the FAB menu, so just the header.
            // refresh button at the right end = check which devices are online right now
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Paired devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (checking && peers.isNotEmpty()) {
                    // same 48 dp slot as the IconButton so the header doesn't jump
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    }
                } else {
                    TooltipIconButton(
                        "Check online status", vectorResource(Res.drawable.ic_refresh),
                        enabled = peers.isNotEmpty(),
                        onClick = {
                            FsLog.d(TAG, "refresh presence (${peers.size} devices)")
                            refreshTick++
                        },
                    )
                }
            }
        }

        if (peers.isEmpty()) {
            item {
                Text(
                    "No devices yet. When you add a device, it gets a pairing request and shows up here once it's allowed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        items(peers, key = { it.id }) { peer ->
            // "name, ID, online status | vertical divider | Send, Unpair". The left side does nothing
            // when tapped (the browse screen was removed with the shared folder), so there's no chevron.
            Card(Modifier.fillMaxWidth()) {
                ActionDividerRow(
                    actions = {
                        TooltipIconButton(
                            FsText.send, vectorResource(Res.drawable.ic_send),
                            modifier = Modifier.padding(start = 8.dp),
                            onClick = {
                                FsLog.d(TAG, "send menu for ${peer.name} (${peer.shortId})")
                                sendTo = peer
                            },
                        )
                        // same icon as "You unpaired" in History (was a trash can).
                        // the other device is notified too, so confirm in case of a mis-tap
                        TooltipIconButton(
                            FsText.unpair, vectorResource(Res.drawable.ic_link_off),
                            modifier = Modifier.padding(end = 8.dp),
                            onClick = { confirmUnpair = peer },
                        )
                    },
                    dividerPadding = 12.dp,
                ) {
                    Column(Modifier.weight(1f).padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp)) {
                        MarqueeText(peer.name, style = MaterialTheme.typography.titleSmall)
                        MarqueeText(
                            peer.shortId + "…",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                        PresenceLine(presence[peer.id] ?: PeerPresence.Checking)
                    }
                }
            }
        }

        // devices we requested that haven't approved yet (listed separately from paired ones)
        val pendingOut = outgoing.filter { o -> peers.none { it.id == o.id } }
        if (pendingOut.isNotEmpty()) {
            item { Text("Pending", style = MaterialTheme.typography.titleMedium) }
            items(pendingOut, key = { "out-" + it.id }) { peer ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            MarqueeText(peer.name, style = MaterialTheme.typography.titleSmall)
                            MarqueeText(
                                peer.shortId + "…",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                            Text(
                                "Waiting for approval…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    }
                }
            }
        }


        // bottom space so the FAB menu doesn't cover content
        item { Spacer(Modifier.size(88.dp)) }
    }
}

/**
 * Settings tab: device name, listening, Save to, receiving, relay (and device ID reset), logs.
 * the shared folder card was removed with the feature.
 */
@Composable
private fun SettingsTab(
    graph: AppGraph,
    hooks: UiHooks,
    modifier: Modifier,
    status: IrohNode.Status,
    relay: String,
    deviceName: String,
    onEditName: () -> Unit,
    alwaysListen: Boolean,
    autoAccept: Boolean,
    onEditRelay: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val settings = graph.settings

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            // this device's name (default = the model name). Paired devices pick up a new name
            // with the next online check (PING carries it).
            SectionCard(
                FsText.deviceNameTitle,
                description = "Other devices see this name. Default: ${settings.defaultDeviceName}",
            ) {
                ValueActionRow(
                    value = deviceName,
                    actionLabel = FsText.change,
                    actionIcon = vectorResource(Res.drawable.ic_edit),
                    onAction = {
                        FsLog.d(TAG, "edit device name")
                        onEditName()
                    },
                )
            }
        }

        // listening has its own card; "Keep screen on" (all platforms) and iOS's silent-audio
        // switch live here too, since they also keep the device listening.
        item {
            SectionCard(FsText.listening) {
                hooks.alwaysListenDescription?.let { desc ->
                    SwitchRow("Always listen", desc, checked = alwaysListen) { on ->
                        FsLog.d(TAG, "alwaysListen switch -> $on")
                        if (on) {
                            // Android: notifications must be on first (opens the settings and waits)
                            hooks.beforeEnableAlwaysListen {
                                FsLog.d(TAG, "alwaysListen enabled")
                                settings.setAlwaysListen(true)
                                hooks.onAlwaysListenChanged(true)
                            }
                        } else {
                            settings.setAlwaysListen(false)
                            hooks.onAlwaysListenChanged(false)
                        }
                    }
                }
                val keepScreenOn by settings.keepScreenOn.collectAsState()
                SwitchRow(
                    "Keep screen on",
                    "Keeps the screen from turning off while ${AppInfo.NAME} is open.",
                    checked = keepScreenOn,
                ) {
                    settings.setKeepScreenOn(it)
                    hooks.applyKeepScreenOn(it)
                }
                if (hooks.showKeepAliveAudio) {
                    val keepAliveAudio by settings.keepAliveAudio.collectAsState()
                    SwitchRow(
                        "Keep alive with silent audio",
                        "Keeps listening for a while after the screen turns off (uses more battery).",
                        checked = keepAliveAudio,
                    ) {
                        settings.setKeepAliveAudio(it)
                        hooks.applyKeepAliveAudio(it)
                    }
                }
            }
        }

        // Save to (default / custom). iOS, and Android and desktop
        hooks.saveDest?.let { dest ->
            item { SaveDestCard(dest) }
        }

        item {
            SectionCard("Receiving") {
                SwitchRow(
                    "Accept without asking",
                    if (autoAccept) "Files sent from paired devices are saved without asking."
                    else "Asks you to accept or decline each time (declined automatically after ${FsText.consentTimeoutSeconds} seconds).",
                    checked = autoAccept,
                ) { on ->
                    FsLog.d(TAG, "autoAccept switch -> $on")
                    settings.setAutoAcceptIncoming(on)
                }
            }
        }

        item {
            SectionCard("Relay") {
                // "Change" became an icon button. Like peer rows, split into
                // "URL | vertical divider | change icon". Three icons:
                //   not set (empty = public relay): change_circle
                //   set and connected (Online and homeRelay is our URL): published_with_changes
                //   set but not connected (starting, error, no relay, mismatch): sync_problem
                val relayUsable = remember(relay, status) {
                    val s = status
                    val ok = relay.isNotBlank() && s is IrohNode.Status.Online &&
                        normalizeRelayUrl(s.homeRelay ?: "") == normalizeRelayUrl(relay)
                    FsLog.d(TAG, "relay usable: $ok (relay=$relay status=$s)")
                    ok
                }
                val relayIcon = when {
                    relay.isBlank() -> Res.drawable.ic_change_circle
                    relayUsable -> Res.drawable.ic_published_with_changes
                    else -> Res.drawable.ic_sync_problem
                }
                ValueActionRow(
                    value = if (relay.isBlank()) "iroh public relay (default)" else relay,
                    actionLabel = FsText.change,
                    actionIcon = vectorResource(relayIcon),
                    onAction = onEditRelay,
                )
                // "Reset device ID" (new key; paired devices are unpaired and told first)
                ResetIdRow(graph)
            }
        }

        item {
            // tapping the whole card opens the log screen (chevron like peer rows, no text button).
            // Android uses this card -> log screen too (the inline log at the bottom of home was removed).
            Card(onClick = {
                FsLog.d(TAG, "open logs")
                onOpenLogs()
            }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(FsText.logTitle, style = MaterialTheme.typography.titleMedium)
                        Text("Shows the in-app log.", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(
                        vectorResource(Res.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { Spacer(Modifier.size(24.dp)) }
    }
}

/**
 * "Add device" FAB menu. Tapping expands "Add by QR code" and "Enter manually".
 * M3 Expressive FloatingActionButtonMenu + ToggleFloatingActionButton
 * (in Compose Multiplatform 1.9's material3; experimental API).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddPeerFabMenu(
    canScanQr: Boolean,
    onScanQr: () -> Unit,
    onManual: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    FloatingActionButtonMenu(
        expanded = expanded,
        button = {
            // make the FAB a snackbar anchor (like setAnchorView(fab): the FAB stays put and
            // the snackbar shows above it)
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = {
                    FsLog.d(TAG, "fab menu expanded -> $it")
                    expanded = it
                },
                modifier = Modifier.snackbarAnchor(),
            ) {
                val icon by remember {
                    derivedStateOf { if (checkedProgress > 0.5f) Res.drawable.ic_close else Res.drawable.ic_add }
                }
                // like the official sample, ToggleFloatingActionButtonDefaults.animateIcon changes the
                // icon color and size with the expansion (checkedProgress)
                Icon(
                    painter = rememberVectorPainter(vectorResource(icon)),
                    contentDescription = if (expanded) FsText.close else FsText.addPeer,
                    modifier = with(ToggleFloatingActionButtonDefaults) {
                        Modifier.animateIcon({ checkedProgress })
                    },
                )
            }
        },
    ) {
        if (canScanQr) {
            FloatingActionButtonMenuItem(
                onClick = {
                    expanded = false
                    FsLog.d(TAG, "fab menu: scan qr")
                    onScanQr()
                },
                icon = { Icon(vectorResource(Res.drawable.ic_qr_code_scanner), contentDescription = null) },
                text = { Text(FsText.addByQr) },
            )
        }
        FloatingActionButtonMenuItem(
            onClick = {
                expanded = false
                FsLog.d(TAG, "fab menu: manual input")
                onManual()
            },
            icon = { Icon(vectorResource(Res.drawable.ic_keyboard), contentDescription = null) },
            text = { Text("Enter manually") },
        )
    }
}

/** Normalizes relay URLs for comparison (trailing / and ., case; iroh may add the FQDN dot). */
private fun normalizeRelayUrl(s: String): String = s.trim().trimEnd('/', '.').lowercase()

private fun statusText(s: IrohNode.Status): String = when (s) {
    IrohNode.Status.Stopped -> "Stopped"
    IrohNode.Status.Starting -> "Starting…"
    is IrohNode.Status.Online -> "Online (relay: ${s.homeRelay ?: "-"})"
    is IrohNode.Status.OfflineOnlyDirect -> s.note
    is IrohNode.Status.Error -> "Error: ${s.message}"
}

@Composable
private fun SaveDestCard(dest: SaveDestHooks) {
    val state by dest.state.collectAsState()
    SectionCard(
        "Save to",
        spacing = 8.dp,
        titleAction = {
            TooltipIconButton(dest.openLabel, vectorResource(Res.drawable.ic_folder_open), onClick = {
                dest.openInFilesApp()
            })
        },
    ) {
        // standard radio buttons (single choice) instead of a "✓" Text.
        // The whole row is selectable (Role.RadioButton); RadioButton itself has onClick = null (accessibility guidance)
        val appSelected = state.kind == "app"
        val customSelected = state.kind == "custom"
        val customUsable = state.customPath != null
        Column(Modifier.selectableGroup()) {
            // ○ Default
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = appSelected, onClick = { dest.selectApp() }, role = Role.RadioButton)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = appSelected, onClick = null)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    // label depends on the environment (LiveContainer guest or standalone install)
                    Text(state.defaultLabel)
                    // real path only, like Custom (dropped the Files app display path FarSend/FarSend)
                    MarqueeText(state.appPath, style = MaterialTheme.typography.bodySmall)
                }
            }
            // ○ Custom | Change (a vertical divider splits "select" and "change folder", like peer rows)
            // The icon differs for selected and accessible (customPath set) / otherwise (not selected or
            // inaccessible) (two variants)
            ActionDividerRow(
                actionLabel = FsText.change,
                actionIcon = vectorResource(folderPickIcon(usable = customUsable)),
                onAction = { dest.pickCustomFolder { } },
                dividerPadding = 8.dp,
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = customSelected,
                            onClick = {
                                // if selected and accessible, just switch; otherwise open the folder picker
                                if (customUsable) dest.selectCustom() else dest.pickCustomFolder { }
                            },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = customSelected, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp)) {
                        Text("Custom")
                        MarqueeText(
                            state.customPath
                                ?: state.customName?.let { "$it (can't access)" }
                                ?: "No folder selected",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        Text(
            "Received files are saved to <Save to>/<device name>/<path on that device>.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Online status line of a paired device: a dot plus text (the text alone carries the state). */
@Composable
private fun PresenceLine(p: PeerPresence) {
    val (text, color) = when (p) {
        PeerPresence.Checking -> FsText.presenceChecking to MaterialTheme.colorScheme.outlineVariant
        is PeerPresence.Online -> FsText.presenceOnline(p.route) to MaterialTheme.colorScheme.primary
        PeerPresence.NotPairedThere -> FsText.presenceNotPairedThere to MaterialTheme.colorScheme.tertiary
        PeerPresence.Offline -> FsText.presenceOffline to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(6.dp))
        MarqueeText(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "Reset device ID" row in the Relay card. Confirms first, then Pairing.startResetIdentity()
 * (tells every paired device, new key, node restart). A spinner replaces the button while it runs (up to 20 s).
 */
@Composable
private fun ResetIdRow(graph: AppGraph) {
    val snackbar = LocalSnackbar.current
    val peers by graph.settings.peers.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val resetting by graph.pairing.resetting.collectAsState()

    ActionDividerRow(
        actions = {
            if (resetting) {
                Box(Modifier.size(48.dp).padding(start = 4.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                }
            } else {
                TooltipIconButton(
                    FsText.resetIdTitle, vectorResource(Res.drawable.ic_lock_reset),
                    modifier = Modifier.padding(start = 4.dp),
                    onClick = { confirm = true },
                )
            }
        },
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(FsText.resetIdTitle, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Gives this device a new ID. Paired devices are unpaired.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirm) {
        ConfirmDialog(
            title = "${FsText.resetIdTitle}?",
            text = FsText.resetIdBody(peers.size),
            confirmLabel = "Reset",
            onConfirm = {
                confirm = false
                FsLog.i(TAG, "reset device ID confirmed by user (${peers.size} paired)")
                // runs in the app scope; the snackbar controller is app-wide, so the result shows on any screen
                graph.pairing.startResetIdentity { notReached -> snackbar.show(FsText.resetIdDone(notReached)) }
            },
            onDismiss = { confirm = false },
        )
    }
}

/**
 * Icon of the folder "Change" button (custom Save to; two variants).
 * Selected and accessible: published_with_changes / not selected or inaccessible: sync_problem
 */
private fun folderPickIcon(usable: Boolean): DrawableResource =
    if (usable) Res.drawable.ic_published_with_changes else Res.drawable.ic_sync_problem

@Composable
private fun AddPeerDialog(
    initialId: String,
    initialName: String,
    selfId: String,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var raw by remember { mutableStateOf(initialId) }
    val parsed = PairingCode.decode(raw)
    val error = when {
        raw.isBlank() -> null
        parsed == null -> "Invalid device ID format"
        parsed.first == selfId -> "This is this device's own ID"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(FsText.addPeer) },
        text = {
            // fields span the dialog width (follows the screen width)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Paste / Clear button at the end of every field
                PasteClearTextField(
                    raw, { raw = it }, label = "Device ID of the other device", error = error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null && error == null, onClick = {
                val (id, codeName) = parsed!!
                // no Name field (the other device's Device name in Settings is the name). Until the
                // first online check (PING, right after it's paired) replaces it: the name in the pairing code
                // (QR / shared text), the QR scan's name, or the first 10 characters of the ID
                onAdd(id, codeName ?: initialName.ifBlank { id.take(10) })
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(FsText.cancel) } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    hint: String? = null,
    /** Multi-line input ("Send" -> "Text"). OK is disabled while empty. */
    multiline: Boolean = false,
    okLabel: String = "OK",
    onDismiss: () -> Unit,
    onOk: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Paste / Clear button at the end (text to send, device name, relay URL)
            if (multiline) {
                PasteClearTextField(
                    value, { value = it }, placeholder = hint,
                    minLines = 4, maxLines = 12, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // single-line fields (relay URL etc.) also span the dialog width
                PasteClearTextField(
                    value, { value = it }, placeholder = hint,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onOk(value) }, enabled = !multiline || value.isNotBlank()) { Text(okLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(FsText.cancel) } },
    )
}
