package com.jetbrains.codex.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

/**
 * Per spec section 11: Configuration surface for Codex plugin
 */
@Service(Service.Level.PROJECT)
@State(
    name = "CodexSettings",
    storages = [Storage("codex.xml")]
)
class CodexSettings : PersistentStateComponent<CodexSettings.State> {

    private var myState = State()

    companion object {
        fun getInstance(project: Project): CodexSettings {
            return project.service<CodexSettings>()
        }

        fun toServerSandboxMode(value: String): String {
            return when (value) {
                "readOnly", "workspaceWrite", "dangerFullAccess" -> value
                else -> when (value.lowercase()) {
                    "read-only", "readonly" -> "readOnly"
                    "workspace-write", "workspacewrite" -> "workspaceWrite"
                    "danger-full-access", "dangerfullaccess" -> "dangerFullAccess"
                    else -> value
                }
            }
        }
    }

    override fun getState(): State {
        return myState
    }

    override fun loadState(state: State) {
        myState = state
    }

    data class State(
        // Codex Configuration
        var codexBinaryPath: String = "",
        var extraCliFlags: String = "",

        // Conversation Defaults
        var defaultProfile: String = "",
        var defaultSummaryMode: String = "auto",

        var approvalPolicy: String = "onRequest",

        var sandboxMode: String = "workspace-write",
        var sandboxNetworkAccess: Boolean = false,

        // Telemetry
        var telemetryEnabled: Boolean = true,
        var logLevel: String = "info"
    )
}

/**
 * Enums for settings validation
 */
object CodexSettingsConstants {
    val SUMMARY_OPTIONS = listOf("auto", "concise", "detailed", "none")
    val SANDBOX_MODES = listOf("read-only", "workspace-write", "danger-full-access")
    val LOG_LEVELS = listOf("error", "warn", "info", "debug")
}
