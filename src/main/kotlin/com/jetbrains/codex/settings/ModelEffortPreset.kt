package com.jetbrains.codex.settings

data class ModelEffortPreset(
    val model: String,
    val effort: String,
    val displayName: String,
    val description: String,
    val isDefault: Boolean = false,
    val isSeparator: Boolean = false
) {
    override fun toString(): String = displayName

    companion object {
        val SEPARATOR = ModelEffortPreset("", "", "───", "", false, true)

        val PRESETS = listOf(
            ModelEffortPreset(
                model = "gpt-5-codex",
                effort = "low",
                displayName = "gpt-5-codex • Low",
                description = "Fastest responses with limited reasoning",
                isDefault = true
            ),
            ModelEffortPreset(
                model = "gpt-5-codex",
                effort = "medium",
                displayName = "gpt-5-codex • Medium",
                description = "Dynamically adjusts reasoning based on the task"
            ),
            ModelEffortPreset(
                model = "gpt-5-codex",
                effort = "high",
                displayName = "gpt-5-codex • High",
                description = "Maximizes reasoning depth for complex or ambiguous problems"
            ),
            
            SEPARATOR,
            
            ModelEffortPreset(
                model = "gpt-5-codex-mini",
                effort = "medium",
                displayName = "gpt-5-codex-mini • Medium",
                description = "Cheaper, faster, but less capable"
            ),
            ModelEffortPreset(
                model = "gpt-5-codex-mini",
                effort = "high",
                displayName = "gpt-5-codex-mini • High",
                description = "Cheaper, faster, but less capable"
            ),
            
            SEPARATOR,
            
            ModelEffortPreset(
                model = "gpt-5",
                effort = "minimal",
                displayName = "gpt-5 • Minimal",
                description = "Fastest responses with little reasoning"
            ),
            ModelEffortPreset(
                model = "gpt-5",
                effort = "low",
                displayName = "gpt-5 • Low",
                description = "Balances speed with some reasoning"
            ),
            ModelEffortPreset(
                model = "gpt-5",
                effort = "medium",
                displayName = "gpt-5 • Medium",
                description = "Solid balance of reasoning depth and latency"
            ),
            ModelEffortPreset(
                model = "gpt-5",
                effort = "high",
                displayName = "gpt-5 • High",
                description = "Maximizes reasoning depth for complex problems"
            )
        )

        fun getDefault(): ModelEffortPreset = PRESETS.first { it.isDefault }
        
        fun find(model: String, effort: String): ModelEffortPreset? {
            return PRESETS.firstOrNull { !it.isSeparator && it.model == model && it.effort == effort }
        }
    }
}

