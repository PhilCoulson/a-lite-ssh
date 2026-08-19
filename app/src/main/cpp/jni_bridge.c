#include "ssh_tunnel.h"

#include <jni.h>
#include <stdlib.h>
#include <string.h>

static JavaVM *g_vm = NULL;
static jobject g_listener = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

static char *dup_jstring(JNIEnv *env, jstring s) {
    if (!s) {
        return NULL;
    }
    const char *utf = (*env)->GetStringUTFChars(env, s, NULL);
    if (!utf) {
        return NULL;
    }
    char *out = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, s, utf);
    return out;
}

static void release_listener(JNIEnv *env) {
    if (g_listener) {
        (*env)->DeleteGlobalRef(env, g_listener);
        g_listener = NULL;
    }
}

JNIEXPORT jint JNICALL
Java_com_alite_ssh_SshNative_nativeStart(
    JNIEnv *env,
    jobject thiz,
    jstring host,
    jint port,
    jstring username,
    jstring password,
    jstring privateKey,
    jstring passphrase,
    jint localPort,
    jstring remoteHost,
    jint remotePort,
    jobject listener) {
    (void)thiz;
    if (!host || !username || !remoteHost || !listener) {
        return -10;
    }
    if (tunnel_is_running()) {
        return -1;
    }

    release_listener(env);
    g_listener = (*env)->NewGlobalRef(env, listener);

    TunnelConfig cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.host = dup_jstring(env, host);
    cfg.port = port;
    cfg.username = dup_jstring(env, username);
    cfg.password = dup_jstring(env, password);
    cfg.private_key = dup_jstring(env, privateKey);
    cfg.passphrase = dup_jstring(env, passphrase);
    cfg.local_port = localPort;
    cfg.remote_host = dup_jstring(env, remoteHost);
    cfg.remote_port = remotePort;

    TunnelCallbacks cb;
    cb.vm = g_vm;
    cb.listener = g_listener;

    int rc = tunnel_start(&cfg, &cb);
    tunnel_config_free(&cfg);
    if (rc != 0) {
        release_listener(env);
    }
    return rc;
}

JNIEXPORT void JNICALL
Java_com_alite_ssh_SshNative_nativeStop(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    tunnel_stop();
}

JNIEXPORT jboolean JNICALL
Java_com_alite_ssh_SshNative_nativeIsRunning(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return tunnel_is_running() ? JNI_TRUE : JNI_FALSE;
}
