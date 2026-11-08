package com.example.farsend.ui

import androidx.compose.ui.platform.Clipboard

/*
 * Plain-text access on top of the Compose Clipboard (LocalClipboard).
 *
 * The old LocalClipboardManager (getText/setText) is deprecated; its replacement works with
 * platform-specific ClipEntry objects and suspend functions, so each platform supplies the
 * small conversion here. Callers launch these from a coroutine scope.
 */

/** Returns the clipboard text, or null when the clipboard has none. */
internal expect suspend fun Clipboard.readPlainText(): String?

/** Replaces the clipboard content with [text]. */
internal expect suspend fun Clipboard.writePlainText(text: String)
