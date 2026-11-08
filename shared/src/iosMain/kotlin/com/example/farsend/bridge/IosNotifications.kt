package com.example.farsend.bridge

import com.example.farsend.AppInfo
import com.example.farsend.data.IncomingNotifier
import com.example.farsend.data.IncomingOffer
import com.example.farsend.data.PairingNotifier
import com.example.farsend.data.PairingRequest
import com.example.farsend.data.ReceivedText
import com.example.farsend.data.UnpairNotice
import com.example.farsend.text.FsText

/**
 * Builds iOS local notification content (ID, category, title, body).
 *
 * The text is built here from FsText so iOS wording matches the dialogs and Android notifications;
 * Swift (IncomingNotification.swift) only gets the 2 functions IosBridge.postNotification /
 * clearNotification.
 * Removal and button handling depend on the notification and category ID strings staying stable.
 * Swift references categories via `IosNotifications.shared.categoryIncoming` etc.
 */
object IosNotifications {
    /** "Accept / Decline" buttons. */
    val categoryIncoming: String = AppInfo.identifier("incoming")

    /** Text-only offer ("Copy / Close" buttons). */
    val categoryIncomingText: String = AppInfo.identifier("incoming.text")

    /** Pairing request ("Allow / Cancel" buttons). */
    val categoryPair: String = AppInfo.identifier("pair")

    // Notification buttons (registered and matched by Swift's IncomingNotification)
    val actionAccept: String = AppInfo.identifier("incoming.accept")
    val actionReject: String = AppInfo.identifier("incoming.reject")
    val actionPairAccept: String = AppInfo.identifier("pair.accept")
    val actionPairReject: String = AppInfo.identifier("pair.reject")

    fun offerId(token: String) = AppInfo.identifier("incoming.$token")
    fun textAcceptedId(token: String) = AppInfo.identifier("text.$token")
    fun autoAcceptedId(token: String) = AppInfo.identifier("received.$token")
    fun pairId(token: String) = AppInfo.identifier("pair.$token")
    fun unpairId(peerId: String) = AppInfo.identifier("unpair.$peerId")

    /** Notifications for the receiving side of "Send". */
    fun incomingNotifier(bridge: IosBridge) = object : IncomingNotifier {
        override fun onOffer(offer: IncomingOffer) {
            val body = if (offer.isTextOnly) {
                FsText.offerTextQuestion(offer.texts.sumOf { it.length }) + "\n" + offer.texts.joinToString(" ").take(120)
            } else {
                FsText.offerFilesQuestion(offer.count, offer.totalBytes) + "\n" +
                    FsText.itemListSummary(offer.items.map { it.relPath }, max = 5)
            }
            bridge.postNotification(
                notificationId = offerId(offer.token),
                title = FsText.offerTitle(offer.peerName, isText = offer.isTextOnly),
                body = body,
                category = if (offer.isTextOnly) categoryIncomingText else categoryIncoming,
                token = offer.token,
                logLabel = "Awaiting consent (${offer.peerName} / ${offer.count} items)",
            )
        }

        override fun onOfferResolved(token: String) = bridge.clearNotification(offerId(token))

        // The foreground UI does the copy, so in the background prompt "open to copy"
        override fun onTextAccepted(text: ReceivedText) = bridge.postNotification(
            notificationId = textAcceptedId(text.token),
            title = FsText.offerTitle(text.peerName, isText = true),
            body = FsText.textTapToCopy + "\n" + text.text.take(200),
            category = null,
            token = null,
            logLabel = "Text received (${text.peerName})",
        )

        // auto-accept (fallback when a continued-processing task can't be requested in the background)
        override fun onFilesAutoAccepted(offer: IncomingOffer) = bridge.postNotification(
            notificationId = autoAcceptedId(offer.token),
            title = FsText.offerTitle(offer.peerName, isText = false),
            body = FsText.autoAcceptedFilesBody(offer.count, offer.totalBytes) + "\n" +
                FsText.itemListSummary(offer.items.map { it.relPath }, max = 5),
            category = null,
            token = null,
            logLabel = "Auto-received (${offer.peerName} / ${offer.count} items)",
        )
    }

    /** Pairing request and unpair notifications. */
    fun pairingNotifier(bridge: IosBridge) = object : PairingNotifier {
        override fun onRequest(request: PairingRequest) = bridge.postNotification(
            notificationId = pairId(request.token),
            title = FsText.pairRequestTitle,
            body = FsText.pairRequestBody(request.peerName, request.shortId),
            category = categoryPair,
            token = request.token,
            logLabel = "Pairing request (${request.peerName})",
        )

        override fun onRequestResolved(token: String) = bridge.clearNotification(pairId(token))

        override fun onUnpaired(notice: UnpairNotice) = bridge.postNotification(
            notificationId = unpairId(notice.peerId),
            title = FsText.unpairedTitle,
            body = FsText.unpairedBody(notice.peerName, notice.shortId),
            category = null,
            token = null,
            logLabel = "Unpaired (${notice.peerName})",
        )

        override fun onUnpairResolved(peerId: String) = bridge.clearNotification(unpairId(peerId))
    }
}
