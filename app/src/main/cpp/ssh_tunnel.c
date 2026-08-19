#include "ssh_tunnel.h"

#include <libssh2.h>

#include <android/log.h>

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <unistd.h>

#define TAG "ALiteSsh"
#define MAX_CONNS 32
#define IO_BUF 32768
#define LISTEN_BACKLOG 16

#define ALOG(fmt, ...) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__)

typedef struct Conn {
    int fd;
    LIBSSH2_CHANNEL *channel;
    int local_eof;
    int remote_eof;
} Conn;

typedef struct TunnelState {
    volatile int running;
    volatile int stop_requested;
    pthread_t thread;
    pthread_mutex_t lock;
    int wake_r;
    int wake_w;
    TunnelConfig cfg;
    TunnelCallbacks cb;
} TunnelState;

static TunnelState g_state = {
    .running = 0,
    .stop_requested = 0,
    .wake_r = -1,
    .wake_w = -1,
    .lock = PTHREAD_MUTEX_INITIALIZER,
};

static char *dup_or_null(const char *s) {
    if (!s) {
        return NULL;
    }
    return strdup(s);
}

void tunnel_config_free(TunnelConfig *cfg) {
    if (!cfg) {
        return;
    }
    free(cfg->host);
    free(cfg->username);
    free(cfg->password);
    free(cfg->private_key);
    free(cfg->passphrase);
    free(cfg->remote_host);
    memset(cfg, 0, sizeof(*cfg));
}

static TunnelConfig cfg_dup(const TunnelConfig *src) {
    TunnelConfig d;
    memset(&d, 0, sizeof(d));
    d.host = dup_or_null(src->host);
    d.port = src->port;
    d.username = dup_or_null(src->username);
    d.password = dup_or_null(src->password);
    d.private_key = dup_or_null(src->private_key);
    d.passphrase = dup_or_null(src->passphrase);
    d.local_port = src->local_port;
    d.remote_host = dup_or_null(src->remote_host);
    d.remote_port = src->remote_port;
    return d;
}

static JNIEnv *attach_env(JavaVM *vm, int *attached) {
    JNIEnv *env = NULL;
    *attached = 0;
    if (!vm) {
        return NULL;
    }
    jint rc = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
    if (rc == JNI_OK) {
        return env;
    }
    if (rc == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) == 0) {
            *attached = 1;
            return env;
        }
    }
    return NULL;
}

static void cb_log(TunnelCallbacks *cb, const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    ALOG("%s", buf);

    if (!cb || !cb->vm || !cb->listener) {
        return;
    }
    int attached = 0;
    JNIEnv *env = attach_env(cb->vm, &attached);
    if (!env) {
        return;
    }
    jclass cls = (*env)->GetObjectClass(env, cb->listener);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onLog", "(Ljava/lang/String;)V");
    if (mid) {
        jstring msg = (*env)->NewStringUTF(env, buf);
        (*env)->CallVoidMethod(env, cb->listener, mid, msg);
        (*env)->DeleteLocalRef(env, msg);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }
    (*env)->DeleteLocalRef(env, cls);
    if (attached) {
        (*cb->vm)->DetachCurrentThread(cb->vm);
    }
}

static void cb_state(TunnelCallbacks *cb, const char *state) {
    if (!cb || !cb->vm || !cb->listener) {
        return;
    }
    int attached = 0;
    JNIEnv *env = attach_env(cb->vm, &attached);
    if (!env) {
        return;
    }
    jclass cls = (*env)->GetObjectClass(env, cb->listener);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onState", "(Ljava/lang/String;)V");
    if (mid) {
        jstring s = (*env)->NewStringUTF(env, state);
        (*env)->CallVoidMethod(env, cb->listener, mid, s);
        (*env)->DeleteLocalRef(env, s);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
    }
    (*env)->DeleteLocalRef(env, cls);
    if (attached) {
        (*cb->vm)->DetachCurrentThread(cb->vm);
    }
}

