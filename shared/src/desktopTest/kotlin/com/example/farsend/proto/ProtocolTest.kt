package com.example.farsend.proto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pairing code tests. (The removed shared-folder ops LIST / GET are covered by PairProtocolTest,
 * which checks that they answer "unknown op".)
 */
class ProtocolTest {
    @Test
    fun pairingCodeRoundTrip() {
        val id = "a".repeat(64)
        val code = com.example.farsend.data.PairingCode.encode(id, "REDMAGIC 9 Pro")
        val (gotId, gotName) = com.example.farsend.data.PairingCode.decode(code)!!
        assertEquals(id, gotId)
        assertEquals("REDMAGIC 9 Pro", gotName)
        // bare ID pasted
        assertEquals(id to null, com.example.farsend.data.PairingCode.decode("  $id "))
        // invalid
        assertEquals(null, com.example.farsend.data.PairingCode.decode("farsend:xyz"))
    }
}
