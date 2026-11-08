package com.example.farsend.net

import com.example.farsend.AppInfo
import com.example.farsend.proto.FrameIn
import com.example.farsend.proto.FrameOut
import com.example.farsend.proto.ProtocolException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import com.example.farsend.log.FsLog
import com.example.farsend.platform.ioDispatcher
import com.example.farsend.platform.CallGate
import com.example.farsend.platform.epochMillis
import kotlinx.coroutines.withContext
import cnames.structs.FsConn
import cnames.structs.FsNode
import cnames.structs.FsRecv
import cnames.structs.FsSend
import farsend.iroh.fs_conn_accept_bi
import farsend.iroh.fs_conn_close
import farsend.iroh.fs_conn_free
import farsend.iroh.fs_conn_is_closed
import farsend.iroh.fs_conn_open_bi
import farsend.iroh.fs_conn_path_summary
import farsend.iroh.fs_conn_remote_id
import farsend.iroh.fs_id_from_secret
import farsend.iroh.fs_last_error
import farsend.iroh.fs_node_accept
import farsend.iroh.fs_node_connect
import farsend.iroh.fs_node_home_relay
import farsend.iroh.fs_node_close
import farsend.iroh.fs_node_free
import farsend.iroh.fs_node_start
import farsend.iroh.fs_node_wait_online
import farsend.iroh.fs_recv_free
import farsend.iroh.fs_recv_read_exact
import farsend.iroh.fs_secret_generate
import farsend.iroh.fs_send_finish
import farsend.iroh.fs_send_free
import farsend.iroh.fs_send_reset
import farsend.iroh.fs_send_write
import farsend.iroh.fs_string_free

/*
 * iOS FsEndpoint implementation. Calls our Rust FFI (native/farsend-iroh, iroh 1.x) via cinterop.
 * All FFI calls block, so they run on Dispatchers.IO.
 * fs_last_error is thread-local, so the call and error fetch happen in the same block (same thread).
 * Corresponds to the FFI wrapper in the old iOS Net/IrohNode.swift. Handles are released
 * explicitly via dispose()/close() (Kotlin/Native has no deinit).
 */

private const val TAG = "${AppInfo.NAME}/Endpoint"

@OptIn(ExperimentalForeignApi::class)
private fun takeString(p: CPointer<ByteVar>?): String? {
    if (p == null) return null
    val s = p.toKString()
    fs_string_free(p)
    return s
}

@OptIn(ExperimentalForeignApi::class)
private fun lastError(): String? = takeString(fs_last_error())

@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
private class IosIn(handle: CPointer<FsRecv>) : FrameIn {
    private val ref = AtomicReference<CPointer<FsRecv>?>(handle)

    override suspend fun readExact(n: Int): ByteArray {
        if (n < 0) throw ProtocolException("bad length $n")
        val buf = ByteArray(n)
        if (n == 0) return buf
        val h = ref.load() ?: throw ProtocolException("stream closed")
        return withContext(ioDispatcher) {
            val rc = buf.usePinned { pinned ->
                fs_recv_read_exact(h, pinned.addressOf(0).reinterpret(), n.convert())
            }
            if (rc != 0) throw ProtocolException(lastError() ?: "read_exact failed ($rc)")
            buf
        }
    }

