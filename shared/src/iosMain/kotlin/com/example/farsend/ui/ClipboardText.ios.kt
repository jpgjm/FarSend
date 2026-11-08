package com.example.farsend.ui

import androidx.compose.ui.platform.Clipboard
import platform.UIKit.UIPasteboard

// UIPasteboard is used directly: converting through the Compose ClipEntry adds nothing on iOS.

internal actual suspend fun Clipboard.readPlainText(): String? =
    UIPasteboard.generalPasteboard.string

internal actual suspend fun Clipboard.writePlainText(text: String) {
    UIPasteboard.generalPasteboard.string = text
}
