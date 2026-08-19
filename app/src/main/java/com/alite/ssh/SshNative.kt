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
        localPorts: IntArray,
        remotePorts: IntArray,
        remoteHosts: Array<String>,
        listener: Listener,
    ): Int

    external fun nativeReplaceForwards(
        localPorts: IntArray,
        remotePorts: IntArray,
        remoteHosts: Array<String>,
    ): Int

    external fun nativeStop()

    external fun nativeIsRunning(): Boolean

    companion object {
        init {
            System.loadLibrary("alite_ssh")
        }
    }
}
