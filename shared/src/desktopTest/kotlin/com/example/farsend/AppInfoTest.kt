package com.example.farsend

import com.example.farsend.data.PairingCode
import com.example.farsend.proto.FileServer
import com.example.farsend.text.FsText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the AppInfo / FsText values that storage locations, identifiers and wording depend on.
 * Changing the save folder name, part-file suffix, pairing code or iOS notification IDs would break
 * existing received files, partial files, notification removal and QR compatibility.
 */
class AppInfoTest {
    @Test
    fun valuesStayAsBefore() {
        assertEquals("FarSend", AppInfo.NAME)
        assertEquals("FarSend", AppInfo.DOWNLOAD_FOLDER)
        assertEquals(".farsend-part", AppInfo.PART_SUFFIX)
        assertEquals("logs", AppInfo.LOG_DIR)
        assertEquals("farsend", AppInfo.LOG_FILE_BASE)
        assertEquals("settings.json", AppInfo.SETTINGS_FILE)
        assertEquals("farsend", AppInfo.PREFS_NAME)
        assertEquals("FarSendOutbox", AppInfo.OUTBOX_FOLDER)
        assertEquals("FarSend/Main", AppInfo.tag("Main"))
        assertEquals("FarSend.incoming.accept", AppInfo.identifier("incoming.accept"))
        assertEquals("FarSend.transfer.3", AppInfo.identifier("transfer.3"))
    }

    @Test
    fun pairingCodeFormatUnchanged() {
        val id = "ab".repeat(32)
        assertEquals("farsend:$id?name=Pixel", PairingCode.encode(id, "Pixel"))
        assertEquals(id to "Pixel", PairingCode.decode("FARSEND:$id?name=Pixel"))
    }

    @Test
    fun textsUnchanged() {
        assertEquals(120L, FsText.consentTimeoutSeconds)
        assertEquals("FarSend is listening", FsText.listeningTitle)
        assertEquals("Scan the QR code shown in FarSend on the other device", FsText.scanQrPrompt)
        assertEquals("Copy", FsText.offerAcceptLabel(true))
        assertEquals("Accept", FsText.offerAcceptLabel(false))
        assertEquals("Close", FsText.offerRejectLabel(true))
        assertEquals("Decline", FsText.offerRejectLabel(false))
    }
}
