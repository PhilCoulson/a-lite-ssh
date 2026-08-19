package com.alite.ssh

import java.util.UUID

data class PortMapping(
    val id: String = UUID.randomUUID().toString(),
    val localPort: Int,
    val remotePort: Int,
    val enabled: Boolean = true,
) {
    fun display(sshHost: String): String {
        val host = sshHost.ifBlank { "SSH主机" }
        return "127.0.0.1:$localPort  →  $host:$remotePort"
    }
}

data class TunnelConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String?,
    val privateKey: String?,
    val passphrase: String?,
    val mappings: List<PortMapping>,
    val trustOnFirstUse: Boolean,
    val ignoreHostKeyMismatch: Boolean,
) {
    fun enabledMappings(): List<PortMapping> = mappings.filter { it.enabled }
}
