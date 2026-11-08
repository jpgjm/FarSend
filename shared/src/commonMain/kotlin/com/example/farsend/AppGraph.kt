package com.example.farsend

import com.example.farsend.data.Incoming
import com.example.farsend.data.Pairing
import com.example.farsend.data.PairingErrorHandler
import com.example.farsend.data.Peer
import com.example.farsend.data.Presence
import com.example.farsend.data.Settings
import com.example.farsend.data.Transfers
import com.example.farsend.net.IrohNode

/**
 * The app-wide core, one instance. Built by each platform's entry point
 * (Android: FarSendApp, desktop: Main.kt, iOS: IosApp.start).
 */
class AppGraph(
    val settings: Settings,
    val node: IrohNode,
    val transfers: Transfers,
    /** Receiving side of "Send" (consent, saving). */
    val incoming: Incoming,
    /** Pairing requests (receiver consent and requester sending). */
    val pairing: Pairing,
) {
    /**
     * Online status of paired devices, checked while Device Hub is shown.
     * The device name lives in [Settings.deviceName] (the user can change it).
     */
    val presence = Presence(settings, node, pairing)

    init {
        // Route FileServer OFFER / PUT to Incoming and PAIR / UNPAIR / PING to Pairing
        node.pushHandlerProvider = { peerId -> incoming.handlerFor(peerId) }
        node.pairHandlerProvider = { peerId -> pairing.handlerFor(peerId) }
        // a send refused for pairing reasons: request pairing / remove the peer (was the browse screen's job)
        transfers.pairingErrors = object : PairingErrorHandler {
            override fun onNotPaired(peer: Peer) = pairing.requestTo(peer)
            override fun onUnpaired(peer: Peer) = pairing.onUnpairedError(peer)
        }
    }
}
