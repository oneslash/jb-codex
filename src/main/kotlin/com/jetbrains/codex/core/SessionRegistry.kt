package com.jetbrains.codex.core

import com.intellij.openapi.diagnostic.logger
import com.jetbrains.codex.protocol.JsonRpcClient
import com.jetbrains.codex.protocol.archiveThread
import com.jetbrains.codex.protocol.interruptTurn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

data class SessionInfo(
    val threadId: String,
    val model: String,
    val cwd: String,
    val state: SessionState,
    val activeTurnId: String?
)

sealed class SessionState {
    object Created : SessionState()
    object Configured : SessionState()
    object Active : SessionState()
    object Interrupted : SessionState()
    object Completed : SessionState()
    object Archived : SessionState()
}

/**
 * SessionRegistry tracks active threads and their states.
 * Per spec section 3.3: Tracks threadId, active turns, and replay state.
 */
class SessionRegistry {
    private val log = logger<SessionRegistry>()

    private val sessions = ConcurrentHashMap<String, SessionInfo>()

    private val _sessionEvents = MutableSharedFlow<SessionEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val sessionEvents: SharedFlow<SessionEvent> = _sessionEvents

    private val _activeSessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val activeSessions: StateFlow<List<SessionInfo>> = _activeSessions

    /**
     * Register a new conversation session
     */
    fun registerSession(
        threadId: String,
        model: String,
        cwd: String
    ): SessionInfo {
        val info = SessionInfo(
            threadId = threadId,
            model = model,
            cwd = cwd,
            state = SessionState.Created,
            activeTurnId = null
        )

        sessions[threadId] = info
        updateActiveSessions()

        log.info("Registered session: $threadId (model: $model)")
        _sessionEvents.tryEmit(SessionEvent.Created(threadId))

        return info
    }

    /**
     * Update session state based on lifecycle events
     */
    fun updateSessionState(threadId: String, newState: SessionState) {
        val current = sessions[threadId] ?: return
        val updated = current.copy(state = newState)
        sessions[threadId] = updated
        updateActiveSessions()

        log.info("Session $threadId state: ${newState::class.simpleName}")

        when (newState) {
            SessionState.Configured -> _sessionEvents.tryEmit(SessionEvent.Configured(threadId))
            SessionState.Active -> _sessionEvents.tryEmit(SessionEvent.Active(threadId))
            SessionState.Completed -> _sessionEvents.tryEmit(SessionEvent.Completed(threadId))
            SessionState.Interrupted -> _sessionEvents.tryEmit(SessionEvent.Interrupted(threadId))
            SessionState.Archived -> _sessionEvents.tryEmit(SessionEvent.Archived(threadId))
            else -> {}
        }
    }

    fun handleThreadStarted(threadId: String) {
        updateSessionState(threadId, SessionState.Configured)
    }

    fun markTurnActive(threadId: String, turnId: String) {
        val current = sessions[threadId] ?: return
        val updated = current.copy(state = SessionState.Active, activeTurnId = turnId)
        sessions[threadId] = updated
        updateActiveSessions()
        _sessionEvents.tryEmit(SessionEvent.Active(threadId))
    }

    fun completeTurn(threadId: String) {
        val current = sessions[threadId] ?: return
        val updated = current.copy(state = SessionState.Completed, activeTurnId = null)
        sessions[threadId] = updated
        updateActiveSessions()
        _sessionEvents.tryEmit(SessionEvent.Completed(threadId))
    }

    fun markTurnInterrupted(threadId: String) {
        val current = sessions[threadId] ?: return
        val updated = current.copy(state = SessionState.Interrupted, activeTurnId = null)
        sessions[threadId] = updated
        updateActiveSessions()
        _sessionEvents.tryEmit(SessionEvent.Interrupted(threadId))
    }

    suspend fun interruptActiveTurn(client: JsonRpcClient, threadId: String) {
        val session = sessions[threadId] ?: return
        val turnId = session.activeTurnId ?: return

        log.info("Interrupting turn $turnId in thread $threadId")

        try {
            client.interruptTurn(threadId, turnId)
            markTurnInterrupted(threadId)
        } catch (e: Exception) {
            log.error("Failed to interrupt turn $turnId in $threadId", e)
            throw e
        }
    }

    /**
     * Archive a session and remove its listener
     */
    suspend fun archiveSession(client: JsonRpcClient, threadId: String) {
        if (!sessions.containsKey(threadId)) return

        log.info("Archiving session: $threadId")

        try {
            client.archiveThread(threadId)
            updateSessionState(threadId, SessionState.Archived)
        } catch (e: Exception) {
            log.error("Failed to archive session $threadId", e)
            throw e
        }
    }

    /**
     * Get session info by conversation ID
     */
    fun getSession(threadId: String): SessionInfo? {
        return sessions[threadId]
    }

    /**
     * List all sessions
     */
    fun listSessions(): List<SessionInfo> {
        return sessions.values.toList()
    }

    /**
     * List active (non-archived, non-completed) sessions
     */
    fun listActiveSessions(): List<SessionInfo> {
        return sessions.values.filter {
            it.state !in listOf(SessionState.Archived, SessionState.Completed)
        }
    }

    /**
     * Remove a session from tracking
     */
    fun removeSession(threadId: String) {
        sessions.remove(threadId)
        updateActiveSessions()
        log.info("Removed session: $threadId")
    }

    /**
     * Cleanup all sessions and remove all listeners
     */
    suspend fun shutdown() {
        log.info("Shutting down all sessions")
        sessions.clear()
        updateActiveSessions()
    }

    private fun updateActiveSessions() {
        _activeSessions.value = listActiveSessions()
    }
}

/**
 * Events emitted by the SessionRegistry
 */
sealed class SessionEvent {
    data class Created(val threadId: String) : SessionEvent()
    data class Configured(val threadId: String) : SessionEvent()
    data class Active(val threadId: String) : SessionEvent()
    data class Completed(val threadId: String) : SessionEvent()
    data class Interrupted(val threadId: String) : SessionEvent()
    data class Archived(val threadId: String) : SessionEvent()
}
