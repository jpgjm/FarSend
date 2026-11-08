//! Minimal iroh FFI shim for FarSend on iOS (called from Kotlin/Native via cinterop).
//!
//! Android and Windows use the official `computer.iroh` bindings (iroh core 1.x); this shim uses
//! the same iroh core (1.x) so the relay/handshake wire protocol matches.
//!
//! Only the operations FarSend needs are exposed as a C ABI. The FarSend protocol (OFFER2 / PUT /
//! PAIR / PING etc.) is handled in the shared Kotlin code. This is just a thin
//! wrapper around iroh endpoints, connections and streams.
//!
//! This file targets iroh 1.x's public API. The actual version cargo resolves
//! at compile time is pinned in Cargo.lock. If API names ever differ, only
//! the call sites below need changes (see "iroh API drift" in docs/build.md):
//!   - Endpoint::builder / presets::N0 / Builder::{secret_key,alpns,relay_mode,bind}
//!   - Endpoint::{accept, connect, id, online}
//!   - Connection::{open_bi, accept_bi, close, close_reason, remote_id, paths}
//!   - Path::{is_selected, is_relay, is_ip, rtt} (path display, fs_conn_path_summary)
//!   - SendStream::{write_all, finish, reset} / RecvStream::read_exact
//!   - SecretKey::{from_bytes, public}
//!   - RelayMode::Custom / RelayMap / RelayUrl / EndpointId / EndpointAddr / VarInt

use std::cell::RefCell;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;
use std::sync::Mutex;
use std::time::Duration;

use tokio::runtime::{Builder as RtBuilder, Handle, Runtime};
use tokio::sync::mpsc;

use iroh::endpoint::{presets, Connection, RecvStream, SendStream, VarInt};
use iroh::{Endpoint, EndpointAddr, EndpointId, RelayMap, RelayMode, SecretKey};

/// ALPN of the FarSend wire protocol. Must match the Android app.
// Historical ALPN kept on purpose (protocol constant, matches shared Wire.ALPN_STRING and the
// deployed browser client); the product rename to FarSend does not change the wire protocol.
const ALPN: &[u8] = b"peersync/0";

// ---------------------------------------------------------------------------
// Error reporting (thread-local)
// ---------------------------------------------------------------------------

thread_local! {
    static LAST_ERROR: RefCell<Option<CString>> = RefCell::new(None);
}

fn set_last_error(msg: impl Into<String>) {
    let s = msg.into();
    LAST_ERROR.with(|e| {
        *e.borrow_mut() = CString::new(s).ok();
    });
}

/// Returns the most recent error message (caller frees it with fs_string_free). null if none.
#[no_mangle]
pub extern "C" fn fs_last_error() -> *mut c_char {
    LAST_ERROR.with(|e| match e.borrow().as_ref() {
        Some(c) => c.clone().into_raw(),
        None => ptr::null_mut(),
    })
}

/// Frees a string returned by fs_*.
#[no_mangle]
pub extern "C" fn fs_string_free(s: *mut c_char) {
    if !s.is_null() {
        unsafe {
            drop(CString::from_raw(s));
        }
    }
}

fn to_cstring(s: impl Into<String>) -> *mut c_char {
    match CString::new(s.into()) {
        Ok(c) => c.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

/// *const c_char -> String (None if null/invalid)
unsafe fn cstr_to_string(p: *const c_char) -> Option<String> {
    if p.is_null() {
        return None;
    }
    CStr::from_ptr(p).to_str().ok().map(|s| s.to_owned())
}

/// Thin wrapper that swallows panics and returns a sentinel.
fn guard_ptr<T>(f: impl FnOnce() -> *mut T) -> *mut T {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(p) => p,
        Err(_) => {
            set_last_error("Internal error (Rust panic)");
            ptr::null_mut()
        }
    }
}

fn guard_i32(f: impl FnOnce() -> i32) -> i32 {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(_) => {
            set_last_error("Internal error (Rust panic)");
            -99
        }
    }
}

// ---------------------------------------------------------------------------
// Handle types
// ---------------------------------------------------------------------------

pub struct FsNode {
    _rt: Runtime,
    handle: Handle,
    endpoint: Endpoint,
    accept_rx: Mutex<mpsc::UnboundedReceiver<Connection>>,
}

pub struct FsConn {
    conn: Connection,
    handle: Handle,
}