static int cb_hostkey(TunnelCallbacks *cb, const char *fingerprint, const char *key_type) {
    if (!cb || !cb->vm || !cb->listener) {
        return 1;
    }
    int attached = 0;
    JNIEnv *env = attach_env(cb->vm, &attached);
    if (!env) {
        return 0;
    }
    int accept = 0;
    jclass cls = (*env)->GetObjectClass(env, cb->listener);
    jmethodID mid = (*env)->GetMethodID(
        env, cls, "onHostKey", "(Ljava/lang/String;Ljava/lang/String;)Z");
    if (mid) {
        jstring fp = (*env)->NewStringUTF(env, fingerprint);
        jstring kt = (*env)->NewStringUTF(env, key_type ? key_type : "unknown");
        accept = (*env)->CallBooleanMethod(env, cb->listener, mid, fp, kt) ? 1 : 0;
        (*env)->DeleteLocalRef(env, fp);
        (*env)->DeleteLocalRef(env, kt);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            accept = 0;
        }
    }
    (*env)->DeleteLocalRef(env, cls);
    if (attached) {
        (*cb->vm)->DetachCurrentThread(cb->vm);
    }
    return accept;
}

static void set_nonblock(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    }
}

static void set_nodelay(int fd) {
    int yes = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &yes, sizeof(yes));
}

static void close_fd(int *fd) {
    if (fd && *fd >= 0) {
        close(*fd);
        *fd = -1;
    }
}

static int connect_tcp(const char *host, int port, char *err, size_t errlen) {
    char portstr[16];
    snprintf(portstr, sizeof(portstr), "%d", port);

    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;

    struct addrinfo *res = NULL;
    int rc = getaddrinfo(host, portstr, &hints, &res);
    if (rc != 0) {
        snprintf(err, errlen, "DNS 失败: %s", gai_strerror(rc));
        return -1;
    }

    int sock = -1;
    for (struct addrinfo *ai = res; ai; ai = ai->ai_next) {
        sock = socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol);
        if (sock < 0) {
            continue;
        }
        set_nodelay(sock);
        int ka = 1;
        setsockopt(sock, SOL_SOCKET, SO_KEEPALIVE, &ka, sizeof(ka));
        if (connect(sock, ai->ai_addr, ai->ai_addrlen) == 0) {
            break;
        }
        close(sock);
        sock = -1;
    }
    freeaddrinfo(res);
    if (sock < 0) {
        snprintf(err, errlen, "连接 %s:%d 失败: %s", host, port, strerror(errno));
        return -1;
    }
    return sock;
}

static int listen_local(int port, char *err, size_t errlen) {
    int fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (fd < 0) {
        snprintf(err, errlen, "创建监听套接字失败: %s", strerror(errno));
        return -1;
    }
    int yes = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        snprintf(err, errlen, "绑定 127.0.0.1:%d 失败: %s", port, strerror(errno));
        close(fd);
        return -1;
    }
    if (listen(fd, LISTEN_BACKLOG) < 0) {
        snprintf(err, errlen, "listen 失败: %s", strerror(errno));
        close(fd);
        return -1;
    }
    set_nonblock(fd);
    return fd;
}

static const char *key_type_name(int type) {
    switch (type) {
        case LIBSSH2_HOSTKEY_TYPE_RSA:
            return "ssh-rsa";
        case LIBSSH2_HOSTKEY_TYPE_DSS:
            return "ssh-dss";
        case LIBSSH2_HOSTKEY_TYPE_ECDSA_256:
            return "ecdsa-sha2-nistp256";
        case LIBSSH2_HOSTKEY_TYPE_ECDSA_384:
            return "ecdsa-sha2-nistp384";
        case LIBSSH2_HOSTKEY_TYPE_ECDSA_521:
            return "ecdsa-sha2-nistp521";
        case LIBSSH2_HOSTKEY_TYPE_ED25519:
            return "ssh-ed25519";
        default:
            return "unknown";
    }
}

static void fingerprint_sha256(LIBSSH2_SESSION *session, char *out, size_t outlen) {
    const char *hash = libssh2_hostkey_hash(session, LIBSSH2_HOSTKEY_HASH_SHA256);
    if (!hash) {
        snprintf(out, outlen, "SHA256:unknown");
        return;
    }
    size_t n = 0;
    n += (size_t)snprintf(out + n, outlen - n, "SHA256:");
    for (int i = 0; i < 32 && n + 3 < outlen; ++i) {
        n += (size_t)snprintf(out + n, outlen - n, "%02x", (unsigned char)hash[i]);
    }
}

static int find_free_conn(Conn *conns) {
    for (int i = 0; i < MAX_CONNS; ++i) {
        if (conns[i].fd < 0) {
            return i;
        }
    }
    return -1;
}

static void close_conn(Conn *c) {
    if (c->channel) {
        libssh2_channel_close(c->channel);
        libssh2_channel_free(c->channel);
        c->channel = NULL;
    }
    close_fd(&c->fd);
    c->local_eof = 0;
    c->remote_eof = 0;
}

