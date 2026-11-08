/* C header for the FarSend iroh FFI. Maps 1:1 to the #[no_mangle] functions in src/lib.rs.
 * Swift refers to it via the bridging header. */
#ifndef FARSEND_IROH_H
#define FARSEND_IROH_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct FsNode FsNode;
typedef struct FsConn FsConn;
typedef struct FsSend FsSend;
typedef struct FsRecv FsRecv;

/* Most recent error message (free with fs_string_free). NULL if none. */
char *fs_last_error(void);
void fs_string_free(char *s);

/* Keys and IDs */
int32_t fs_secret_generate(uint8_t *out32);
int32_t fs_id_from_secret(const uint8_t *secret32, char **out_id);

/* Node */
FsNode *fs_node_start(const uint8_t *secret32, const char *relay_url);
char *fs_node_id(FsNode *node);
/* Home relay URL. NULL if not connected. Free with fs_string_free. */
char *fs_node_home_relay(FsNode *node);
int32_t fs_node_wait_online(FsNode *node, uint64_t timeout_ms);
FsConn *fs_node_accept(FsNode *node);
FsConn *fs_node_connect(FsNode *node, const char *peer_id);
/* Stop (blocked accept returns NULL; handle stays valid), then free once no call is running (0.2.37). */
void fs_node_close(FsNode *node);
void fs_node_free(FsNode *node);

/* Connections */
char *fs_conn_remote_id(FsConn *conn);
int32_t fs_conn_open_bi(FsConn *conn, FsSend **out_send, FsRecv **out_recv);
int32_t fs_conn_accept_bi(FsConn *conn, FsSend **out_send, FsRecv **out_recv);
int32_t fs_conn_is_closed(FsConn *conn);
void fs_conn_close(FsConn *conn, uint32_t code, const char *reason);
/* Selected path "<kind>|<rtt_ms>" (kind: direct/relay/other/none). Free with fs_string_free. */
char *fs_conn_path_summary(FsConn *conn);
void fs_conn_free(FsConn *conn);

/* Streams */
int32_t fs_send_write(FsSend *send, const uint8_t *buf, size_t len);
int32_t fs_send_finish(FsSend *send);
int32_t fs_send_reset(FsSend *send, uint64_t code);
void fs_send_free(FsSend *send);
int32_t fs_recv_read_exact(FsRecv *recv, uint8_t *buf, size_t len);
void fs_recv_free(FsRecv *recv);

#ifdef __cplusplus
}
#endif

#endif /* FARSEND_IROH_H */