pub struct FsSend {
    send: SendStream,
    handle: Handle,
}

pub struct FsRecv {
    recv: RecvStream,
    handle: Handle,
}

// ---------------------------------------------------------------------------
// Secret key and ID
// ---------------------------------------------------------------------------

/// Generates a random 32-byte secret key and writes it to out32.
#[no_mangle]
pub extern "C" fn fs_secret_generate(out32: *mut u8) -> i32 {
    guard_i32(|| {
        if out32.is_null() {
            set_last_error("out32 is null");
            return -1;
        }
        let mut buf = [0u8; 32];
        if getrandom::getrandom(&mut buf).is_err() {
            set_last_error("Failed to generate random bytes");
            return -2;
        }
        // An ed25519 secret key is any 32 bytes; return them as is (no round trip through SecretKey:
        // iroh-base's SecretKey::from_bytes / to_bytes just pass the same 32 bytes through).
        unsafe {
            ptr::copy_nonoverlapping(buf.as_ptr(), out32, 32);
        }
        0
    })
}

/// Gets the device ID string from a 32-byte secret key (free with fs_string_free).
#[no_mangle]
pub extern "C" fn fs_id_from_secret(secret32: *const u8, out_id: *mut *mut c_char) -> i32 {
    guard_i32(|| {
        if secret32.is_null() || out_id.is_null() {
            set_last_error("Argument is null");
            return -1;
        }
        let mut buf = [0u8; 32];
        unsafe {
            ptr::copy_nonoverlapping(secret32, buf.as_mut_ptr(), 32);
        }
        let sk = SecretKey::from_bytes(&buf);
        let id = sk.public().to_string();
        unsafe {
            *out_id = to_cstring(id);
        }
        0
    })
}

// ---------------------------------------------------------------------------
// Node (Endpoint)
// ---------------------------------------------------------------------------

fn start_inner(secret: [u8; 32], relay: Option<String>) -> Result<FsNode, String> {
    let rt = RtBuilder::new_multi_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("tokio runtime: {e}"))?;
    let handle = rt.handle().clone();

    let sk = SecretKey::from_bytes(&secret);

    let (endpoint, rx) = handle.block_on(async move {
        let mut builder = Endpoint::builder(presets::N0)
            .secret_key(sk)
            .alpns(vec![ALPN.to_vec()]);

        if let Some(r) = relay {
            let r = r.trim();
            if !r.is_empty() {
                // iroh 1.4: build RelayMap with try_from_iter, which takes &str
                let map = RelayMap::try_from_iter([r])
                    .map_err(|e| format!("Invalid relay URL: {e}"))?;
                builder = builder.relay_mode(RelayMode::Custom(map));
            }
        }

        let endpoint = builder.bind().await.map_err(|e| format!("bind: {e}"))?;

        let (tx, rx) = mpsc::unbounded_channel::<Connection>();
        let ep = endpoint.clone();
        tokio::spawn(async move {
            accept_loop(ep, tx).await;
        });

        Ok::<(Endpoint, mpsc::UnboundedReceiver<Connection>), String>((endpoint, rx))
    })?;

    Ok(FsNode {
        _rt: rt,
        handle,
        endpoint,
        accept_rx: Mutex::new(rx),
    })
}

/// Accept loop. ALPN is already filtered by the Endpoint (anything but peersync/0 is rejected).
/// Pairing (allow list) checks happen in the shared Kotlin code, so this only hands over connections.
async fn accept_loop(endpoint: Endpoint, tx: mpsc::UnboundedSender<Connection>) {
    loop {
        match endpoint.accept().await {
            Some(incoming) => {
                let tx = tx.clone();
                // Spawn per connection so accepting doesn't stall while waiting for handshakes
                tokio::spawn(async move {
                    let connecting = match incoming.accept() {
                        Ok(c) => c,
                        Err(_) => return,
                    };
                    if let Ok(conn) = connecting.await {
                        let _ = tx.send(conn);
                    }
                });
            }
            None => break, // Endpoint closed
        }
    }
}

