package com.example.farsend.ui

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

internal actual suspend fun Clipboard.readPlainText(): String? =
    getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

internal actual suspend fun Clipboard.writePlainText(text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
}
