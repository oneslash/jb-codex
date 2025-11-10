package com.jetbrains.codex.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Per spec section 11: Settings UI for Codex configuration
 */
class CodexSettingsConfigurable(private val project: Project) : Configurable {

    private var codexPathField: TextFieldWithBrowseButton? = null
    private var extraFlagsField: JBTextField? = null
    private var profileField: JBTextField? = null
    private var summaryCombo: ComboBox<String>? = null
    private var sandboxModeCombo: ComboBox<String>? = null
    private var networkAccessCheckbox: JBCheckBox? = null
    private var telemetryCheckbox: JBCheckBox? = null
    private var logLevelCombo: ComboBox<String>? = null

    override fun getDisplayName(): String = "Codex"

    override fun createComponent(): JComponent {
        val settings = CodexSettings.getInstance(project).state

        // Codex Binary Path
        codexPathField = TextFieldWithBrowseButton().apply {
            text = settings.codexBinaryPath
            addBrowseFolderListener(
                "Select Codex Binary",
                "Choose the codex executable",
                project,
                FileChooserDescriptorFactory.createSingleLocalFileDescriptor()
            )
        }

        // Extra CLI Flags
        extraFlagsField = JBTextField(settings.extraCliFlags).apply {
            toolTipText = "Additional CLI flags passed to codex app-server (e.g., -c key=value)"
        }

        // Profile
        profileField = JBTextField(settings.defaultProfile).apply {
            toolTipText = "Optional profile name"
        }

        // Summary Mode
        summaryCombo = ComboBox(CodexSettingsConstants.SUMMARY_OPTIONS.toTypedArray()).apply {
            selectedItem = settings.defaultSummaryMode
            toolTipText = "Summary mode: auto, concise, detailed, none"
        }

        sandboxModeCombo = ComboBox(CodexSettingsConstants.SANDBOX_MODES.toTypedArray()).apply {
            selectedItem = settings.sandboxMode
            toolTipText = "Sandbox mode: read-only, workspace-write, danger-full-access"
        }

        // Network Access
        networkAccessCheckbox = JBCheckBox("Allow Network Access", settings.sandboxNetworkAccess).apply {
            toolTipText = "Allow the agent to access the network"
        }

        // Telemetry
        telemetryCheckbox = JBCheckBox("Enable Telemetry", settings.telemetryEnabled).apply {
            toolTipText = "Send anonymous usage data to improve the plugin"
        }

        // Log Level
        logLevelCombo = ComboBox(CodexSettingsConstants.LOG_LEVELS.toTypedArray()).apply {
            selectedItem = settings.logLevel
            toolTipText = "Log verbosity level"
        }

        // Build the form
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Codex Binary Path:"), codexPathField!!)
            .addTooltip("Path to the codex executable. Leave empty to use PATH.")
            .addVerticalGap(8)

            .addLabeledComponent(JBLabel("Extra CLI Flags:"), extraFlagsField!!)
            .addVerticalGap(16)

            .addComponent(TitledSeparator("Conversation Defaults"))
            .addLabeledComponent(JBLabel("Profile:"), profileField!!)
            .addLabeledComponent(JBLabel("Summary Mode:"), summaryCombo!!)
            .addVerticalGap(16)

            .addComponent(TitledSeparator("Security & Permissions"))
            .addLabeledComponent(JBLabel("Sandbox Mode:"), sandboxModeCombo!!)
            .addComponent(networkAccessCheckbox!!)
            .addVerticalGap(16)

            .addComponent(TitledSeparator("Telemetry & Logging"))
            .addComponent(telemetryCheckbox!!)
            .addLabeledComponent(JBLabel("Log Level:"), logLevelCombo!!)

            .addComponentFillVertically(JPanel(), 0)
            .panel.apply {
                border = JBUI.Borders.empty(10)
            }
    }

    override fun isModified(): Boolean {
        val settings = CodexSettings.getInstance(project).state
        return codexPathField?.text != settings.codexBinaryPath ||
                extraFlagsField?.text != settings.extraCliFlags ||
                profileField?.text != settings.defaultProfile ||
                summaryCombo?.selectedItem != settings.defaultSummaryMode ||
                sandboxModeCombo?.selectedItem != settings.sandboxMode ||
                networkAccessCheckbox?.isSelected != settings.sandboxNetworkAccess ||
                telemetryCheckbox?.isSelected != settings.telemetryEnabled ||
                logLevelCombo?.selectedItem != settings.logLevel
    }

    override fun apply() {
        val settings = CodexSettings.getInstance(project).state
        settings.codexBinaryPath = codexPathField?.text?.trim() ?: ""
        settings.extraCliFlags = extraFlagsField?.text?.trim() ?: ""
        settings.defaultProfile = profileField?.text?.trim() ?: ""
        settings.defaultSummaryMode = summaryCombo?.selectedItem as? String ?: "auto"
        settings.sandboxMode = sandboxModeCombo?.selectedItem as? String ?: "workspace-write"
        settings.sandboxNetworkAccess = networkAccessCheckbox?.isSelected ?: false
        settings.telemetryEnabled = telemetryCheckbox?.isSelected ?: true
        settings.logLevel = logLevelCombo?.selectedItem as? String ?: "info"
    }

    override fun reset() {
        val settings = CodexSettings.getInstance(project).state
        codexPathField?.text = settings.codexBinaryPath
        extraFlagsField?.text = settings.extraCliFlags
        profileField?.text = settings.defaultProfile
        summaryCombo?.selectedItem = settings.defaultSummaryMode
        sandboxModeCombo?.selectedItem = settings.sandboxMode
        networkAccessCheckbox?.isSelected = settings.sandboxNetworkAccess
        telemetryCheckbox?.isSelected = settings.telemetryEnabled
        logLevelCombo?.selectedItem = settings.logLevel
    }
}
