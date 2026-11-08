// ClipEntry's desktop constructor and nativeClipEntry are still experimental
@file:OptIn(ExperimentalComposeUiApi::class)

package com.example.farsend.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable

internal actual suspend fun Clipboard.readPlainText(): String? {
    val transferable = getClipEntry()?.nativeClipEntry as? Transferable ?: return null
    if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
    return transferable.getTransferData(DataFlavor.stringFlavor) as? String
}

internal actual suspend fun Clipboard.writePlainText(text: String) {
    setClipEntry(ClipEntry(StringSelection(text)))
}
