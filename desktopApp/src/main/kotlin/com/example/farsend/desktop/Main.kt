package com.example.farsend.desktop

import com.example.farsend.AppInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import com.example.farsend.ui.AppTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.example.farsend.text.FsText
import com.example.farsend.AppGraph
import com.example.farsend.data.Incoming
import com.example.farsend.data.IncomingNotifier
import com.example.farsend.data.IncomingOffer
import com.example.farsend.data.Pairing
import com.example.farsend.data.PairingNotifier
import com.example.farsend.data.PairingRequest
import com.example.farsend.data.UnpairNotice
import com.example.farsend.data.ReceivedText
import com.example.farsend.data.SaveDestController
import com.example.farsend.data.Settings
import com.example.farsend.data.TransferKind
import com.example.farsend.data.TransferNotifier
import com.example.farsend.data.Transfers
import com.example.farsend.log.FsLog
import com.example.farsend.net.IrohNode
import com.example.farsend.proto.FileSendItem
import com.example.farsend.ui.App
import com.example.farsend.ui.SaveDestHooks
import com.example.farsend.ui.SendOption
import com.example.farsend.ui.SendPick
import com.example.farsend.ui.ShareAnchor
import androidx.compose.ui.awt.ComposeWindow
import com.example.farsend.ui.UiHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.awt.Frame
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

private const val TAG = "${AppInfo.NAME}/Main"

/**
 * App version. The jpackage launcher passes packageVersion from build.gradle.kts as
 * `jpackage.app-version`; read that instead of hard-coding a string that goes stale.
 * Dev runs (gradle run) don't pass it, so "dev".
 */
internal fun appVersion(): String =
    System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: "dev"

/** Tray notifications for finished/failed transfers (like the old Windows version's balloon tips). */
private class DesktopTransferNotifier : TransferNotifier {
    var trayState: TrayState? = null

    private fun label(kind: TransferKind) = FsText.transferVerb(kind)

    override fun onCompleted(id: Int, fileName: String, savedPath: String, kind: TransferKind) {
        trayState?.sendNotification(Notification(FsText.transferDoneTitle(kind), fileName, Notification.Type.Info))
    }

    override fun onFailed(id: Int, fileName: String, reason: String, kind: TransferKind) {
        trayState?.sendNotification(Notification("${label(kind)} failed", "$fileName: $reason", Notification.Type.Error))
    }
}


/**
 * Loads a PNG bundled in the jar (desktopApp/src/main/resources) as a Painter.
 * Replaces the deprecated painterResource(String); the Compose resources library is not
 * used here because these two fixed PNGs are desktop-only (tray and window icon).
 */
private fun classpathIconPainter(name: String): Painter {
    val bytes = checkNotNull(object {}.javaClass.getResourceAsStream("/$name")) { "missing resource: $name" }
        .use { it.readAllBytes() }
    return BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}