static int wait_session(int socket_fd, LIBSSH2_SESSION *session, int timeout_ms) {
    struct pollfd pfd;
    memset(&pfd, 0, sizeof(pfd));
    pfd.fd = socket_fd;
    int dir = libssh2_session_block_directions(session);
    if (dir & LIBSSH2_SESSION_BLOCK_INBOUND) {
        pfd.events |= POLLIN;
    }
    if (dir & LIBSSH2_SESSION_BLOCK_OUTBOUND) {
        pfd.events |= POLLOUT;
    }
    if (pfd.events == 0) {
        pfd.events = POLLIN;
    }
    return poll(&pfd, 1, timeout_ms);
}

static int session_last_error(LIBSSH2_SESSION *session, char *err, size_t errlen) {
    char *msg = NULL;
    int code = libssh2_session_last_error(session, &msg, NULL, 0);
    snprintf(err, errlen, "libssh2(%d): %s", code, msg ? msg : "unknown");
    return code;
}

static int host_equal(const char *a, const char *b) {
    char na[256];
    char nb[256];
    if (!a || !b || !a[0] || !b[0]) {
        return 0;
    }
    while (*a == '[') {
        a++;
    }
    while (*b == '[') {
        b++;
    }
    snprintf(na, sizeof(na), "%s", a);
    snprintf(nb, sizeof(nb), "%s", b);
    size_t la = strlen(na);
    size_t lb = strlen(nb);
    if (la && na[la - 1] == ']') {
        na[la - 1] = '\0';
    }
    if (lb && nb[lb - 1] == ']') {
        nb[lb - 1] = '\0';
    }
    return strcasecmp(na, nb) == 0;
}

static const char *forward_dest_host(const TunnelConfig *cfg) {
    if (!cfg->remote_host || !cfg->remote_host[0] ||
        strcasecmp(cfg->remote_host, "localhost") == 0) {
        return "127.0.0.1";
    }
    if (host_equal(cfg->remote_host, cfg->host)) {
        return "127.0.0.1";
    }
    return cfg->remote_host;
}

static int session_dead(int code) {
    return code == LIBSSH2_ERROR_SOCKET_NONE ||
           code == LIBSSH2_ERROR_SOCKET_DISCONNECT ||
           code == LIBSSH2_ERROR_SOCKET_RECV ||
           code == LIBSSH2_ERROR_SOCKET_SEND ||
           code == LIBSSH2_ERROR_BAD_SOCKET;
}

static void describe_forward_error(int code, char *err, size_t errlen) {
    const char *hint = NULL;
    switch (code) {
        case LIBSSH2_ERROR_TIMEOUT:
            hint = "打开转发通道超时。远端请填云上 Web 的监听地址，访问本机服务用 127.0.0.1，不要填云主机公网 IP";
            break;
        case LIBSSH2_ERROR_CHANNEL_FAILURE:
            hint = "服务器拒绝了端口转发。请检查 sshd 的 AllowTcpForwarding，以及远端端口是否在监听";
            break;
        case LIBSSH2_ERROR_SOCKET_NONE:
        case LIBSSH2_ERROR_SOCKET_DISCONNECT:
        case LIBSSH2_ERROR_SOCKET_RECV:
        case LIBSSH2_ERROR_BAD_SOCKET:
            hint = "SSH 连接已断开";
            break;
        default:
            break;
    }
    if (hint) {
        size_t used = strlen(err);
        if (used + 4 < errlen) {
            snprintf(err + used, errlen - used, "。%s", hint);
        }
    }
}

static LIBSSH2_CHANNEL *open_forward_channel(
    LIBSSH2_SESSION *session,
    const char *dest_host,
    int dest_port,
    const char *orig_ip,
    int orig_port,
    char *err,
    size_t errlen) {
    /* Official libssh2 example opens direct-tcpip in blocking mode, then
       switches to non-blocking for the data pump. Retrying the public API
       after EAGAIN is not reliable and can yield TIMEOUT / SOCKET_NONE. */
    libssh2_session_set_blocking(session, 1);
    libssh2_session_set_timeout(session, 15000);
    libssh2_session_set_read_timeout(session, 15);

    LIBSSH2_CHANNEL *ch = libssh2_channel_direct_tcpip_ex(
        session, dest_host, dest_port, orig_ip, orig_port);

    if (!ch) {
        int code = session_last_error(session, err, errlen);
        describe_forward_error(code, err, errlen);
    }

    libssh2_session_set_blocking(session, 0);
    libssh2_session_set_timeout(session, 0);
    libssh2_session_set_read_timeout(session, 60);
    return ch;
}