/// Starts the node. secret32 is 32 bytes. relay_url null or empty means the public relay.
#[no_mangle]
pub extern "C" fn fs_node_start(secret32: *const u8, relay_url: *const c_char) -> *mut FsNode {
    guard_ptr(|| {
        if secret32.is_null() {
            set_last_error("secret32 is null");
            return ptr::null_mut();
        }
        let mut secret = [0u8; 32];
        unsafe {
            ptr::copy_nonoverlapping(secret32, secret.as_mut_ptr(), 32);
        }
        let relay = unsafe { cstr_to_string(relay_url) };
        match start_inner(secret, relay) {
            Ok(node) => Box::into_raw(Box::new(node)),
            Err(e) => {
                set_last_error(e);
                ptr::null_mut()
            }
        }
    })
}

/// This device's ID string (free with fs_string_free).
#[no_mangle]
pub extern "C" fn fs_node_id(node: *mut FsNode) -> *mut c_char {
    guard_ptr(|| {
        let node = match unsafe { node.as_ref() } {
            Some(n) => n,
            None => return ptr::null_mut(),
        };
        to_cstring(node.endpoint.id().to_string())
    })
}

/// Home relay URL (null if not connected; free with fs_string_free).
/// Taken from the current EndpointAddr, like `endpoint.addr().relayUrl()` in the JVM version
/// (call after fs_node_wait_online reports online).
#[no_mangle]
pub extern "C" fn fs_node_home_relay(node: *mut FsNode) -> *mut c_char {
    guard_ptr(|| {
        let node = match unsafe { node.as_ref() } {
            Some(n) => n,
            None => return ptr::null_mut(),
        };
        match node.endpoint.addr().relay_urls().next() {
            Some(url) => to_cstring(url.to_string()),
            None => ptr::null_mut(),
        }
    })
}

/// Waits until connected to a relay. 1=online, 0=timeout, <0=error.
#[no_mangle]
pub extern "C" fn fs_node_wait_online(node: *mut FsNode, timeout_ms: u64) -> i32 {
    guard_i32(|| {
        let node = match unsafe { node.as_ref() } {
            Some(n) => n,
            None => return -1,
        };
        let ok = node.handle.block_on(async {
            tokio::time::timeout(Duration::from_millis(timeout_ms), node.endpoint.online())
                .await
                .is_ok()
        });
        if ok {
            1
        } else {
            0
        }
    })
}

/// Waits for the next incoming connection (blocking). null once fs_node_close closes the endpoint.
#[no_mangle]
pub extern "C" fn fs_node_accept(node: *mut FsNode) -> *mut FsConn {
    guard_ptr(|| {
        let node = match unsafe { node.as_ref() } {
            Some(n) => n,
            None => return ptr::null_mut(),
        };
        let mut rx = match node.accept_rx.lock() {
            Ok(g) => g,
            Err(_) => return ptr::null_mut(),
        };
        let conn = node.handle.block_on(async { rx.recv().await });
        match conn {
            Some(conn) => Box::into_raw(Box::new(FsConn {
                conn,
                handle: node.handle.clone(),
            })),
            None => ptr::null_mut(),
        }
    })
}

/// Connects to peer_id.
#[no_mangle]
pub extern "C" fn fs_node_connect(node: *mut FsNode, peer_id: *const c_char) -> *mut FsConn {
    guard_ptr(|| {
        let node = match unsafe { node.as_ref() } {
            Some(n) => n,
            None => return ptr::null_mut(),
        };
        let id_str = match unsafe { cstr_to_string(peer_id) } {
            Some(s) => s,
            None => {
                set_last_error("peer_id is null");
                return ptr::null_mut();
            }
        };
        let id: EndpointId = match id_str.parse() {
            Ok(i) => i,
            Err(e) => {
                set_last_error(format!("Invalid device ID: {e}"));
                return ptr::null_mut();
            }
        };
        let addr = EndpointAddr::from(id);
        let res = node
            .handle
            .block_on(async { node.endpoint.connect(addr, ALPN).await });
        match res {
            Ok(conn) => Box::into_raw(Box::new(FsConn {
                conn,
                handle: node.handle.clone(),
            })),
            Err(e) => {
                set_last_error(format!("connect: {e}"));
                ptr::null_mut()
            }
        }
    })
}

/// Stops the node without freeing it. Blocked fs_node_accept calls return null and other
/// calls finish, but the handle stays valid. Free it with fs_node_free once every call has returned.
/// (Closing and freeing in one step let accept / wait_online running on other threads use freed
/// memory: iOS crashed with "memory corruption of free block" on Restart. Hence the two steps.)
#[no_mangle]
pub extern "C" fn fs_node_close(node: *mut FsNode) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let node = match unsafe { node.as_ref() } {
            Some(n) => n,
            None => return,
        };
        let ep = node.endpoint.clone();
        node.handle.block_on(async move {
            ep.close().await;
        });
    }));
}

