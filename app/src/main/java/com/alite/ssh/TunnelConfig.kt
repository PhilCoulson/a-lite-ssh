package com.alite.ssh

data class TunnelConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val privateKey: String?,
    val passphrase: String?,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    val trustOnFirstUse: Boolean,
    val ignoreHostKeyMismatch: Boolean,
)