static int do_auth(LIBSSH2_SESSION *session, const TunnelConfig *cfg, char *err, size_t errlen) {
    const char *user = cfg->username ? cfg->username : "";
    char *list = libssh2_userauth_list(session, user, (unsigned int)strlen(user));
    if (libssh2_userauth_authenticated(session)) {
        return 0;
    }
    if (!list) {
        session_last_error(session, err, errlen);
        return -1;
    }

    int use_key = cfg->private_key && cfg->private_key[0] != '\0';
    if (use_key) {
        if (!strstr(list, "publickey")) {
            snprintf(err, errlen, "服务器不支持公钥认证，可用: %s", list);
            return -1;
        }
        const char *pass = cfg->passphrase ? cfg->passphrase : "";
        int rc = libssh2_userauth_publickey_frommemory(
            session,
            user,
            strlen(user),
            NULL,
            0,
            cfg->private_key,
            strlen(cfg->private_key),
            pass);
        if (rc) {
            session_last_error(session, err, errlen);
            return -1;
        }
        return 0;
    }

    if (!strstr(list, "password")) {
        snprintf(err, errlen, "服务器不支持密码认证，可用: %s", list);
        return -1;
    }
    const char *password = cfg->password ? cfg->password : "";
    int rc = libssh2_userauth_password(session, user, password);
    if (rc) {
        session_last_error(session, err, errlen);
        return -1;
    }
    return 0;
}

static int pump_local_to_ssh(Conn *c, int ssh_fd, LIBSSH2_SESSION *session) {
    char buf[IO_BUF];
    ssize_t n = recv(c->fd, buf, sizeof(buf), 0);
    if (n == 0) {
        c->local_eof = 1;
        libssh2_channel_send_eof(c->channel);
        return 0;
    }
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            return 0;
        }
        return -1;
    }
    ssize_t off = 0;
    int spins = 0;
    while (off < n) {
        ssize_t w = libssh2_channel_write(c->channel, buf + off, (size_t)(n - off));
        if (w == LIBSSH2_ERROR_EAGAIN) {
            if (++spins > 50) {
                return 0;
            }
            wait_session(ssh_fd, session, 50);
            continue;
        }
        if (w < 0) {
            return -1;
        }
        off += w;
    }
    return 0;
}

static int pump_ssh_to_local(Conn *c) {
    char buf[IO_BUF];
    for (;;) {
        ssize_t n = libssh2_channel_read(c->channel, buf, sizeof(buf));
        if (n == LIBSSH2_ERROR_EAGAIN) {
            break;
        }
        if (n < 0) {
            return -1;
        }
        if (n == 0) {
            break;
        }
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = send(c->fd, buf + off, (size_t)(n - off), 0);
            if (w < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) {
                    /* Rare on loopback; drop remaining rather than buffer. */
                    return 0;
                }
                return -1;
            }
            off += w;
        }
    }
    if (libssh2_channel_eof(c->channel)) {
        c->remote_eof = 1;
        shutdown(c->fd, SHUT_WR);
    }
    return 0;
}