    override fun dispose() {
        ref.exchange(null)?.let { fs_recv_free(it) }
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
private class IosOut(handle: CPointer<FsSend>) : FrameOut {
    private val ref = AtomicReference<CPointer<FsSend>?>(handle)

    override suspend fun write(bytes: ByteArray) {
        val h = ref.load() ?: throw ProtocolException("stream closed")
        if (bytes.isEmpty()) return
        withContext(ioDispatcher) {
            val rc = bytes.usePinned { pinned ->
                fs_send_write(h, pinned.addressOf(0).reinterpret(), bytes.size.convert())
            }
            if (rc != 0) throw ProtocolException(lastError() ?: "write failed ($rc)")
        }
    }

    override suspend fun finish() {
        val h = ref.load() ?: throw ProtocolException("stream closed")
        withContext(ioDispatcher) {
            val rc = fs_send_finish(h)
            if (rc != 0) throw ProtocolException(lastError() ?: "finish failed ($rc)")
        }
    }

    override suspend fun abort() {
        val h = ref.load() ?: return
        withContext(ioDispatcher) { fs_send_reset(h, 1uL) }
    }

    override fun dispose() {
        ref.exchange(null)?.let { fs_send_free(it) }
    }
}

/**
 * Connection handle. Every call goes through a [CallGate], and close() is "close, wait for the calls
 * inside to return, then free" (freeing right after closing, while acceptBi / paths could still be
 * running on other threads, was a use-after-free).
 */
@OptIn(ExperimentalForeignApi::class)
private class IosFsConnection(
    private val h: CPointer<FsConn>,
    override val remoteId: String,
) : FsConnection {
    private val gate = CallGate()

    override suspend fun openBi(): Pair<FrameOut, FrameIn> = withContext(ioDispatcher) {
        gate.enter {
            memScoped {
                val send = allocPointerTo<FsSend>()
                val recv = allocPointerTo<FsRecv>()
                val rc = fs_conn_open_bi(h, send.ptr, recv.ptr)
                val s = send.value
                val r = recv.value
                if (rc < 0 || s == null || r == null) {
                    throw ProtocolException(lastError() ?: "Couldn't open a stream.")
                }
                Pair<FrameOut, FrameIn>(IosOut(s), IosIn(r))
            }
        } ?: throw ProtocolException("The connection is closed.")
    }

    override suspend fun acceptBi(): Pair<FrameIn, FrameOut>? = withContext(ioDispatcher) {
        gate.enter {
            memScoped {
                val send = allocPointerTo<FsSend>()
                val recv = allocPointerTo<FsRecv>()
                val rc = fs_conn_accept_bi(h, send.ptr, recv.ptr)
                val s = send.value
                val r = recv.value
                if (rc < 0 || s == null || r == null) {
                    null // connection closed
                } else {
                    Pair<FrameIn, FrameOut>(IosIn(r), IosOut(s))
                }
            }
        }
    }

    override fun isClosed(): Boolean = gate.enter { fs_conn_is_closed(h) != 0 } ?: true

    override fun paths(): List<PathInfo>? {
        val raw = gate.enter { takeString(fs_conn_path_summary(h)) } ?: return null
        // "<kind>|<rtt_ms>" (kind: direct / relay / other / none)
        val parts = raw.split('|', limit = 2)
        val kind = parts.getOrNull(0) ?: "none"
        val rtt = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return when (kind) {
            "direct" -> listOf(PathInfo(PathKind.Direct, rtt, selected = true))
            "relay" -> listOf(PathInfo(PathKind.Relay, rtt, selected = true))
            "other" -> listOf(PathInfo(PathKind.Other, rtt, selected = true))
            else -> emptyList()
        }
    }

    override suspend fun close(code: Long, reason: String) {
        if (!gate.close()) return
        // close first so a blocked accept_bi returns, wait for every call inside, then free
        withContext(ioDispatcher) { fs_conn_close(h, code.convert(), reason) }
        gate.awaitIdle()
        withContext(ioDispatcher) { fs_conn_free(h) }
    }
}

/**
 * Node handle. Every call goes through a [CallGate]; shutdown() is fs_node_close (blocked accept
 * returns null) -> wait for the calls inside -> fs_node_free. waitOnline() waits in short slices so a
 * shutdown doesn't have to wait for the whole timeout.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosFsEndpoint(private val node: CPointer<FsNode>) : FsEndpoint {
    private val gate = CallGate()

    override suspend fun waitOnline(timeoutMs: Long): Boolean = withContext(ioDispatcher) {
        var left = timeoutMs
        while (left > 0) {
            val slice = minOf(left, WAIT_ONLINE_SLICE_MS)
            val rc = gate.enter { fs_node_wait_online(node, slice.convert()) } ?: return@withContext false // stopped
            if (rc == 1) return@withContext true
            if (rc < 0) return@withContext false
            left -= slice
        }
        false
    }

    // added fs_node_home_relay to the FFI (same as the JVM endpoint.addr().relayUrl())
    override fun homeRelayUrl(): String? = gate.enter { takeString(fs_node_home_relay(node)) }
        .also { FsLog.d(TAG, "home relay: ${it ?: "(none)"}") }

    override suspend fun accept(): FsConnection? = withContext(ioDispatcher) {
        // null: stopped (gate closed, or fs_node_close made the blocked accept return)
        val conn = gate.enter { fs_node_accept(node) } ?: return@withContext null
        val remote = takeString(fs_conn_remote_id(conn))
        if (remote == null) {
            fs_conn_free(conn)
            throw ProtocolException("Couldn't get the other device's ID.")
        }
        IosFsConnection(conn, remote)
    }

    override suspend fun connect(peerId: String): FsConnection = withContext(ioDispatcher) {
        val conn = gate.enter {
            fs_node_connect(node, peerId) ?: throw ProtocolException(lastError() ?: "Connection failed.")
        } ?: throw ProtocolException("The node isn't running.")
        IosFsConnection(conn, peerId)
    }

    override suspend fun shutdown() {
        if (!gate.close()) return
        withContext(ioDispatcher) { fs_node_close(node) } // blocked accept / connect return
        val waitStart = epochMillis()
        gate.awaitIdle()
        FsLog.d(TAG, "node calls finished in ${epochMillis() - waitStart} ms; freeing")
        withContext(ioDispatcher) { fs_node_free(node) }
    }

    private companion object {
        /** waitOnline asks the FFI in slices this long, so shutdown waits at most about this long for it. */
        const val WAIT_ONLINE_SLICE_MS = 500L
    }
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun bindFsEndpoint(secretKey: ByteArray, relayUrl: String?): FsEndpoint =
    withContext(ioDispatcher) {
        val relay = relayUrl?.trim()?.ifBlank { null }
        val node = secretKey.usePinned { pinned ->
            fs_node_start(pinned.addressOf(0).reinterpret(), relay)
        } ?: throw ProtocolException(lastError() ?: "Bind failed.")
        IosFsEndpoint(node)
    }

@OptIn(ExperimentalForeignApi::class)
actual fun generateSecretKey(): ByteArray {
    val key = ByteArray(32)
    val rc = key.usePinned { fs_secret_generate(it.addressOf(0).reinterpret()) }
    check(rc == 0) { lastError() ?: "Key generation failed." }
    return key
}

@OptIn(ExperimentalForeignApi::class)
actual fun endpointIdFromSecret(secretKey: ByteArray): String = memScoped {
    val out = allocPointerTo<ByteVar>()
    val rc = secretKey.usePinned { fs_id_from_secret(it.addressOf(0).reinterpret(), out.ptr) }
    check(rc == 0) { lastError() ?: "Device ID calculation failed." }
    takeString(out.value) ?: error("Couldn't get the device ID.")
}