/// Frees the node. Call after fs_node_close, once no other call on this node is running.
#[no_mangle]
pub extern "C" fn fs_node_free(node: *mut FsNode) {
    if node.is_null() {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| {
        drop(unsafe { Box::from_raw(node) });
    }));
}

// ---------------------------------------------------------------------------
// Connections
// ---------------------------------------------------------------------------

/// The other device's ID string (free with fs_string_free).
#[no_mangle]
pub extern "C" fn fs_conn_remote_id(conn: *mut FsConn) -> *mut c_char {
    guard_ptr(|| {
        let conn = match unsafe { conn.as_ref() } {
            Some(c) => c,
            None => return ptr::null_mut(),
        };
        to_cstring(conn.conn.remote_id().to_string())
    })
}

/// Opens a new bidirectional stream (send side in send, receive side in recv).
#[no_mangle]
pub extern "C" fn fs_conn_open_bi(
    conn: *mut FsConn,
    out_send: *mut *mut FsSend,
    out_recv: *mut *mut FsRecv,
) -> i32 {
    guard_i32(|| {
        let conn = match unsafe { conn.as_ref() } {
            Some(c) => c,
            None => return -1,
        };
        if out_send.is_null() || out_recv.is_null() {
            return -1;
        }
        let res = conn.handle.block_on(async { conn.conn.open_bi().await });
        match res {
            Ok((send, recv)) => {
                unsafe {
                    *out_send = Box::into_raw(Box::new(FsSend {
                        send,
                        handle: conn.handle.clone(),
                    }));
                    *out_recv = Box::into_raw(Box::new(FsRecv {
                        recv,
                        handle: conn.handle.clone(),
                    }));
                }
                0
            }
            Err(e) => {
                set_last_error(format!("open_bi: {e}"));
                -2
            }
        }
    })
}

/// Accepts the next incoming bidirectional stream. Negative if the connection is closed.
#[no_mangle]
pub extern "C" fn fs_conn_accept_bi(
    conn: *mut FsConn,
    out_send: *mut *mut FsSend,
    out_recv: *mut *mut FsRecv,
) -> i32 {
    guard_i32(|| {
        let conn = match unsafe { conn.as_ref() } {
            Some(c) => c,
            None => return -1,
        };
        if out_send.is_null() || out_recv.is_null() {
            return -1;
        }
        let res = conn.handle.block_on(async { conn.conn.accept_bi().await });
        match res {
            Ok((send, recv)) => {
                unsafe {
                    *out_send = Box::into_raw(Box::new(FsSend {
                        send,
                        handle: conn.handle.clone(),
                    }));
                    *out_recv = Box::into_raw(Box::new(FsRecv {
                        recv,
                        handle: conn.handle.clone(),
                    }));
                }
                0
            }
            Err(_) => -2, // Connection closed (normal end)
        }
    })
}

/// Whether the connection is closed. 1=closed, 0=open.
#[no_mangle]
pub extern "C" fn fs_conn_is_closed(conn: *mut FsConn) -> i32 {
    guard_i32(|| {
        let conn = match unsafe { conn.as_ref() } {
            Some(c) => c,
            None => return 1,
        };
        if conn.conn.close_reason().is_some() {
            1
        } else {
            0
        }
    })
}

/// Closes the connection.
#[no_mangle]
pub extern "C" fn fs_conn_close(conn: *mut FsConn, code: u32, reason: *const c_char) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let conn = match unsafe { conn.as_ref() } {
            Some(c) => c,
            None => return,
        };
        let reason = unsafe { cstr_to_string(reason) }.unwrap_or_default();
        conn.conn
            .close(VarInt::from_u32(code), reason.as_bytes());
    }));
}

