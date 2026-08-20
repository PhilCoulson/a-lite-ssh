package com.alite.ssh

import java.util.UUID

data class PortMapping(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val localPort: Int,
    val remoteHost: String = DEFAULT_REMOTE_HOST,
    val remotePort: Int,
    val enabled: Boolean = true,
) {
    fun effectiveRemoteHost(): String = remoteHost.ifBlank { DEFAULT_REMOTE_HOST }

    fun title(): String = name.ifBlank { "$localPort → ${effectiveRemoteHost()}:$remotePort" }

    fun display(): String {
        val route = "127.0.0.1:$localPort  →  ${effectiveRemoteHost()}:$remotePort"
        return if (name.isBlank()) route else "${title()}\n$route"
    }

    companion object {
        const val DEFAULT_REMOTE_HOST = "127.0.0.1"
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
