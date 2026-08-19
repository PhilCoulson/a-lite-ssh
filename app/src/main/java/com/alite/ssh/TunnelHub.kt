package com.alite.ssh

import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

object TunnelHub {
    data class Snapshot(
        val state: String,
        val logs: List<String>,
        val config: TunnelConfig?,
    )

    interface Observer {
        fun onTunnelEvent(snapshot: Snapshot)
    }

    private val observers = CopyOnWriteArrayList<Observer>()
    private val logBuffer = ArrayDeque<String>()
    @Volatile var state: String = "idle"
        private set
    @Volatile var config: TunnelConfig? = null

    fun addObserver(observer: Observer) {
        observers.add(observer)
        observer.onTunnelEvent(snapshot())
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    @Synchronized
    fun setState(value: String) {
        state = value
        emit()
    }

    @Synchronized
    fun appendLog(line: String) {
        if (logBuffer.size >= MAX_LOGS) {
            logBuffer.removeFirst()
        }
        logBuffer.addLast(line)
        emit()
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(state, logBuffer.toList(), config)

    @Synchronized
    fun resetLogs() {
        logBuffer.clear()
        emit()
    }

    private fun emit() {
        val snap = snapshot()
        observers.forEach { it.onTunnelEvent(snap) }
    }

    private const val MAX_LOGS = 200
}
