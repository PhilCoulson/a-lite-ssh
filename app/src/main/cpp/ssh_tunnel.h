#ifndef ALITE_SSH_TUNNEL_H
#define ALITE_SSH_TUNNEL_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct TunnelConfig {
    char *host;
    int port;
    char *username;
    char *password;
    char *private_key;
    char *passphrase;
    int local_port;
    char *remote_host;
    int remote_port;
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

#ifdef __cplusplus
}
#endif

#endif
