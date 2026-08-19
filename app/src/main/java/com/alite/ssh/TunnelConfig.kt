package com.alite.ssh

import java.util.UUID

data class PortMapping(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val localPort: Int,
    val remotePort: Int,
    val enabled: Boolean = true,
) {
    fun title(): String = name.ifBlank { "$localPort → $remotePort" }

    fun display(sshHost: String): String {
        val host = sshHost.ifBlank { "SSH主机" }
        val route = "127.0.0.1:$localPort  →  $host:$remotePort"
        return if (name.isBlank()) route else "${title()}\n$route"
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
