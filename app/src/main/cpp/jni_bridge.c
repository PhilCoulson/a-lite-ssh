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

static int copy_forwards(
    JNIEnv *env,
    jintArray localPorts,
    jintArray remotePorts,
    PortForward *out,
    int *count_out) {
    if (!localPorts || !remotePorts) {
        return -1;
    }
    jsize nlocal = (*env)->GetArrayLength(env, localPorts);
    jsize nremote = (*env)->GetArrayLength(env, remotePorts);
    if (nlocal != nremote || nlocal <= 0 || nlocal > ALITE_MAX_FORWARDS) {
        return -1;
    }
    jint *locals = (*env)->GetIntArrayElements(env, localPorts, NULL);
    jint *remotes = (*env)->GetIntArrayElements(env, remotePorts, NULL);
    if (!locals || !remotes) {
        if (locals) {
            (*env)->ReleaseIntArrayElements(env, localPorts, locals, JNI_ABORT);
        }
        if (remotes) {
            (*env)->ReleaseIntArrayElements(env, remotePorts, remotes, JNI_ABORT);
        }
        return -1;
    }
    for (jsize i = 0; i < nlocal; ++i) {
        out[i].local_port = locals[i];
        out[i].remote_port = remotes[i];
    }
    *count_out = nlocal;
    (*env)->ReleaseIntArrayElements(env, localPorts, locals, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, remotePorts, remotes, JNI_ABORT);
    return 0;
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
    jintArray localPorts,
    jintArray remotePorts,
    jobject listener) {
    (void)thiz;
    if (!host || !username || !listener) {
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
    if (copy_forwards(env, localPorts, remotePorts, cfg.forwards, &cfg.forward_count) != 0) {
        tunnel_config_free(&cfg);
        release_listener(env);
        return -11;
    }

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

JNIEXPORT jint JNICALL
Java_com_alite_ssh_SshNative_nativeReplaceForwards(
    JNIEnv *env,
    jobject thiz,
    jintArray localPorts,
    jintArray remotePorts) {
    (void)thiz;
    int count = 0;
    PortForward forwards[ALITE_MAX_FORWARDS];
    memset(forwards, 0, sizeof(forwards));
    if (localPorts == NULL || remotePorts == NULL ||
        (*env)->GetArrayLength(env, localPorts) == 0) {
        return tunnel_replace_forwards(NULL, NULL, 0);
    }
    if (copy_forwards(env, localPorts, remotePorts, forwards, &count) != 0) {
        return -11;
    }
    int locals[ALITE_MAX_FORWARDS];
    int remotes[ALITE_MAX_FORWARDS];
    for (int i = 0; i < count; ++i) {
        locals[i] = forwards[i].local_port;
        remotes[i] = forwards[i].remote_port;
    }
    return tunnel_replace_forwards(locals, remotes, count);
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