/// Returns the selected path as a "<kind>|<rtt_ms>" string (free with fs_string_free).
/// kind: direct (direct IP) / relay (via relay) / other / none (no selected path, disconnected).
/// E.g. "direct|12", "relay|85". The caller (Swift / C#) builds the display text.
/// iroh 1.4: finds the is_selected Path in a Connection::paths() snapshot.
#[no_mangle]
pub extern "C" fn fs_conn_path_summary(conn: *mut FsConn) -> *mut c_char {
    guard_ptr(|| {
        let conn = match unsafe { conn.as_ref() } {
            Some(c) => c,
            None => return ptr::null_mut(),
        };
        if conn.conn.close_reason().is_some() {
            return to_cstring("none|0");
        }
        let paths = conn.conn.paths();
        let mut selected: Option<(&'static str, u128)> = None;
        for p in paths.iter() {
            if p.is_selected() {
                let kind = if p.is_relay() {
                    "relay"
                } else if p.is_ip() {
                    "direct"
                } else {
                    "other"
                };
                selected = Some((kind, p.rtt().as_millis()));
                break;
            }
        }
        match selected {
            Some((kind, rtt)) => to_cstring(format!("{kind}|{rtt}")),
            None => to_cstring("none|0"),
        }
    })
}

/// Frees the connection handle (doesn't close the connection). Call after fs_conn_close, once no other
/// call on this connection is running.
#[no_mangle]
pub extern "C" fn fs_conn_free(conn: *mut FsConn) {
    if !conn.is_null() {
        unsafe {
            drop(Box::from_raw(conn));
        }
    }
}

// ---------------------------------------------------------------------------
// Streams
// ---------------------------------------------------------------------------

/// Writes all bytes to the send stream.
#[no_mangle]
pub extern "C" fn fs_send_write(send: *mut FsSend, buf: *const u8, len: usize) -> i32 {
    guard_i32(|| {
        let send = match unsafe { send.as_mut() } {
            Some(s) => s,
            None => return -1,
        };
        if buf.is_null() && len != 0 {
            return -1;
        }
        let slice = unsafe { std::slice::from_raw_parts(buf, len) };
        let handle = send.handle.clone();
        let res = handle.block_on(async { send.send.write_all(slice).await });
        match res {
            Ok(()) => 0,
            Err(e) => {
                set_last_error(format!("write_all: {e}"));
                -2
            }
        }
    })
}

/// Finishes the send stream normally.
#[no_mangle]
pub extern "C" fn fs_send_finish(send: *mut FsSend) -> i32 {
    guard_i32(|| {
        let send = match unsafe { send.as_mut() } {
            Some(s) => s,
            None => return -1,
        };
        match send.send.finish() {
            Ok(()) => 0,
            Err(e) => {
                set_last_error(format!("finish: {e}"));
                -2
            }
        }
    })
}

/// Resets the send stream (ends the other side's read with an error).
#[no_mangle]
pub extern "C" fn fs_send_reset(send: *mut FsSend, code: u64) -> i32 {
    guard_i32(|| {
        let send = match unsafe { send.as_mut() } {
            Some(s) => s,
            None => return -1,
        };
        match send.send.reset(VarInt::from_u64(code).unwrap_or(VarInt::from_u32(1))) {
            Ok(()) => 0,
            Err(e) => {
                set_last_error(format!("reset: {e}"));
                -2
            }
        }
    })
}

/// Frees the send stream.
#[no_mangle]
pub extern "C" fn fs_send_free(send: *mut FsSend) {
    if !send.is_null() {
        unsafe {
            drop(Box::from_raw(send));
        }
    }
}

/// Reads exactly len bytes from the receive stream. 0=success, <0=EOF/error.
#[no_mangle]
pub extern "C" fn fs_recv_read_exact(recv: *mut FsRecv, buf: *mut u8, len: usize) -> i32 {
    guard_i32(|| {
        let recv = match unsafe { recv.as_mut() } {
            Some(r) => r,
            None => return -1,
        };
        if len == 0 {
            return 0;
        }
        if buf.is_null() {
            return -1;
        }
        let slice = unsafe { std::slice::from_raw_parts_mut(buf, len) };
        let handle = recv.handle.clone();
        let res = handle.block_on(async { recv.recv.read_exact(slice).await });
        match res {
            Ok(()) => 0,
            Err(e) => {
                set_last_error(format!("read_exact: {e}"));
                -2
            }
        }
    })
}

/// Frees the receive stream.
#[no_mangle]
pub extern "C" fn fs_recv_free(recv: *mut FsRecv) {
    if !recv.is_null() {
        unsafe {
            drop(Box::from_raw(recv));
        }
    }
}
