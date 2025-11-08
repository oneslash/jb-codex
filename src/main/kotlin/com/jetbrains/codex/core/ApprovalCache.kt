package com.jetbrains.codex.core

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache for "approve for session" decisions.
 * Per spec section 3.5: "Persists per-session 'approve similar' decisions"
 */
class ApprovalCache {

    private val execApprovals = ConcurrentHashMap<String, ConcurrentHashMap<String, ApprovalDecision>>()
    private val patchApprovals = ConcurrentHashMap<String, ConcurrentHashMap<String, ApprovalDecision>>()

    data class ApprovalDecision(
        val decision: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Check if a command has been pre-approved for the session
     */
    fun checkExecApproval(threadId: String, command: List<String>, cwd: String): String? {
        val key = generateExecKey(command, cwd)
        return execApprovals[threadId]?.get(key)?.decision
    }

    /**
     * Cache an exec approval decision for the session
     */
    fun cacheExecApproval(threadId: String, command: List<String>, cwd: String, decision: String) {
        if (decision == "approved_for_session") {
            val key = generateExecKey(command, cwd)
            execApprovals
                .computeIfAbsent(threadId) { ConcurrentHashMap() }[key] = ApprovalDecision("approved")
        }
    }

    /**
     * Check if a patch has been pre-approved for the session
     */
    fun checkPatchApproval(threadId: String, files: Set<String>): String? {
        val key = generatePatchKey(files)
        return patchApprovals[threadId]?.get(key)?.decision
    }

    /**
     * Cache a patch approval decision for the session
     */
    fun cachePatchApproval(threadId: String, files: Set<String>, decision: String) {
        if (decision == "approved_for_session") {
            val key = generatePatchKey(files)
            patchApprovals
                .computeIfAbsent(threadId) { ConcurrentHashMap() }[key] = ApprovalDecision("approved")
        }
    }

    /**
     * Clear all cached approvals (e.g., on session end)
     */
    fun clear() {
        execApprovals.clear()
        patchApprovals.clear()
    }

    /**
     * Clear cached approvals for a specific thread session.
     */
    fun clearThread(threadId: String) {
        execApprovals.remove(threadId)
        patchApprovals.remove(threadId)
    }

    /**
     * Generate a consistent key for exec commands.
     * Uses normalized command (first element) + cwd for matching similar commands.
     */
    private fun generateExecKey(command: List<String>, cwd: String): String {
        if (command.isEmpty()) {
            return "unknown:$cwd"
        }

        val normalized = command.joinToString(" ") { token ->
            token.trim().lowercase(Locale.ROOT)
        }
        return "$normalized@$cwd"
    }

    /**
     * Generate a consistent key for patch approvals.
     * Uses sorted file paths for matching.
     */
    private fun generatePatchKey(files: Set<String>): String {
        if (files.isEmpty()) return "patch:none"
        val normalizedPaths = files.map { it.trim() }.sorted()
        return normalizedPaths.joinToString("|")
    }

    /**
     * Get stats about cached approvals
     */
    fun getStats(): CacheStats {
        return CacheStats(
            execApprovals = execApprovals.values.sumOf { it.size },
            patchApprovals = patchApprovals.values.sumOf { it.size }
        )
    }

    data class CacheStats(
        val execApprovals: Int,
        val patchApprovals: Int
    )
}
