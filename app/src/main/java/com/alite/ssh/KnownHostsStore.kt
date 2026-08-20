package com.alite.ssh

import android.content.Context

class KnownHostsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun fingerprint(host: String, port: Int): String? = prefs.getString(key(host, port), null)

    fun save(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(key(host, port), fingerprint).apply()
    }

    fun decide(
        host: String,
        port: Int,
        fingerprint: String,
        trustOnFirstUse: Boolean,
        ignoreMismatch: Boolean,
    ): HostKeyDecision {
        val saved = fingerprint(host, port)
        return when {
            saved == null && trustOnFirstUse -> {
                save(host, port, fingerprint)
                HostKeyDecision.AcceptedFirstSeen
            }
            saved == null -> HostKeyDecision.RejectedUnknown
            saved == fingerprint -> HostKeyDecision.Matched
            ignoreMismatch -> {
                save(host, port, fingerprint)
                HostKeyDecision.AcceptedMismatch
            }
            else -> HostKeyDecision.RejectedMismatch
        }
    }

    private fun key(host: String, port: Int) = "$host:$port"

    companion object {
        private const val PREFS = "known_hosts"
    }
}

enum class HostKeyDecision {
    Matched,
    AcceptedFirstSeen,
    AcceptedMismatch,
    RejectedUnknown,
    RejectedMismatch,
}