static void *tunnel_thread(void *arg) {
    (void)arg;
    TunnelState *st = &g_state;
    TunnelConfig *cfg = &st->cfg;
    TunnelCallbacks *cb = &st->cb;
    char err[256];
    int ssh_fd = -1;
    int listen_fd = -1;
    LIBSSH2_SESSION *session = NULL;
    Conn conns[MAX_CONNS];
    for (int i = 0; i < MAX_CONNS; ++i) {
        conns[i].fd = -1;
        conns[i].channel = NULL;
        conns[i].local_eof = 0;
        conns[i].remote_eof = 0;
    }

    cb_state(cb, "connecting");
    cb_log(cb, "正在连接 %s:%d ...", cfg->host, cfg->port);

    if (libssh2_init(0) != 0) {
        cb_log(cb, "libssh2_init 失败");
        cb_state(cb, "error");
        goto done;
    }

    ssh_fd = connect_tcp(cfg->host, cfg->port, err, sizeof(err));
    if (ssh_fd < 0) {
        cb_log(cb, "%s", err);
        cb_state(cb, "error");
        goto done;
    }

    session = libssh2_session_init();
    if (!session) {
        cb_log(cb, "无法创建 SSH 会话");
        cb_state(cb, "error");
        goto done;
    }
    libssh2_session_set_blocking(session, 1);

    int rc = libssh2_session_handshake(session, ssh_fd);
    if (rc) {
        session_last_error(session, err, sizeof(err));
        cb_log(cb, "握手失败: %s", err);
        cb_state(cb, "error");
        goto done;
    }

    size_t hk_len = 0;
    int hk_type = 0;
    libssh2_session_hostkey(session, &hk_len, &hk_type);
    char fp[96];
    fingerprint_sha256(session, fp, sizeof(fp));
    const char *kt = key_type_name(hk_type);
    cb_log(cb, "主机密钥 %s %s", kt, fp);
    if (!cb_hostkey(cb, fp, kt)) {
        cb_log(cb, "已拒绝该主机密钥");
        cb_state(cb, "error");
        goto done;
    }

    cb_state(cb, "authenticating");
    if (do_auth(session, cfg, err, sizeof(err)) != 0) {
        cb_log(cb, "认证失败: %s", err);
        cb_state(cb, "error");
        goto done;
    }
    cb_log(cb, "认证成功，用户 %s", cfg->username);

    const char *dest_host = forward_dest_host(cfg);
    if (cfg->remote_host && strcmp(dest_host, cfg->remote_host) != 0) {
        cb_log(cb,
               "远端地址 %s 与 SSH 主机相同，已改为 %s（云主机通常无法访问自己的公网 IP）",
               cfg->remote_host, dest_host);
    }

    listen_fd = listen_local(cfg->local_port, err, sizeof(err));
    if (listen_fd < 0) {
        cb_log(cb, "%s", err);
        cb_state(cb, "error");
        goto done;
    }
    cb_log(cb, "本地转发 127.0.0.1:%d -> %s:%d",
           cfg->local_port, dest_host, cfg->remote_port);
    cb_state(cb, "listening");

    libssh2_keepalive_config(session, 0, 30);
    libssh2_session_set_blocking(session, 0);

    while (!st->stop_requested) {
        struct pollfd pfds[3 + MAX_CONNS];
        int nfds = 0;
        int idx_listen = nfds;
        pfds[nfds].fd = listen_fd;
        pfds[nfds].events = POLLIN;
        pfds[nfds].revents = 0;
        nfds++;

        int idx_wake = nfds;
        pfds[nfds].fd = st->wake_r;
        pfds[nfds].events = POLLIN;
        pfds[nfds].revents = 0;
        nfds++;

        int idx_ssh = nfds;
        pfds[nfds].fd = ssh_fd;
        pfds[nfds].events = POLLIN;
        int dir = libssh2_session_block_directions(session);
        if (dir & LIBSSH2_SESSION_BLOCK_OUTBOUND) {
            pfds[nfds].events |= POLLOUT;
        }
        pfds[nfds].revents = 0;
        nfds++;

        int idx_conn[MAX_CONNS];
        for (int i = 0; i < MAX_CONNS; ++i) {
            idx_conn[i] = -1;
            if (conns[i].fd >= 0 && !conns[i].local_eof) {
                idx_conn[i] = nfds;
                pfds[nfds].fd = conns[i].fd;
                pfds[nfds].events = POLLIN;
                pfds[nfds].revents = 0;
                nfds++;
            }
        }

        int pr = poll(pfds, (nfds_t)nfds, 500);
        if (pr < 0) {
            if (errno == EINTR) {
                continue;
            }
            cb_log(cb, "poll 失败: %s", strerror(errno));
            cb_state(cb, "error");
            break;
        }

        if (pfds[idx_wake].revents & POLLIN) {
            char drain[32];
            while (read(st->wake_r, drain, sizeof(drain)) > 0) {
            }
        }

        int idle = 0;
        libssh2_keepalive_send(session, &idle);

        if (pfds[idx_listen].revents & POLLIN) {
            struct sockaddr_in peer;
            socklen_t plen = sizeof(peer);
            int cfd = accept(listen_fd, (struct sockaddr *)&peer, &plen);
            if (cfd >= 0) {
                int slot = find_free_conn(conns);
                if (slot < 0) {
                    cb_log(cb, "连接数已满，拒绝新连接");
                    close(cfd);
                } else {
                    set_nonblock(cfd);
                    set_nodelay(cfd);
                    char orig_ip[INET_ADDRSTRLEN];
                    if (!inet_ntop(AF_INET, &peer.sin_addr, orig_ip, sizeof(orig_ip))) {
                        snprintf(orig_ip, sizeof(orig_ip), "127.0.0.1");
                    }
                    int orig_port = ntohs(peer.sin_port);
                    LIBSSH2_CHANNEL *ch = open_forward_channel(
                        session,
                        dest_host,
                        cfg->remote_port,
                        orig_ip,
                        orig_port,
                        err,
                        sizeof(err));
                    if (!ch) {
                        cb_log(cb, "direct-tcpip 失败: %s", err);
                        close(cfd);
                        int code = libssh2_session_last_errno(session);
                        if (session_dead(code)) {
                            cb_state(cb, "error");
                            goto done;
                        }
                    } else {
                        conns[slot].fd = cfd;
                        conns[slot].channel = ch;
                        conns[slot].local_eof = 0;
                        conns[slot].remote_eof = 0;
                        cb_log(cb, "已建立转发 %s:%d -> %s:%d",
                               orig_ip, orig_port, dest_host, cfg->remote_port);
                    }
                }
            }
        }

        int ssh_ready = (pfds[idx_ssh].revents & (POLLIN | POLLOUT | POLLHUP | POLLERR)) != 0;
        for (int i = 0; i < MAX_CONNS; ++i) {
            if (conns[i].fd < 0) {
                continue;
            }
            int ready = 0;
            if (idx_conn[i] >= 0 && (pfds[idx_conn[i]].revents & (POLLIN | POLLHUP | POLLERR))) {
                ready = 1;
            }
            if (ready) {
                if (pump_local_to_ssh(&conns[i], ssh_fd, session) != 0) {
                    close_conn(&conns[i]);
                    continue;
                }
            }
            if (conns[i].channel && (ready || ssh_ready || pr == 0)) {
                if (pump_ssh_to_local(&conns[i]) != 0) {
                    close_conn(&conns[i]);
                    continue;
                }
            }
            if (conns[i].local_eof && conns[i].remote_eof) {
                close_conn(&conns[i]);
            }
        }
    }

    cb_state(cb, "stopped");
    cb_log(cb, "隧道已停止");

done:
    for (int i = 0; i < MAX_CONNS; ++i) {
        close_conn(&conns[i]);
    }
    close_fd(&listen_fd);
    if (session) {
        libssh2_session_disconnect(session, "a-lite-ssh stop");
        libssh2_session_free(session);
    }
    close_fd(&ssh_fd);
    libssh2_exit();

    pthread_mutex_lock(&st->lock);
    st->running = 0;
    pthread_mutex_unlock(&st->lock);
    return NULL;
}

