package com.alite.ssh

class SshNative {
    interface Listener {
        fun onState(state: String)
        fun onLog(message: String)
        fun onHostKey(fingerprint: String, keyType: String): Boolean
    }

    external fun nativeStart(
        host: String,
        port: Int,
        username: String,
        password: String?,
        privateKey: String?,
        passphrase: String?,
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
        listener: Listener,
    ): Int

    external fun nativeStop()

    external fun nativeIsRunning(): Boolean

    companion object {
        init {
            System.loadLibrary("alite_ssh")
        }
    }
}
