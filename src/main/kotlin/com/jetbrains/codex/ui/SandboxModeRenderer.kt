package com.jetbrains.codex.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.jetbrains.codex.settings.SandboxModePreset
import java.awt.Component
import java.awt.Font
import javax.swing.*

class SandboxModeRenderer : ListCellRenderer<SandboxModePreset> {
    override fun getListCellRendererComponent(
        list: JList<out SandboxModePreset>?,
        value: SandboxModePreset?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        if (value == null) {
            return JBLabel("")
        }

        if (index == -1) {
            val label = JBLabel(value.displayName)
            label.font = label.font.deriveFont(Font.PLAIN, 13f)
            label.border = JBUI.Borders.empty(4, 8)
            return label
        }

        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8, 12)

        val titleLabel = JBLabel(value.displayName)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 13f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT

        val descLabel = JBLabel(value.description)
        descLabel.font = descLabel.font.deriveFont(Font.PLAIN, 11f)
        descLabel.foreground = JBColor(0x808080, 0x888888)
        descLabel.alignmentX = Component.LEFT_ALIGNMENT

        panel.add(titleLabel)
        panel.add(Box.createVerticalStrut(JBUI.scale(3)))
        panel.add(descLabel)

        if (isSelected) {
            panel.background = JBColor(0xE3F2FD, 0x2B3A47)
            titleLabel.foreground = JBColor(0x000000, 0xE0E0E0)
        } else {
            panel.background = JBColor(0xFFFFFF, 0x2B2D30)
            titleLabel.foreground = JBColor(0x000000, 0xBBBBBB)
        }

        return panel
    }
}

