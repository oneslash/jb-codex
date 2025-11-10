package com.jetbrains.codex.settings

data class SandboxModePreset(
    val value: String,
    val displayName: String,
    val description: String,
    val isDefault: Boolean = false
) {
    override fun toString(): String = displayName

    companion object {
        val PRESETS = listOf(
            SandboxModePreset(
                value = "read-only",
                displayName = "Read-only",
                description = "Agent can only read files, no modifications allowed"
            ),
            SandboxModePreset(
                value = "workspace-write",
                displayName = "Workspace write",
                description = "Agent can read and write files within the workspace",
                isDefault = true
            ),
            SandboxModePreset(
                value = "danger-full-access",
                displayName = "Full access",
                description = "Agent has unrestricted access to the filesystem"
            )
        )

        fun getDefault(): SandboxModePreset = PRESETS.first { it.isDefault }
        
        fun find(value: String): SandboxModePreset? {
            return PRESETS.firstOrNull { it.value == value }
        }
    }
}