int tunnel_start(const TunnelConfig *cfg, const TunnelCallbacks *cb) {
    pthread_mutex_lock(&g_state.lock);
    if (g_state.running) {
        pthread_mutex_unlock(&g_state.lock);
        return -1;
    }
    tunnel_config_free(&g_state.cfg);
    g_state.cfg = cfg_dup(cfg);
    g_state.cb = *cb;
    g_state.stop_requested = 0;

    int pipefd[2];
    if (pipe(pipefd) != 0) {
        pthread_mutex_unlock(&g_state.lock);
        return -2;
    }
    g_state.wake_r = pipefd[0];
    g_state.wake_w = pipefd[1];
    set_nonblock(g_state.wake_r);
    set_nonblock(g_state.wake_w);

    g_state.running = 1;
    if (pthread_create(&g_state.thread, NULL, tunnel_thread, NULL) != 0) {
        g_state.running = 0;
        close_fd(&g_state.wake_r);
        close_fd(&g_state.wake_w);
        pthread_mutex_unlock(&g_state.lock);
        return -3;
    }
    pthread_detach(g_state.thread);
    pthread_mutex_unlock(&g_state.lock);
    return 0;
}

void tunnel_stop(void) {
    pthread_mutex_lock(&g_state.lock);
    g_state.stop_requested = 1;
    if (g_state.wake_w >= 0) {
        char x = 1;
        (void)write(g_state.wake_w, &x, 1);
    }
    pthread_mutex_unlock(&g_state.lock);
}

int tunnel_is_running(void) {
    pthread_mutex_lock(&g_state.lock);
    int r = g_state.running;
    pthread_mutex_unlock(&g_state.lock);
    return r;
}
