#ifndef ALITE_SSH_TUNNEL_H
#define ALITE_SSH_TUNNEL_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ALITE_MAX_FORWARDS 16
#define ALITE_HOST_LEN 256

typedef struct PortForward {
    int local_port;
    int remote_port;
    char remote_host[ALITE_HOST_LEN];
} PortForward;

typedef struct TunnelConfig {
    char *host;
    int port;
    char *username;
    char *password;
    char *private_key;
    char *passphrase;
    int forward_count;
    PortForward forwards[ALITE_MAX_FORWARDS];
} TunnelConfig;

typedef struct TunnelCallbacks {
    JavaVM *vm;
    jobject listener;
} TunnelCallbacks;

void tunnel_config_free(TunnelConfig *cfg);

/* Owns cfg (deep-copied internally). Returns 0 if the worker started. */
int tunnel_start(const TunnelConfig *cfg, const TunnelCallbacks *cb);

void tunnel_stop(void);

int tunnel_is_running(void);

/* Replace listening forwards on a running tunnel. Empty count is allowed. */
int tunnel_replace_forwards(const PortForward *forwards, int count);

#ifdef __cplusplus
}
#endif

#endif