fun main(args: Array<String>) {
    // Auto-start passes --tray (start in the tray without a window, same as the old Windows version)
    val startHidden = args.any { it.equals("--tray", ignoreCase = true) }

    // settings and logs live next to the exe. If that folder can't be written, say so and quit
    // (no silent fallback to another location: the device ID would change without notice).
    DesktopPaths.writeProblem()?.let { reason ->
        System.err.println("Can't write to ${DesktopPaths.baseDir}: $reason")
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "${AppInfo.NAME} can't save its data in\n${DesktopPaths.baseDir}\n\n" +
                "Move the ${AppInfo.NAME} folder to a place you can write to (for example Documents or Desktop) " +
                "and start it again.\n\n($reason)",
            AppInfo.NAME,
            javax.swing.JOptionPane.ERROR_MESSAGE,
        )
        kotlin.system.exitProcess(1)
    }

    FsLog.addSink(FileLogSink())
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        FsLog.e("Unhandled", "Unhandled exception (${t.name}): $e")
    }
    FsLog.i(TAG, "${AppInfo.NAME} started v${appVersion()} (desktop)${if (startHidden) " --tray" else ""} data=${DesktopPaths.baseDir}")

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // "Always listen" (sign-in auto-start + tray) starts off, as on Android
    // Device name: the computer name until the user sets one in Settings
    val defaultName = System.getenv("COMPUTERNAME")
        ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
        ?: "PC"
    val settings = Settings(DesktopKeyValueStore(DesktopPaths.settingsFile), defaultDeviceName = defaultName)
    // "Save to": <user>\Downloads\FarSend or a folder the user picks
    val saveDest = SaveDestController(settings, DesktopSaveFolderAccess())
    val node = IrohNode(settings, appScope)
    val notifier = DesktopTransferNotifier()
    val transfers = Transfers(
        node = node,
        scope = appScope,
        notifier = notifier,
        historyStore = settings.store,
    )
    val incoming = Incoming(
        settings = settings,
        targets = saveDest,
        transfers = transfers,
        scope = appScope,
    )
    val pairing = Pairing(settings, node, appScope)
    val graph = AppGraph(settings, node, transfers, incoming, pairing)

    // "Always listen" = auto-start at sign-in (startup registration)
    AutoStart.apply(settings.alwaysListen.value)

    node.start()

    application {
        var windowVisible by remember { mutableStateOf(!startHidden) }

        // "Keep screen on" only while the window is shown (not while folded into the tray)
        val keepScreenOn by settings.keepScreenOn.collectAsState()
        LaunchedEffect(windowVisible, keepScreenOn) { KeepScreenOn.apply(keepScreenOn && windowVisible) }
        val trayState = rememberTrayState()
        notifier.trayState = trayState

        var scanRequest by remember { mutableStateOf<((String?) -> Unit)?>(null) }

        // When the other device sends, bring the window to the front (even from the tray) to show the consent dialog
        LaunchedEffect(Unit) {
            incoming.notifier = object : IncomingNotifier {
                override fun onOffer(offer: IncomingOffer) {
                    FsLog.i(TAG, "incoming offer from ${offer.peerName}: ${offer.count} items, ${offer.texts.size} texts")
                    windowVisible = true
                    trayState.sendNotification(
                        if (offer.isTextOnly) {
                            Notification(
                                FsText.offerTitle(offer.peerName, isText = true),
                                "Choose \"Copy\" or \"Close\" in the window.",
                                Notification.Type.Info,
                            )
                        } else {
                            Notification(
                                FsText.offerTitle(offer.peerName, isText = false),
                                "Accept ${offer.count} items? Choose \"Accept\" or \"Decline\" in the window.",
                                Notification.Type.Info,
                            )
                        },
                    )
                }

                override fun onTextAccepted(text: ReceivedText) {
                    // The foreground UI (App) does the actual copy. Bring the window back if it's in the tray
                    windowVisible = true
                }

                // "Accept without asking" skips the dialog but still shows a tray notification
                // (without these notifications nothing tells the user that text or files arrived)
                override fun onTextAutoAccepted(text: ReceivedText) {
                    FsLog.i(TAG, "auto-accepted text from ${text.peerName}: ${text.text.length} chars")
                    trayState.sendNotification(
                        Notification(
                            FsText.offerTitle(text.peerName, isText = true),
                            FsText.copiedToClipboard,
                            Notification.Type.Info,
                        ),
                    )
                }

                override fun onFilesAutoAccepted(offer: IncomingOffer) {
                    FsLog.i(TAG, "auto-accepted ${offer.count} items from ${offer.peerName}")
                    trayState.sendNotification(
                        Notification(
                            FsText.offerTitle(offer.peerName, isText = false),
                            FsText.autoAcceptedFilesBody(offer.count, offer.totalBytes),
                            Notification.Type.Info,
                        ),
                    )
                }
            }
            // Pairing requests likewise: bring the window to the front and show a tray notification
            pairing.notifier = object : PairingNotifier {
                override fun onRequest(request: PairingRequest) {
                    FsLog.i(TAG, "pairing request from ${request.peerName} (${request.shortId})")
                    windowVisible = true
                    trayState.sendNotification(
                        Notification(
                            FsText.pairRequestTitle,
                            // Windows tray notifications have no buttons, so instead of the shared text, tell the user to choose in the window
                            "Pairing request from ${FsText.peerWithId(request.peerName, request.shortId)}. Choose \"Allow\" or \"Cancel\" in the window.",
                            Notification.Type.Info,
                        ),
                    )
                }

                // The other device unpaired (removed automatically). Bring the window to the front to show the notice
                override fun onUnpaired(notice: UnpairNotice) {
                    FsLog.i(TAG, "unpaired by ${notice.peerName} (${notice.shortId})")
                    windowVisible = true
                    trayState.sendNotification(
                        Notification(
                            FsText.unpairedTitle,
                            FsText.unpairedBody(notice.peerName, notice.shortId),
                            Notification.Type.Info,
                        ),
                    )
                }
            }
        }

        // Main window used as the parent of native dialogs (FileDialog). Recreated after hiding to the tray, so fetch it each time
        val mainFrame = java.util.concurrent.atomic.AtomicReference<Frame?>(null)

        val hooks = remember {
            object : UiHooks {
                override val canScanQr = true

                override fun scanQr(onResult: (String?) -> Unit, onMessage: (String) -> Unit) {
                    scanRequest = onResult
                }

                // "Share" opens the Windows share window (WindowsShare).
                // Hidden on non-Windows desktops (dev runs on Linux / macOS); the ID box still copies on tap
                override val canShareText = WindowsShare.isAvailable

                override fun shareText(text: String, anchor: ShareAnchor?) {
                    // the share window is placed by Windows, so anchor is unused
                    val hwnd = (mainFrame.get() as? ComposeWindow)?.windowHandle ?: 0L
                    WindowsShare.share(hwnd, FsText.shareIdTitle, text)
                }

                // "Send":
                //  - Files (multiple): the OS-native "Open" dialog (java.awt.FileDialog, multi-select).
                //    The standard Windows dialog, as in FlyingCarpet; multi-select via checkboxes / Ctrl / Shift.
                //    (JFileChooser would only multi-select with Ctrl+click, which was confusing)
                //  - Folder: Windows' FileDialog can't pick folders, so JFileChooser (DIRECTORIES_ONLY)
                override val sendOptions = listOf(SendOption.Files, SendOption.Folder)

                override fun pickSendItems(option: SendOption, onResult: (SendPick?) -> Unit) {
                    SwingUtilities.invokeLater {
                        if (option == SendOption.Folder) {
                            val chooser = JFileChooser().apply {
                                dialogTitle = "Choose folder to send"
                                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                isMultiSelectionEnabled = false
                            }
                            if (chooser.showOpenDialog(mainFrame.get()) != JFileChooser.APPROVE_OPTION) {
                                onResult(null)
                                return@invokeLater
                            }
                            val items = FileSendItem.ofDirectory(chooser.selectedFile)
                            FsLog.d(TAG, "send picker (folder): ${chooser.selectedFile} -> ${items.size} items")
                            onResult(SendPick(items))
                        } else {
                            val dialog = FileDialog(mainFrame.get(), "Choose files to send", FileDialog.LOAD).apply {
                                isMultipleMode = true
                            }
                            dialog.isVisible = true // Modal; blocks until closed
                            val files = dialog.files?.toList().orEmpty()
                            if (files.isEmpty()) {
                                FsLog.d(TAG, "send picker (files): cancelled")
                                onResult(null)
                                return@invokeLater
                            }
                            val items = FileSendItem.ofFiles(files)
                            FsLog.d(TAG, "send picker (files): ${files.size} picked -> ${items.size} items")
                            onResult(SendPick(items))
                        }
                    }
                }

                /** Clipboard image (when there's no text). Sent as a temporary PNG file (same as LocalSend). */
                override fun readClipboardImage(): SendPick? {
                    val cb = Toolkit.getDefaultToolkit().systemClipboard
                    if (!cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
                    val image = cb.getData(DataFlavor.imageFlavor) as? java.awt.Image ?: return null
                    val w = image.getWidth(null)
                    val h = image.getHeight(null)
                    if (w <= 0 || h <= 0) return null
                    val buffered = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { b ->
                        val g = b.createGraphics()
                        g.drawImage(image, 0, 0, null)
                        g.dispose()
                    }
                    val name = "clipboard_" + java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")) + ".png"
                    val tmp = java.io.File(System.getProperty("java.io.tmpdir"), "${AppInfo.NAME}-$name")
                    ImageIO.write(buffered, "png", tmp)
                    FsLog.d(TAG, "clipboard image -> ${tmp.length()} bytes")
                    return SendPick(listOf(FileSendItem(tmp, name))) { tmp.delete() }
                }

                override val alwaysListenDescription =
                    "Starts when you sign in and listens from the system tray."

                override fun onAlwaysListenChanged(on: Boolean) {
                    AutoStart.apply(on)
                }

                // "Keep screen on" is applied by the LaunchedEffect above (it also depends on the window's visibility)

                override val saveDest: SaveDestHooks = saveDest.hooks(
                    openLabel = "Open in File Explorer",
                    pick = { current, onResult ->
                        SwingUtilities.invokeLater {
                            val chooser = JFileChooser().apply {
                                dialogTitle = "Choose where to save received files"
                                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                current?.let { currentDirectory = java.io.File(it) }
                            }
                            val ok = chooser.showOpenDialog(mainFrame.get()) == JFileChooser.APPROVE_OPTION
                            onResult(if (ok) chooser.selectedFile.absolutePath else null)
                        }
                    },
                    open = { state ->
                        val custom = state.customPath.takeIf { state.kind == Settings.SAVE_DEST_CUSTOM }
                        val dir = java.io.File(custom ?: state.appPath)
                        dir.mkdirs()
                        FsLog.d(TAG, "open folder: $dir")
                        java.awt.Desktop.getDesktop().open(dir)
                    },
                )

            }
        }

        Tray(
            state = trayState,
            icon = remember { classpathIconPainter("tray.png") },
            tooltip = AppInfo.NAME,
            onAction = { windowVisible = true },
            menu = {
                Item("Show", onClick = { windowVisible = true })
                Item("Quit", onClick = {
                    FsLog.i(TAG, "Quitting")
                    runBlocking { runCatching { node.stop() } }
                    exitApplication()
                })
            },
        )

        if (windowVisible) {
            Window(
                onCloseRequest = {
                    // While "Always listen" is on, closing hides the window to the tray (keeps listening)
                    if (settings.alwaysListen.value) {
                        FsLog.d(TAG, "close -> hide to tray (still listening)")
                        windowVisible = false
                    } else {
                        runBlocking { runCatching { node.stop() } }
                        exitApplication()
                    }
                },
                title = AppInfo.NAME,
                icon = remember { classpathIconPainter("app.png") },
                state = rememberWindowState(width = 480.dp, height = 760.dp),
            ) {
                DisposableEffect(window) {
                    mainFrame.set(window)
                    onDispose { mainFrame.compareAndSet(window, null) }
                }
                val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
                AppTheme(colorScheme = colors) {
                    App(graph, hooks)
                }
            }
        }

        scanRequest?.let { cb ->
            QrScanWindow(
                onClose = {
                    scanRequest = null
                    cb(null)
                },
                onFound = { text ->
                    scanRequest = null
                    cb(text)
                },
            )
        }
    }
}
