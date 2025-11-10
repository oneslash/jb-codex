package com.jetbrains.codex.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.ColorUtil
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.*
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.IconUtil
import com.intellij.util.execution.ParametersListUtil
import com.intellij.util.ui.JBUI
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.UIUtil
import com.jetbrains.codex.core.CodexService
import com.jetbrains.codex.core.ServiceState
import com.jetbrains.codex.core.SessionEvent
import com.jetbrains.codex.settings.CodexSettings
import com.jetbrains.codex.settings.CodexSettingsConstants
import com.jetbrains.codex.settings.ModelEffortPreset
import com.jetbrains.codex.settings.SandboxModePreset
import com.jetbrains.codex.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.accessibility.AccessibleContext
import javax.swing.*
import javax.swing.Scrollable
import javax.swing.text.BadLocationException
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.html.HTMLEditorKit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

@OptIn(FlowPreview::class)
class CodexChatPanel(private val project: Project) : JBPanel<CodexChatPanel>(BorderLayout()), Disposable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val service = project.service<CodexService>()
    
    private val transcriptPane = JTextPane().apply {
        isEditable = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
    }
    private val reasoningPane = JTextPane().apply {
        isEditable = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
    }
    private val inputArea = JBTextArea()
    private val attachButton = object : JButton() {
        init {
            isOpaque = false
            border = JBUI.Borders.empty()
            isFocusPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(JBUI.scale(36), JBUI.scale(36))
            toolTipText = "Attach files"
            putClientProperty(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, "Attach files")
        }

        override fun paintComponent(g: Graphics) {
            val colors = attachButtonColors
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = height
            val bg = if (model.isPressed) colors.backgroundPressed else colors.background
            val borderColor = if (model.isPressed) colors.borderPressed else colors.border
            g2.color = bg
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = borderColor
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

            val ix = (width - attachIcon.iconWidth) / 2
            val iy = (height - attachIcon.iconHeight) / 2
            attachIcon.paintIcon(this, g2, ix, iy)
            g2.dispose()
        }
    }
    private enum class ComposerActionState { SEND, ABORT }

    private data class ComposerButtonColors(
        val background: JBColor,
        val backgroundPressed: JBColor,
        val border: JBColor,
        val borderPressed: JBColor
    )

    private val sendButtonColors = ComposerButtonColors(
        background = JBColor(0xEEF2FF, 0x2F3036),
        backgroundPressed = JBColor(0xDCE3FF, 0x3A3F47),
        border = JBColor(0xC9D4F4, 0x4F5668),
        borderPressed = JBColor(0xAFC1F2, 0x5A6275)
    )
    private val abortButtonColors = ComposerButtonColors(
        background = JBColor(0xFDECEA, 0x3B2727),
        backgroundPressed = JBColor(0xF9D6CF, 0x4A2F2F),
        border = JBColor(0xF4B9AE, 0x664040),
        borderPressed = JBColor(0xEC9E92, 0x7A4F4F)
    )
    private val attachButtonColors = ComposerButtonColors(
        background = JBColor(0xEEF5FF, 0x2B303A),
        backgroundPressed = JBColor(0xDBE9FF, 0x353B45),
        border = JBColor(0xC1D4FF, 0x454C58),
        borderPressed = JBColor(0xA8C4FF, 0x525A69)
    )

    private val sendIcon: Icon = IconUtil.scale(AllIcons.General.ChevronUp, null, JBUIScale.scale(1.35f))
    private val attachIcon: Icon = IconUtil.scale(AllIcons.General.Add, null, JBUIScale.scale(1.1f))
    private val abortIcon: Icon = AllIcons.Actions.Suspend
    private var composerActionState = ComposerActionState.SEND
    private val composerActionButton = object : JButton() {
        init {
            isOpaque = false
            border = JBUI.Borders.empty()
            isFocusPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(JBUI.scale(40), JBUI.scale(40))
            toolTipText = "Send message"
            putClientProperty(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, "Send message")
        }

        override fun paintComponent(g: Graphics) {
            val colors = when (composerActionState) {
                ComposerActionState.SEND -> sendButtonColors
                ComposerActionState.ABORT -> abortButtonColors
            }

            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = height
            val bg = if (model.isPressed) colors.backgroundPressed else colors.background
            val borderColor = if (model.isPressed) colors.borderPressed else colors.border
            g2.color = bg
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            g2.color = borderColor
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)

            val icon = when (composerActionState) {
                ComposerActionState.SEND -> sendIcon
                ComposerActionState.ABORT -> abortIcon
            }
            val ix = (width - icon.iconWidth) / 2
            val iy = (height - icon.iconHeight) / 2
            icon.paintIcon(this, g2, ix, iy)
            g2.dispose()
        }
    }
    private val modelEffortCombo = ComboBox(ModelEffortPreset.PRESETS.toTypedArray()).apply {
        renderer = ModelEffortComboBoxRenderer()
        selectedItem = ModelEffortPreset.getDefault()
    }
    private val sandboxModeCombo = ComboBox(SandboxModePreset.PRESETS.toTypedArray()).apply {
        renderer = SandboxModeRenderer()
        val settings = CodexSettings.getInstance(project).state
        selectedItem = SandboxModePreset.find(settings.sandboxMode) ?: SandboxModePreset.getDefault()
    }
    private val planAutoContextButton = JButton("Auto context").apply {
        isEnabled = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor(0xC9D4F4, 0x414756), 1),
            JBUI.Borders.empty(6, 16)
        )
        isContentAreaFilled = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { injectPlanContextIntoComposer() }
    }
    private val planAttachmentButton = JButton("+").apply {
        toolTipText = "Attach files for Codex context"
        margin = JBUI.emptyInsets()
        preferredSize = Dimension(JBUI.scale(32), JBUI.scale(32))
        background = JBColor(0xE2E8F8, 0x3A3F4C)
        foreground = JBColor(0x1B2F6B, 0xAECBFA)
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor(0xCAD5F3, 0x454C5E), 1),
            JBUI.Borders.empty(4)
        )
        isFocusPainted = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { attachButton.doClick() }
    }

    private val attachmentChipsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
        isOpaque = false
    }

    private val outlineColor = JBColor(0xD0D7DE, 0x3C3F41)

    private val connectionStatusLabel = JBLabel("Disconnected").apply {
        icon = AllIcons.General.BalloonWarning
    }
    private val usageLabel = JBLabel("—").apply {
        foreground = JBColor.GRAY
    }
    private val newSessionButton = JButton("New").apply {
        toolTipText = "Start a new Codex session"
        putClientProperty(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, "Start a new Codex session")
        isEnabled = false
        addActionListener { handleNewSessionRequest() }
    }

    private lateinit var planPanelWrapper: JComponent
    private lateinit var planStepsContainer: JBPanel<JBPanel<*>>
    private lateinit var planProgressLabel: JBLabel
    private lateinit var planExplanationLabel: JBLabel
    private val planProgressBadge = PlanProgressBadge()
    private var activePlanSteps: List<PlanStepView> = emptyList()

    private var currentModelLabel: String = "—"

    private val timelineItems = mutableListOf<TimelineItem>()
    private val timelineContainer = object : JBPanel<JBPanel<*>>(VerticalLayout(8)), Scrollable {
        init {
            isOpaque = false
            border = JBUI.Borders.empty()
            background = UIUtil.getPanelBackground()
        }

        override fun getPreferredScrollableViewportSize(): Dimension? = null
        override fun getScrollableUnitIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = JBUI.scale(32)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = JBUI.scale(200)
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
    private val timelineEmptyLabel = JBLabel("Timeline will appear once events stream in").apply {
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(12, 6)
    }
    private lateinit var timelineScrollPane: JBScrollPane
    private val timelineIdGenerator = AtomicLong(0)
    private val expandedTimelineItems = mutableSetOf<Long>()
    private val timelineItemKeys = mutableMapOf<String, Long>()
    private val dismissedPinnedIssueIds = mutableSetOf<Long>()
    private val reasoningTimelineBuffers = mutableMapOf<String, StringBuilder>()
    private val agentResponseTimelineBuffers = mutableMapOf<String, StringBuilder>()
    private var timelineAutoScroll = true
    private var suppressTimelineScrollListener = false
    private lateinit var pinnedIssuesWrapper: JBPanel<JBPanel<*>>
    private lateinit var pinnedIssuesList: JBPanel<JBPanel<*>>
    private val statusBadgeFont: Font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size - 1f)

    private val jsonPrinter = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = false
    }

    private var threadId: String? = null
    private var threadReady = false
    private var activeTurnId: String? = null
    private val attachedFiles = mutableListOf<String>()
    private val pendingMessages = ArrayDeque<OutgoingMessage>()
    private val startMutex = Mutex()
    private val outgoingMutex = Mutex()
    private val queueProcessingMutex = Mutex()
    private var currentTaskTimelineKey: String? = null

    // Usage tracking
    private var tokenUsageSnapshot = TokenUsageSnapshot()
    private var rateLimitSnapshot: RateLimitSnapshot? = null

    // Conflated flows for UI updates to prevent flood during deltas
    private val _transcriptUpdates = MutableSharedFlow<TranscriptChunk>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    // Accumulators for delta content
    private val transcriptBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private val timelineRefreshRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val timelineCardCache = mutableMapOf<Long, TimelineCard>()

    private val transcriptAttributes = EnumMap<TranscriptStyle, SimpleAttributeSet>(TranscriptStyle::class.java).apply {
        enumValues<TranscriptStyle>().forEach { style ->
            val attr = SimpleAttributeSet()
            StyleConstants.setForeground(attr, style.color)
            put(style, attr)
        }
    }
    private val reasoningAttributes = SimpleAttributeSet().apply {
        StyleConstants.setForeground(this, JBColor(0x6A1B9A, 0xCE93D8))
    }
    private val markdownFlavour = CommonMarkFlavourDescriptor()
    private val resetTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    private var notificationJob: Job? = null
    private var approvalJob: Job? = null
    private var conversationInitJob: Job? = null
    private var sessionReadyFallbackJob: Job? = null

    init {
        setupUI()
        setupListeners()
        observeServiceState()
        setupConflatedFlows()

        // Composer starts in SEND mode; it switches to abort when a turn is active
        setComposerActionState(ComposerActionState.SEND)

        val settings = CodexSettings.getInstance(project).state

        refreshAttachmentChips()
        val currentPreset = modelEffortCombo.selectedItem as? ModelEffortPreset ?: ModelEffortPreset.getDefault()
        updateThreadMetadata(currentPreset.model)
    }

    private fun setupConflatedFlows() {
        // Conflate transcript updates and batch to EDT
        scope.launch {
            _transcriptUpdates
                .debounce(16) // ~60fps max update rate
                .collect { chunk ->
                    SwingUtilities.invokeLater {
                        appendChunkToPane(transcriptPane, chunk)
                    }
                }
        }

        scope.launch {
            timelineRefreshRequests
                .debounce(24)
                .collect {
                    refreshTimeline()
                }
        }
    }
    
    private fun setupUI() {
        border = JBUI.Borders.empty()
        add(createHeaderPanel(), BorderLayout.NORTH)
        add(createBodyPanel(), BorderLayout.CENTER)
        add(createComposerPanel(), BorderLayout.SOUTH)
    }

    private fun createHeaderPanel(): JComponent {
        val leftCol = JBPanel<JBPanel<*>>(VerticalLayout(4)).apply {
            isOpaque = false
            add(JBLabel("Codex Session").apply {
                font = font.deriveFont(font.style or Font.BOLD, font.size + 2f)
            })
            add(connectionStatusLabel)
        }

        val rightCol = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(newSessionButton)
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(outlineColor, 0, 0, 1, 0),
                JBUI.Borders.empty(10, 12)
            )
            add(leftCol, BorderLayout.CENTER)
            add(rightCol, BorderLayout.EAST)
        }
    }

    private fun createBodyPanel(): JComponent {
        val planPanel = createPlanPanel()
        planPanelWrapper = planPanel
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(createTimelinePanel(), BorderLayout.CENTER)
            add(planPanel, BorderLayout.SOUTH)
        }
    }

    private fun createPlanPanel(): JComponent {
        planStepsContainer = JBPanel<JBPanel<*>>(VerticalLayout(12)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(12)
        }

        planProgressLabel = JBLabel("Plan ready").apply {
            font = font.deriveFont(font.style or Font.BOLD, font.size + 1f)
        }
        planExplanationLabel = JBLabel().apply {
            foreground = JBColor(0x60718B, 0x9AA5C0)
            isVisible = false
        }
        planProgressBadge.setProgress(0, 0)

        val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(12)
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 12, 0)).apply {
                isOpaque = false
                add(planProgressBadge)
                add(JBPanel<JBPanel<*>>(VerticalLayout(2)).apply {
                    isOpaque = false
                    add(planProgressLabel)
                    add(planExplanationLabel)
                })
            }, BorderLayout.CENTER)
        }

        val followUpPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            background = JBColor(0xFFFFFF, 0x2E3036)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor(0xE0E6F3, 0x3D424F), 1),
                JBUI.Borders.empty(12)
            )
            add(JBLabel("Ask for follow-up changes").apply {
                foreground = JBColor(0x6B778C, 0xAAB4C0)
            }, BorderLayout.CENTER)
            cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
            toolTipText = "Click to focus the composer input"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    inputArea.requestFocusInWindow()
                }
            })
        }

        val actionRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 12, 12)).apply {
            isOpaque = false
            add(planAttachmentButton)
            add(planAutoContextButton)
        }

        val followUpBlock = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(16)
            add(followUpPanel, BorderLayout.CENTER)
            add(actionRow, BorderLayout.SOUTH)
        }

        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(12, 12, 0, 12)
            val card = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = true
                background = JBColor(0xF7F9FD, 0x2C2F33)
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(JBColor(0xE0E5F1, 0x3C404D), 1),
                    JBUI.Borders.empty(16)
                )
                add(header, BorderLayout.NORTH)
                add(planStepsContainer, BorderLayout.CENTER)
                add(followUpBlock, BorderLayout.SOUTH)
            }
            add(card, BorderLayout.CENTER)
            isVisible = false
        }

        return panel
    }

    private fun createTimelinePanel(): JComponent {
        timelineContainer.removeAll()
        timelineContainer.add(timelineEmptyLabel)

        timelineScrollPane = JBScrollPane(timelineContainer).apply {
            border = JBUI.Borders.empty()
            viewport.background = UIUtil.getPanelBackground()
        }
        installTimelineScrollListener(timelineScrollPane)

        pinnedIssuesList = JBPanel<JBPanel<*>>(VerticalLayout(6)).apply {
            isOpaque = false
        }
        pinnedIssuesWrapper = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            background = JBColor(0xFFF3E0, 0x3B2F23)
            isOpaque = true
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor(0xF8C48E, 0x5D4330), 1),
                JBUI.Borders.empty(12)
            )
            val headerRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                isOpaque = false
                add(JBLabel("Latest issues").apply {
                    font = font.deriveFont(font.style or Font.BOLD)
                    foreground = JBColor(0x8D4B00, 0xFFCC80)
                }, BorderLayout.WEST)
                val dismissAllLink = LinkLabel<String>("Dismiss all", null).apply {
                    foreground = JBColor(0x5D4037, 0xFFCC80)
                    setListener({ _, _ -> dismissAllPinnedIssues() }, null)
                }
                add(dismissAllLink, BorderLayout.EAST)
            }
            add(headerRow, BorderLayout.NORTH)
            add(pinnedIssuesList, BorderLayout.CENTER)
            isVisible = false
        }

        val header = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 8, 0)
            add(JBLabel("Tooling Timeline").apply {
                font = font.deriveFont(font.style or Font.BOLD, font.size + 1f)
            }, BorderLayout.WEST)
            add(JBLabel("Double-click to expand details").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size - 1f)
            }, BorderLayout.EAST)
        }

        val headerStack = JBPanel<JBPanel<*>>(VerticalLayout(6)).apply {
            isOpaque = false
            add(header)
            add(pinnedIssuesWrapper)
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12, 8, 12)
            add(headerStack, BorderLayout.NORTH)
            add(timelineScrollPane, BorderLayout.CENTER)
        }
    }

    private fun refreshPinnedIssues() {
        if (!this::pinnedIssuesWrapper.isInitialized || !this::pinnedIssuesList.isInitialized) return
        val pinned = timelineItems.filter { it.pinned && !dismissedPinnedIssueIds.contains(it.id) }
        SwingUtilities.invokeLater {
            pinnedIssuesList.removeAll()
            if (pinned.isEmpty()) {
                pinnedIssuesWrapper.isVisible = false
            } else {
                pinned.forEach { pinnedIssuesList.add(buildPinnedIssueRow(it)) }
                pinnedIssuesWrapper.isVisible = true
            }
            pinnedIssuesList.revalidate()
            pinnedIssuesList.repaint()
        }
    }

    private fun buildPinnedIssueRow(item: TimelineItem): JComponent {
        val title = SimpleColoredComponent().apply {
            append(item.title, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.WHITE))
            if (!item.summary.isNullOrBlank()) {
                append(" · ${item.summary}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        val badge = createStatusBadge(item.status)
        val actionRow = JBPanel<JBPanel<*>>(HorizontalLayout(8)).apply {
            isOpaque = false
            add(LinkLabel<String>("Show", null).apply {
                setListener({ _, _ -> focusTimelineItem(item.id) }, null)
            })
            add(LinkLabel<String>("Dismiss", null).apply {
                setListener({ _, _ -> dismissPinnedIssue(item.id) }, null)
            })
        }
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            background = JBColor(0xCC4C02, 0x5D4037)
            border = JBUI.Borders.empty(6, 8)
            add(JBPanel<JBPanel<*>>(HorizontalLayout(8)).apply {
                isOpaque = false
                add(badge)
                add(title)
            }, BorderLayout.CENTER)
            add(actionRow, BorderLayout.EAST)
        }
    }

    private fun createStatusBadge(status: TimelineStatus): JComponent {
        return StatusBubble(status)
    }

    private fun configureStatusBadge(label: JBLabel, status: TimelineStatus) {
        if (label.componentCount > 0) {
            label.removeAll()
        }
        label.layout = BorderLayout()
        label.isOpaque = false
        label.border = null
        label.add(StatusBubble(status), BorderLayout.CENTER)
        label.toolTipText = status.label
    }

    private fun focusTimelineItem(itemId: Long) {
        SwingUtilities.invokeLater {
            val index = timelineItems.indexOfFirst { it.id == itemId }
            if (index < 0) return@invokeLater
            if (index >= timelineContainer.componentCount) return@invokeLater
            val component = timelineContainer.getComponent(index)
            val rect = Rectangle(component.bounds)
            timelineContainer.scrollRectToVisible(rect)
        }
    }

    private fun dismissPinnedIssue(itemId: Long) {
        dismissedPinnedIssueIds.add(itemId)
        refreshPinnedIssues()
    }

    private fun dismissAllPinnedIssues() {
        timelineItems.filter { it.pinned }.forEach { dismissedPinnedIssueIds.add(it.id) }
        refreshPinnedIssues()
    }

    private fun invokeTimelineAction(action: TimelineAction) {
        try {
            action.handler.invoke()
        } catch (t: Throwable) {
            appendTranscript("Timeline action failed: ${t.message}\n", TranscriptStyle.ERROR)
        }
    }

    private fun createComposerPanel(): JComponent {
        val textFieldBackground = JBColor.namedColor("TextArea.background", UIUtil.getTextFieldBackground())
        val topPadding = JBUI.scale(12)
        val sidePadding = JBUI.scale(16)
        val bottomPadding = JBUI.scale(56)

        inputArea.apply {
            lineWrap = true
            wrapStyleWord = true
            rows = 4
            margin = JBUI.emptyInsets()
            border = JBUI.Borders.empty(topPadding, sidePadding, bottomPadding, sidePadding)
            background = textFieldBackground
            isOpaque = false
        }

        val inputScroll = JBScrollPane(inputArea).apply {
            preferredSize = Dimension(600, JBUI.scale(120))
            border = JBUI.Borders.empty()
            viewportBorder = null
            isOpaque = false
            viewport.isOpaque = false
        }

        val composerInputContainer = object : JBPanel<JBPanel<*>>(null) {
            private val arc = JBUI.scale(28)

            init {
                isOpaque = false
                minimumSize = Dimension(JBUI.scale(200), JBUI.scale(120))
            }

            override fun getPreferredSize(): Dimension {
                val inner = inputScroll.preferredSize
                val padding = JBUI.scale(24)
                val width = max(inner.width, JBUI.scale(200)) + padding
                val height = max(inner.height, JBUI.scale(100)) + padding
                return Dimension(width, height)
            }

            override fun getMinimumSize(): Dimension {
                val base = super.getMinimumSize()
                return Dimension(max(base.width, JBUI.scale(200)), max(base.height, JBUI.scale(100)))
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val bg = textFieldBackground
                val borderColor = JBColor(0xE0E6F3, 0x3D424F)
                g2.color = bg
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.color = borderColor
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                g2.dispose()
            }

            override fun doLayout() {
                val padding = JBUI.scale(12)
                val buttonInset = JBUI.scale(6)
                val buttonSize = composerActionButton.preferredSize
                inputScroll.setBounds(padding, padding, width - padding * 2, height - padding * 2)
                val attachSize = attachButton.preferredSize
                attachButton.setBounds(
                    padding + buttonInset,
                    height - padding - attachSize.height - buttonInset,
                    attachSize.width,
                    attachSize.height
                )
                composerActionButton.setBounds(
                    width - padding - buttonSize.width - buttonInset,
                    height - padding - buttonSize.height - buttonInset,
                    buttonSize.width,
                    buttonSize.height
                )
            }
        }

        composerInputContainer.add(inputScroll)
        composerInputContainer.add(composerActionButton)
        composerInputContainer.add(attachButton)
        composerInputContainer.setComponentZOrder(composerActionButton, 0)
        composerInputContainer.setComponentZOrder(attachButton, 0)

        val attachmentsWrapper = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 0, 8, 0)
            add(JBLabel("Attachments"), BorderLayout.WEST)
            add(attachmentChipsPanel, BorderLayout.CENTER)
        }

        val controls = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            
            val dropdownRow = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(modelEffortCombo)
                add(Box.createHorizontalStrut(8))
                add(sandboxModeCombo)
                add(Box.createHorizontalGlue())
            }
            
            val usageRow = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(usageLabel)
                add(Box.createHorizontalGlue())
                border = JBUI.Borders.emptyTop(4)
            }
            
            add(dropdownRow)
            add(usageRow)
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12, 12, 12)
            add(attachmentsWrapper, BorderLayout.NORTH)
            add(composerInputContainer, BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }
    }

    private fun setComposerActionState(state: ComposerActionState) {
        if (SwingUtilities.isEventDispatchThread()) {
            applyComposerActionState(state)
        } else {
            SwingUtilities.invokeLater { applyComposerActionState(state) }
        }
    }

    private fun applyComposerActionState(state: ComposerActionState) {
        composerActionState = state
        val tooltip = if (state == ComposerActionState.SEND) "Send message" else "Abort current task"
        composerActionButton.toolTipText = tooltip
        composerActionButton.putClientProperty(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, tooltip)
        composerActionButton.repaint()
    }
    
    private fun setupListeners() {
        composerActionButton.addActionListener {
            when (composerActionState) {
                ComposerActionState.SEND -> handleSendAction()
                ComposerActionState.ABORT -> handleAbortAction()
            }
        }

        attachButton.addActionListener {
            val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
                .withTitle("Select Files to Attach")
                .withDescription("Choose files to attach to your message")

            val files = FileChooser.chooseFiles(descriptor, project, null)
            if (files.isNotEmpty()) {
                files.forEach { vf ->
                    attachedFiles.add(vf.path)
                    appendTranscript("📎 Attached: ${vf.name}\n", TranscriptStyle.INFO)
                }
                refreshAttachmentChips()
            }
        }

        // Ctrl/Cmd+Enter to send
        inputArea.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER &&
                    (e.isControlDown || e.isMetaDown)) {
                    e.consume()
                    if (composerActionState == ComposerActionState.SEND) {
                        handleSendAction()
                    } else {
                        handleAbortAction()
                    }
                }
            }
        })
    }

    private fun handleSendAction() {
        if (composerActionState != ComposerActionState.SEND) return
        val text = inputArea.text.trim()
        if (text.isBlank()) return
        val attachmentsSnapshot = attachedFiles.toList()
        inputArea.text = ""
        attachedFiles.clear()
        refreshAttachmentChips()
        scope.launch {
            queueOutgoingMessage(OutgoingMessage(text, attachmentsSnapshot))
            ensureServiceRunning()
            processOutgoingQueue()
        }
    }

    private fun handleAbortAction() {
        if (composerActionState != ComposerActionState.ABORT) return
        val currentThreadId = threadId
        if (currentThreadId == null) {
            setComposerActionState(ComposerActionState.SEND)
            return
        }
        scope.launch {
            try {
                appendTranscript("\n⚠ Aborting current task...\n", TranscriptStyle.WARNING)
                val client = service.getClient()
                if (client != null) {
                    service.sessionRegistry.interruptActiveTurn(client, currentThreadId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                appendTranscript("✗ Abort failed: ${e.message}\n", TranscriptStyle.ERROR)
            } finally {
                setComposerActionState(ComposerActionState.SEND)
            }
        }
    }

    private fun handleNewSessionRequest() {
        if (conversationInitJob?.isActive == true) {
            appendTranscript("↻ Session setup already in progress. Please wait...\n", TranscriptStyle.INFO)
            return
        }
        scope.launch {
            ensureServiceRunning()
            val state = service.state.value
            if (state !is ServiceState.Running) {
                appendTranscript("Codex is still starting; a new session will begin once connected.\n", TranscriptStyle.INFO)
                return@launch
            }
            appendTranscript("\n↻ Starting a fresh Codex session...\n", TranscriptStyle.SYSTEM)
            scheduleThreadInitialization()
        }
    }

    private fun scheduleThreadInitialization() {
        conversationInitJob?.cancel()
        conversationInitJob = scope.launch {
            initializeThread()
        }
    }

    private fun setNewSessionButtonEnabled(enabled: Boolean) {
        if (SwingUtilities.isEventDispatchThread()) {
            newSessionButton.isEnabled = enabled
        } else {
            SwingUtilities.invokeLater { newSessionButton.isEnabled = enabled }
        }
    }
    
    private fun observeServiceState() {
        scope.launch {
            var lastClient: JsonRpcClient? = null
            service.state.collect { state ->
                when (state) {
                    is ServiceState.Running -> {
                        setNewSessionButtonEnabled(true)
                        updateConnectionStatus(
                            "Connected",
                            AllIcons.General.InspectionsOK,
                            JBColor(0x1B5E20, 0x81C784)
                        )

                        if (state.client !== lastClient) {
                            lastClient = state.client
                            appendTranscript("✓ Codex connection ready\n", TranscriptStyle.SYSTEM)
                            startEventProcessing(state.client)
                            scheduleThreadInitialization()
                        }
                    }

                    is ServiceState.Error -> {
                        setNewSessionButtonEnabled(false)
                        cancelEventProcessing()
                        conversationInitJob?.cancel()
                        sessionReadyFallbackJob?.cancel()
                        sessionReadyFallbackJob = null
                        appendTranscript("✗ Error: ${state.message}\n", TranscriptStyle.ERROR)
                        runOnEdt {
                            setComposerActionState(ComposerActionState.SEND)
                        }
                        updateConnectionStatus(
                            "Error",
                            AllIcons.General.Error,
                            JBColor(0xB71C1C, 0xEF9A9A)
                        )
                        recordTimelineEvent(TimelineCategory.ERROR, "Codex session error", state.message ?: "unknown")
                        threadReady = false
                        threadId = null
                        currentTaskTimelineKey = null
                    }

                    is ServiceState.Stopped -> {
                        setNewSessionButtonEnabled(false)
                        cancelEventProcessing()
                        conversationInitJob?.cancel()
                        sessionReadyFallbackJob?.cancel()
                        sessionReadyFallbackJob = null
                        runOnEdt {
                            setComposerActionState(ComposerActionState.SEND)
                        }
                        updateConnectionStatus(
                            "Stopped",
                            AllIcons.Actions.Suspend,
                            JBColor(0x37474F, 0x78909C)
                        )
                        threadReady = false
                        threadId = null
                        currentTaskTimelineKey = null
                    }

                    ServiceState.Starting -> {
                        setNewSessionButtonEnabled(false)
                    }
                }
            }
        }

        // Monitor session events to enable composer after configuration
        scope.launch {
            service.sessionRegistry.sessionEvents.collect { event ->
                when (event) {
                    is SessionEvent.Configured -> {
                        if (event.threadId == threadId) {
                            sessionReadyFallbackJob?.cancel()
                            sessionReadyFallbackJob = null
                            threadReady = true
                            SwingUtilities.invokeLater {
                                appendTranscript("✓ Thread ready - you can now send messages\n", TranscriptStyle.SYSTEM)
                            }
                            scope.launch { processOutgoingQueue() }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private suspend fun initializeThread() {
        val state = service.state.value
        if (state !is ServiceState.Running) return

        val client = state.client
        val settings = CodexSettings.getInstance(project).state

        client.awaitInitialized()

        try {
            sessionReadyFallbackJob?.cancel()
            sessionReadyFallbackJob = null
            threadReady = false

            val hadPreviousThread = threadId != null
            threadId?.let {
                service.sessionRegistry.removeSession(it)
                service.approvalCache.clearThread(it)
            }
            currentTaskTimelineKey = null

            // Clear timeline for fresh session
            clearTimeline()

            if (hadPreviousThread) {
                appendTranscript("\n↻ Connection restored - creating a new thread...\n", TranscriptStyle.SYSTEM)
            }

            // Load account info and rate limits
            try {
                val account = client.accountRead()
                val username = account.jsonObject["username"]?.jsonPrimitive?.contentOrNull
                if (username != null) {
                    appendTranscript("Logged in as: $username\n", TranscriptStyle.INFO)
                }

                client.accountRateLimitsRead()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Account info not critical, continue anyway
                appendTranscript("(Account info unavailable)\n", TranscriptStyle.WARNING)
            }

            val models = client.listModels()
            appendTranscript("Available models loaded\n", TranscriptStyle.INFO)

            val currentPreset = modelEffortCombo.selectedItem as? ModelEffortPreset ?: ModelEffortPreset.getDefault()
            val model = currentPreset.model
            val cwd = project.basePath ?: System.getProperty("user.dir")
            val approvalPolicy = settings.approvalPolicy
            val sandboxPreset = sandboxModeCombo.selectedItem as? SandboxModePreset ?: SandboxModePreset.getDefault()

            val thread = client.startThread(
                model = model,
                cwd = cwd,
                approvalPolicy = approvalPolicy,
                sandbox = CodexSettings.toServerSandboxMode(sandboxPreset.value)
            )
            threadId = thread.id
            service.approvalCache.clearThread(threadId!!)

            appendTranscript("✓ Thread created: $threadId\n", TranscriptStyle.SYSTEM)
            appendTranscript("  Model: $model\n", TranscriptStyle.SYSTEM)
            appendTranscript("  Approval: $approvalPolicy\n", TranscriptStyle.SYSTEM)
            appendTranscript("  Sandbox: ${sandboxPreset.value}\n", TranscriptStyle.SYSTEM)
            updateThreadMetadata(model)

            // Register with SessionRegistry
            service.sessionRegistry.registerSession(
                threadId = threadId!!,
                model = model,
                cwd = cwd
            )

            appendTranscript("⏳ Waiting for thread/started...\n", TranscriptStyle.SYSTEM)
            scheduleThreadReadyFallback(threadId!!)

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            appendTranscript("✗ Initialization failed: ${e.message}\n", TranscriptStyle.ERROR)
            recordTimelineEvent(TimelineCategory.ERROR, "Initialization failed", e.message ?: "unknown")
        }
    }
    
    private fun startEventProcessing(client: JsonRpcClient) {
        cancelEventProcessing()

        notificationJob = scope.launch {
            try {
                for (notification in client.notificationChannel) {
                    val event = parseCodexEvent(notification)
                    handleEvent(event)
                }
            } catch (e: CancellationException) {
                // Expected on shutdown; propagate cancellation to respect structured concurrency
                throw e
            }
        }

        approvalJob = scope.launch {
            try {
                for (approval in client.approvalRequestChannel) {
                    handleApproval(client, approval)
                }
            } catch (e: CancellationException) {
                // Expected on shutdown; propagate cancellation to respect structured concurrency
                throw e
            }
        }
    }

    private fun cancelEventProcessing() {
        notificationJob?.cancel()
        approvalJob?.cancel()
        notificationJob = null
        approvalJob = null
    }

    private suspend fun queueOutgoingMessage(message: OutgoingMessage) {
        outgoingMutex.withLock {
            pendingMessages.addLast(message)
        }
    }

    private fun isThreadReadyForSend(): Boolean {
        val state = service.state.value
        return state is ServiceState.Running && threadReady && threadId != null
    }

    private suspend fun processOutgoingQueue() {
        if (!isThreadReadyForSend()) return
        queueProcessingMutex.withLock {
            while (isThreadReadyForSend()) {
                val next = outgoingMutex.withLock {
                    if (pendingMessages.isEmpty()) null else pendingMessages.removeFirst()
                } ?: break
                sendMessage(next)
            }
        }
    }

    private suspend fun ensureServiceRunning() {
        val current = service.state.value
        if (current is ServiceState.Running || current is ServiceState.Starting) return

        startMutex.withLock {
            val latest = service.state.value
            if (latest is ServiceState.Running || latest is ServiceState.Starting) return@withLock
            try {
                val settings = CodexSettings.getInstance(project).state
                val codexPath = settings.codexBinaryPath.ifEmpty { null }
                val extraArgs = parseCliArgs(settings.extraCliFlags)
                appendTranscript("⏳ Launching Codex app-server...\n", TranscriptStyle.SYSTEM)
                service.start(codexPath, extraArgs)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                appendTranscript("✗ Failed to start Codex: ${e.message}\n", TranscriptStyle.ERROR)
            }
        }
    }
    
    private suspend fun sendMessage(message: OutgoingMessage) {
        val state = service.state.value
        if (state !is ServiceState.Running || threadId == null) return

        val currentPreset = modelEffortCombo.selectedItem as? ModelEffortPreset ?: ModelEffortPreset.getDefault()
        val settings = CodexSettings.getInstance(project).state
        val summary = settings.defaultSummaryMode

        try {
            appendTranscript("\n👤 User: ${message.text}\n", TranscriptStyle.USER)
            val trimmed = message.text.trim()
            if (trimmed.isNotEmpty()) {
                val title = clipForTimeline(trimmed).ifBlank { "Message" }
                recordTimelineEvent(TimelineCategory.USER, title, trimmed)
            }
            if (message.attachments.isNotEmpty()) {
                appendTranscript("  Attachments: ${message.attachments.size}\n", TranscriptStyle.USER)
                recordTimelineEvent(
                    TimelineCategory.USER,
                    "Attachments",
                    message.attachments.joinToString { friendlyFileName(it) }
                )
            }

            val sandboxPreset = sandboxModeCombo.selectedItem as? SandboxModePreset ?: SandboxModePreset.getDefault()
            val turn = state.client.startTurn(
                threadId = threadId!!,
                text = message.text,
                attachments = message.attachments,
                effort = currentPreset.effort,
                summary = summary,
                approvalPolicy = settings.approvalPolicy,
                sandbox = CodexSettings.toServerSandboxMode(sandboxPreset.value),
                cwd = project.basePath ?: System.getProperty("user.dir"),
                model = currentPreset.model
            )
            activeTurnId = turn.id
            service.sessionRegistry.markTurnActive(threadId!!, turn.id)

            setComposerActionState(ComposerActionState.ABORT)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            appendTranscript("✗ Send failed: ${e.message}\n", TranscriptStyle.ERROR)
        }
    }
    
    private fun handleEvent(event: CodexEvent) {
        when (event) {
            is CodexEvent.ThreadStarted -> {
                val modelLabel = event.model ?: currentModelLabel
                appendTranscript("✓ Thread ready (model: $modelLabel)\n", TranscriptStyle.SYSTEM)
                updateThreadMetadata(event.model)
                currentTaskTimelineKey = null

                service.sessionRegistry.handleThreadStarted(event.threadId)
                threadReady = true
                scope.launch { processOutgoingQueue() }
            }

            is CodexEvent.TaskStarted -> {
                appendTranscript("\n🤖 Agent is thinking...\n", TranscriptStyle.SYSTEM)
                val workingKey = resolveTaskTimelineKey(event.turnId)
                showWorkingTimelineEntry(workingKey)
                currentTaskTimelineKey = workingKey
                event.turnId?.let {
                    activeTurnId = it
                    service.sessionRegistry.markTurnActive(event.threadId, it)
                }
            }

            is CodexEvent.TaskComplete -> {
                val status = event.status ?: "completed"
                val interrupted = status.equals("interrupted", true)
                val failed = status.equals("failed", true)
                val message = if (interrupted) "\n⚠ Task interrupted\n" else "\n✓ Task complete\n"
                appendTranscript(message, if (interrupted) TranscriptStyle.WARNING else TranscriptStyle.SYSTEM)
                val summary = event.lastAgentMessage?.let { clipForTimeline(it) } ?: ""
                val title = when {
                    interrupted -> "Task interrupted"
                    failed -> "Task failed"
                    else -> "Task complete"
                }
                val timelineStatus = when {
                    interrupted -> TimelineStatus.WARNING
                    failed -> TimelineStatus.FAILED
                    else -> TimelineStatus.SUCCEEDED
                }
                val key = event.turnId?.let { timelineKey("task", it) } ?: currentTaskTimelineKey
                if (key != null) {
                    val updated = completeTaskTimelineEntry(key, title, timelineStatus, summary)
                    if (!updated) {
                        showWorkingTimelineEntry(key)
                        completeTaskTimelineEntry(key, title, timelineStatus, summary)
                    }
                }

                val hasThread = event.threadId
                clearReasoningTimelineBuffer(hasThread, event.turnId)
                clearAgentResponseTimeline(hasThread, event.turnId)
                activeTurnId = null
                currentTaskTimelineKey = null
                service.sessionRegistry.completeTurn(event.threadId)

                setComposerActionState(ComposerActionState.SEND)
            }

            is CodexEvent.TurnAborted -> {
                appendTranscript("\n⚠ Task aborted: ${event.reason}\n", TranscriptStyle.WARNING)
                val key = event.turnId?.let { timelineKey("task", it) } ?: currentTaskTimelineKey
                if (key != null) {
                    val updated = completeTaskTimelineEntry(key, "Task aborted", TimelineStatus.WARNING, event.reason)
                    if (!updated) {
                        showWorkingTimelineEntry(key)
                        completeTaskTimelineEntry(key, "Task aborted", TimelineStatus.WARNING, event.reason)
                    }
                }

                val hasThread = event.threadId
                clearReasoningTimelineBuffer(hasThread, event.turnId)
                clearAgentResponseTimeline(hasThread, event.turnId)
                activeTurnId = null
                currentTaskTimelineKey = null
                service.sessionRegistry.markTurnInterrupted(event.threadId)

                setComposerActionState(ComposerActionState.SEND)
            }

            is CodexEvent.AgentMessage -> {
                appendTranscript("🤖 ${event.message}\n", TranscriptStyle.AGENT)
                val combined = finalizeAgentResponseEntry(event.threadId, event.turnId, event.message)
                if (!combined.isNullOrBlank()) {
                    recordAgentTimelineEntry(combined)
                }
            }

            is CodexEvent.AgentMessageDelta -> {
                appendTranscript(event.delta, TranscriptStyle.AGENT)
                appendAgentResponseDelta(event.threadId, event.turnId, event.delta)
            }

            is CodexEvent.AgentReasoning -> {
                val text = event.content
                if (text.isNotBlank()) {
                    appendReasoning("$text\n")
                }
                val combined = finalizeReasoningEntry(event.threadId, event.turnId, text)
                if (!combined.isNullOrBlank()) {
                    recordReasoningTimelineEntry(combined)
                }
            }

            is CodexEvent.AgentReasoningDelta -> {
                if (event.delta.isNotBlank()) {
                    appendReasoning(event.delta)
                    appendReasoningDelta(event.threadId, event.turnId, event.delta)
                }
            }

            is CodexEvent.PlanUpdate -> {
                updatePlanList(event.plan, event.explanation)
                appendTranscript("📋 Plan update (${event.plan.size} steps)\n", TranscriptStyle.SYSTEM)
                recordTimelineEvent(TimelineCategory.PLAN, "Plan updated", clipForTimeline(event.plan.joinToString { "${it.status}:${it.step}" }))
            }

            is CodexEvent.McpToolCallBegin -> {
                appendTranscript("[TOOL] ${event.server}.${event.tool} started\n", TranscriptStyle.SYSTEM)
                handleToolBeginEvent(event)
            }

            is CodexEvent.McpToolCallEnd -> {
                val status = if (event.error != null) "failed: ${event.error}" else "completed"
                val style = if (event.error != null) TranscriptStyle.WARNING else TranscriptStyle.SYSTEM
                appendTranscript("[TOOL] ${event.callId} $status\n", style)
                handleToolEndEvent(event)
            }

            is CodexEvent.ExecCommandBegin -> {
                val cmd = event.command.joinToString(" ")
                appendTranscript("[EXEC] $cmd\n", TranscriptStyle.SYSTEM)
                handleExecBegin(event)
            }

            is CodexEvent.ExecCommandOutputDelta -> {
                appendTranscript("  ${event.chunk}\n", TranscriptStyle.SYSTEM)
                handleExecOutput(event)
            }

            is CodexEvent.ExecCommandEnd -> {
                appendTranscript("[EXEC] Exit code: ${event.exitCode}\n", TranscriptStyle.SYSTEM)
                handleExecEnd(event)
            }

            is CodexEvent.ApplyPatchApprovalRequest -> {
                handlePatchApproval(event)
            }

            is CodexEvent.PatchApplyBegin -> {
                appendTranscript("[PATCH] Applying changes...\n", TranscriptStyle.SYSTEM)
                handlePatchApplyBegin(event)
            }

            is CodexEvent.PatchApplyEnd -> {
                val status = if (event.success) "succeeded" else "failed"
                val style = if (event.success) TranscriptStyle.SYSTEM else TranscriptStyle.WARNING
                appendTranscript("[PATCH] $status\n", style)
                handlePatchApplyEnd(event)
            }

            is CodexEvent.WebSearchBegin -> {
                appendTranscript("[SEARCH] Query: ${event.query}\n", TranscriptStyle.SYSTEM)
                handleSearchBegin(event)
            }

            is CodexEvent.WebSearchEnd -> {
                appendTranscript("[SEARCH] Completed\n", TranscriptStyle.SYSTEM)
                handleSearchEnd(event)
            }

            is CodexEvent.TokenCount -> handleTokenCount(event)

            is CodexEvent.RateLimitsUpdated -> handleRateLimitsUpdated(event)

            is CodexEvent.Error -> {
                appendTranscript("✗ Error: ${event.message}\n", TranscriptStyle.ERROR)
                recordTimelineEvent(TimelineCategory.ERROR, "Error", event.message)
            }

            is CodexEvent.Warning -> {
                appendTranscript("⚠ Warning: ${event.message}\n", TranscriptStyle.WARNING)
                recordTimelineEvent(TimelineCategory.WARNING, "Warning", event.message)
            }

            is CodexEvent.Unknown -> Unit
        }
    }

    private suspend fun handleApproval(client: JsonRpcClient, approval: ApprovalRequest) {
        val result = onEdt {
            when (approval.method) {
                "execCommandApproval" -> showExecApprovalDialog(approval.params)
                "applyPatchApproval" -> showPatchApprovalDialog(approval.params)
                else -> "denied"
            }
        }

        client.respondToApproval(approval.id, result)
    }
    
    private fun showExecApprovalDialog(params: JsonObject): String {
        val commandArray = params["command"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val cwdRaw = params["cwd"]?.jsonPrimitive?.content ?: ""

        // Check cache first
        val convId = threadId
        val cached = convId?.let { service.approvalCache.checkExecApproval(it, commandArray, cwdRaw) }
        if (cached != null) {
            appendTranscript("[APPROVAL] Command auto-approved from cache\n")
            return cached
        }

        val maskedCommand = SecretMasker.maskCommand(commandArray).joinToString(" ")
        val cwd = SecretMasker.maskPath(cwdRaw)
        val reason = SecretMasker.mask(params["reason"]?.jsonPrimitive?.contentOrNull ?: "No reason provided")

        // Risk assessment
        val riskHint = assessCommandRisk(commandArray)

        val message = """
            Command: $maskedCommand
            Directory: $cwd
            Reason: $reason
            ${if (riskHint != null) "\n⚠️ Risk: $riskHint" else ""}

            Approve this command?
        """.trimIndent()

        val choice = Messages.showDialog(
            project,
            message,
            "Exec Command Approval",
            arrayOf("Approve", "Approve for Session", "Deny", "Abort"),
            if (riskHint != null) 2 else 0, // Default to Deny if risky
            if (riskHint != null) Messages.getWarningIcon() else Messages.getQuestionIcon()
        )

        val decision = when (choice) {
            0 -> "approved"
            1 -> "approved_for_session"
            2 -> "denied"
            3 -> "abort"
            else -> "denied"
        }

        // Cache the decision if "approved_for_session"
        convId?.let {
            service.approvalCache.cacheExecApproval(it, commandArray, cwdRaw, decision)
        }

        return decision
    }

    private fun assessCommandRisk(command: List<String>): String? {
        val cmd = command.firstOrNull()?.lowercase() ?: return null

        return when {
            cmd in listOf("rm", "rmdir", "del") && command.any { it.contains("-r") || it.contains("-f") } ->
                "Destructive file deletion"
            cmd in listOf("chmod", "chown") && command.any { it.contains("-R") } ->
                "Recursive permission change"
            cmd == "curl" && command.any { it.contains("sudo") || it.contains("bash") } ->
                "Remote script execution"
            cmd in listOf("dd", "mkfs", "fdisk") ->
                "Disk operation - potential data loss"
            cmd in listOf("kill", "killall") && command.any { it == "-9" } ->
                "Force kill processes"
            cmd == "docker" && command.any { it in listOf("rmi", "system", "prune") } ->
                "Docker cleanup operation"
            command.any { it.contains("sudo") } ->
                "Requires elevated privileges"
            else -> null
        }
    }
    
    private fun showPatchApprovalDialog(params: JsonObject): String {
        val fileChanges = params["fileChanges"]?.jsonObject
        val filesSet = fileChanges?.keys ?: emptySet()

        // Check cache first
        val convId = threadId
        val cached = convId?.let { service.approvalCache.checkPatchApproval(it, filesSet) }
        if (cached != null) {
            appendTranscript("[APPROVAL] Patch auto-approved from cache\n")
            return cached
        }

        val reason = SecretMasker.mask(params["reason"]?.jsonPrimitive?.contentOrNull ?: "No reason provided")
        val files = filesSet.map { SecretMasker.maskPath(it) }.joinToString("\n  ")

        // Check for sensitive file modifications
        val hasSensitiveFiles = filesSet.any { path ->
            path.contains(".env") || path.contains("credentials") ||
                    path.contains("secrets") || path.contains("id_rsa") ||
                    path.contains(".ssh")
        }

        val message = """
            Files to modify:
              $files

            Reason: $reason
            ${if (hasSensitiveFiles) "\n⚠️ Warning: Modifying sensitive configuration files" else ""}

            Approve these changes?
        """.trimIndent()

        val choice = Messages.showDialog(
            project,
            message,
            "Patch Approval",
            arrayOf("Approve", "Approve for Session", "Deny", "Abort"),
            if (hasSensitiveFiles) 2 else 0, // Default to Deny if sensitive
            if (hasSensitiveFiles) Messages.getWarningIcon() else Messages.getQuestionIcon()
        )

        val decision = when (choice) {
            0 -> "approved"
            1 -> "approved_for_session"
            2 -> "denied"
            3 -> "abort"
            else -> "denied"
        }

        // Cache the decision if "approved_for_session"
        convId?.let {
            service.approvalCache.cachePatchApproval(it, filesSet, decision)
        }

        return decision
    }
    
    private fun scheduleThreadReadyFallback(expectedThreadId: String) {
        sessionReadyFallbackJob?.cancel()
        sessionReadyFallbackJob = scope.launch {
            delay(5000)
            val stillWaiting = !threadReady && threadId == expectedThreadId
            if (stillWaiting) {
                threadReady = true
                appendTranscript("⚠ Thread/started not received; enabling composer anyway.\n", TranscriptStyle.WARNING)
                scope.launch { processOutgoingQueue() }
            }
        }
    }

    private fun nextTimelineId(): Long = timelineIdGenerator.incrementAndGet()

    private fun timelineKey(prefix: String, callId: String): String = "$prefix:$callId"

    private fun resolveTaskTimelineKey(turnId: String?): String {
        if (!turnId.isNullOrBlank()) return timelineKey("task", turnId)
        return currentTaskTimelineKey ?: timelineKey("task", "thread-${threadId ?: "unknown"}-${System.nanoTime()}")
    }

    private fun showWorkingTimelineEntry(key: String) {
        val refreshed = updateTimelineItem(key) { existing ->
            val note = existing as? TimelineNote ?: return@updateTimelineItem null
            note.copy(title = WORKING_TITLE, status = TimelineStatus.RUNNING, summary = null)
        }
        if (!refreshed) {
            recordTimelineEvent(
                category = TimelineCategory.AGENT,
                title = WORKING_TITLE,
                detail = "",
                status = TimelineStatus.RUNNING,
                key = key
            )
        }
    }

    private fun completeTaskTimelineEntry(
        key: String?,
        title: String,
        status: TimelineStatus,
        summary: String
    ): Boolean {
        if (key == null) return false
        return updateTimelineItem(key) { existing ->
            val note = existing as? TimelineNote ?: return@updateTimelineItem null
            note.copy(
                title = title,
                status = status,
                summary = summary.ifBlank { null }
            )
        }
    }

    private fun addTimelineItem(item: TimelineItem) {
        timelineItems.add(item)
        timelineItemKeys[item.key] = item.id
        
        if (item.isExpandable) {
            val autoExpandCategories = setOf(
                TimelineCategory.THINKING,
                TimelineCategory.AGENT,
                TimelineCategory.USER
            )
            if (item.category in autoExpandCategories) {
                expandedTimelineItems.add(item.id)
            }
        }
        
        requestTimelineRefresh()
    }

    private fun updateTimelineItem(key: String, transform: (TimelineItem) -> TimelineItem?): Boolean {
        val index = findTimelineIndexForKey(key) ?: return false
        val current = timelineItems[index]
        val updated = transform(current) ?: return false
        timelineItems[index] = updated
        
        if (updated.isExpandable) {
            val autoExpandCategories = setOf(
                TimelineCategory.THINKING,
                TimelineCategory.AGENT,
                TimelineCategory.USER
            )
            if (updated.category in autoExpandCategories) {
                expandedTimelineItems.add(updated.id)
            }
        }
        
        requestTimelineRefresh()
        return true
    }

    private fun findTimelineIndexForKey(key: String): Int? {
        val itemId = timelineItemKeys[key] ?: return null
        val index = timelineItems.indexOfFirst { it.id == itemId }
        return if (index >= 0) index else null
    }

    private fun prettyPrintJson(element: JsonElement?): String? {
        if (element == null) return null
        return try {
            jsonPrinter.encodeToString(element)
        } catch (_: Exception) {
            element.toString()
        }
    }

    private fun formatExecLog(log: TimelineContent.ExecLog): String {
        val builder = StringBuilder()
        if (!log.workingDirectory.isNullOrBlank()) {
            builder.append("cwd: ${log.workingDirectory}\n")
        }
        builder.append("$ ${log.command}\n")
        if (log.stdout.isNotEmpty()) {
            log.stdout.forEach { builder.append(it).append('\n') }
        }
        if (log.stderr.isNotEmpty()) {
            builder.append("STDERR:\n")
            log.stderr.forEach { builder.append(it).append('\n') }
        }
        return builder.toString().trimEnd()
    }

    private fun copyToClipboard(text: String?) {
        if (text.isNullOrBlank()) return
        try {
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        } catch (t: Throwable) {
            appendTranscript("Copy failed: ${t.message}\n", TranscriptStyle.ERROR)
        }
    }

    private fun buildToolDetail(arguments: String?, result: String?, error: String?): String? {
        if (arguments.isNullOrBlank() && result.isNullOrBlank() && error.isNullOrBlank()) return null
        val builder = StringBuilder()
        if (!arguments.isNullOrBlank()) {
            builder.append("Arguments:\n").append(arguments.trim()).append("\n\n")
        }
        if (!result.isNullOrBlank()) {
            builder.append("Result:\n").append(result.trim()).append("\n\n")
        }
        if (!error.isNullOrBlank()) {
            builder.append("Error:\n").append(error.trim()).append('\n')
        }
        return builder.toString().trimEnd()
    }

    private fun buildPatchDetail(fileChanges: JsonObject, reason: String?): String? {
        if (fileChanges.isEmpty() && reason.isNullOrBlank()) return null
        val builder = StringBuilder()
        if (!reason.isNullOrBlank()) {
            builder.append("Reason: ").append(reason.trim()).append("\n\n")
        }
        fileChanges.entries.forEach { (path, value) ->
            builder.append(path).append('\n')
            val diff = when (value) {
                is JsonPrimitive -> value.contentOrNull ?: value.toString()
                is JsonObject -> value.stringOrNull("diff") ?: value.stringOrNull("patch") ?: value.toString()
                is JsonArray -> value.joinToString(separator = "\n") { it.toString() }
                else -> value.toString()
            }
            if (!diff.isNullOrBlank()) {
                builder.append(diff.trim()).append("\n")
            }
            builder.append('\n')
        }
        return builder.toString().trimEnd()
    }

    private fun buildExecSummary(log: TimelineContent.ExecLog, exitCode: Int? = null): String {
        val parts = mutableListOf<String>()
        parts += clipForTimeline(log.command, 80)
        val streamBits = mutableListOf<String>()
        if (log.stdout.isNotEmpty()) streamBits += "${log.stdout.size} out"
        if (log.stderr.isNotEmpty()) streamBits += "${log.stderr.size} err"
        if (exitCode != null) streamBits += "exit $exitCode"
        if (streamBits.isNotEmpty()) {
            parts += streamBits.joinToString(", ")
        }
        return parts.joinToString(" • ")
    }

    private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.intAny(vararg keys: String): Int? {
        for (key in keys) {
            val element = this[key]?.jsonPrimitive ?: continue
            element.intOrNull?.let { return it }
            element.contentOrNull?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.longAny(vararg keys: String): Long? {
        for (key in keys) {
            val element = this[key]?.jsonPrimitive ?: continue
            element.longOrNull?.let { return it }
            element.contentOrNull?.toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.doubleAny(vararg keys: String): Double? {
        for (key in keys) {
            val element = this[key]?.jsonPrimitive ?: continue
            element.doubleOrNull?.let { return it }
            element.contentOrNull?.toDoubleOrNull()?.let { return it }
        }
        return null
    }

    private fun recordTimelineEvent(
        category: TimelineCategory,
        title: String,
        detail: String = "",
        collapsible: Boolean? = null,
        status: TimelineStatus? = null,
        pinned: Boolean? = null,
        key: String? = null
    ) {
        val shouldCollapse = collapsible ?: shouldCollapseDetail(category, detail)
        val textFormat = when (category) {
            TimelineCategory.AGENT,
            TimelineCategory.USER,
            TimelineCategory.THINKING -> TimelineTextFormat.MARKDOWN
            else -> TimelineTextFormat.PLAIN
        }
        var summaryText = when {
            detail.isBlank() -> null
            shouldCollapse -> clipForTimeline(detail)
            else -> detail.trim()
        }
        if (category == TimelineCategory.USER && summaryText != null && summaryText == title) {
            summaryText = null
        }
        val expandable = if (shouldCollapse && detail.isNotBlank()) TimelineContent.Text(detail, textFormat) else null
        val effectiveStatus = status ?: when (category) {
            TimelineCategory.ERROR -> TimelineStatus.FAILED
            TimelineCategory.WARNING -> TimelineStatus.WARNING
            TimelineCategory.TOOL -> TimelineStatus.INFO
            TimelineCategory.EXEC -> TimelineStatus.INFO
            else -> TimelineStatus.INFO
        }
        val id = nextTimelineId()
        val groupedKey = key ?: id.toString()
        val pinnedState = pinned ?: (category == TimelineCategory.ERROR || category == TimelineCategory.WARNING)
        val item = TimelineNote(
            id = id,
            key = groupedKey,
            category = category,
            status = effectiveStatus,
            title = title,
            summary = summaryText,
            expandableContent = expandable,
            pinned = pinnedState
        )
        addTimelineItem(item)
    }

    private fun clearTimeline() {
        timelineItems.clear()
        expandedTimelineItems.clear()
        timelineItemKeys.clear()
        dismissedPinnedIssueIds.clear()
        reasoningTimelineBuffers.clear()
        agentResponseTimelineBuffers.clear()
        refreshTimeline()
    }

    private fun refreshTimeline() {
        SwingUtilities.invokeLater {
            val snapshot = ArrayList(timelineItems)

            if (snapshot.isEmpty()) {
                timelineContainer.removeAll()
                timelineCardCache.clear()
                timelineContainer.add(timelineEmptyLabel)
            } else {
                val currentIds = snapshot.map { it.id }.toSet()
                timelineCardCache.keys.retainAll(currentIds)
                
                val needsRebuild = timelineContainer.componentCount != snapshot.size ||
                    snapshot.mapIndexed { index, item ->
                        val card = timelineContainer.getComponent(index) as? TimelineCard
                        card?.boundItem?.id != item.id
                    }.any { it }
                
                if (needsRebuild) {
                    timelineContainer.removeAll()
                    snapshot.forEachIndexed { index, item ->
                        val card = timelineCardCache.getOrPut(item.id) { TimelineCard() }
                        card.bind(item, index, snapshot.size)
                        timelineContainer.add(card)
                    }
                    timelineContainer.revalidate()
                    timelineContainer.repaint()
                } else {
                    snapshot.forEachIndexed { index, item ->
                        val card = timelineCardCache.getOrPut(item.id) { TimelineCard() }
                        card.bind(item, index, snapshot.size)
                    }
                    timelineContainer.revalidate()
                    timelineContainer.repaint()
                }
            }
            
            if (snapshot.isNotEmpty() && this::timelineScrollPane.isInitialized && timelineAutoScroll) {
                val bar = timelineScrollPane.verticalScrollBar
                suppressTimelineScrollListener = true
                bar.value = bar.maximum
                suppressTimelineScrollListener = false
            }
        }
        refreshPinnedIssues()
    }

    private fun requestTimelineRefresh() {
        timelineRefreshRequests.tryEmit(Unit)
    }

    private fun installTimelineScrollListener(scrollPane: JBScrollPane) {
        val bar = scrollPane.verticalScrollBar
        bar.addAdjustmentListener {
            if (suppressTimelineScrollListener) return@addAdjustmentListener
            timelineAutoScroll = isScrollbarNearBottom(bar)
        }
    }

    private fun isScrollbarNearBottom(bar: JScrollBar): Boolean {
        val tolerance = JBUI.scale(24)
        return bar.value + bar.visibleAmount >= bar.maximum - tolerance
    }

    private fun toggleTimelineEntry(entryId: Long) {
        if (expandedTimelineItems.contains(entryId)) {
            expandedTimelineItems.remove(entryId)
        } else {
            expandedTimelineItems.add(entryId)
        }
        requestTimelineRefresh()
    }

    private fun shouldCollapseDetail(category: TimelineCategory, detail: String): Boolean {
        if (detail.isBlank()) return false
        if (category == TimelineCategory.THINKING || category == TimelineCategory.AGENT) return true
        return detail.length > 200 || detail.contains('\n')
    }

    private fun clipForTimeline(text: String?, max: Int = 120): String {
        if (text.isNullOrBlank()) return ""
        val collapsed = text.replace("\\s+".toRegex(), " ").trim()
        val stripped = stripMarkdownFormatting(collapsed)
        return if (stripped.length <= max) stripped else stripped.take(max) + "…"
    }

    private fun stripMarkdownFormatting(text: String): String {
        return text
            .replace("""\*\*(.+?)\*\*""".toRegex(), "$1")
            .replace("""\*(.+?)\*""".toRegex(), "$1")
            .replace("""__(.+?)__""".toRegex(), "$1")
            .replace("""_(.+?)_""".toRegex(), "$1")
            .replace("""~~(.+?)~~""".toRegex(), "$1")
            .replace("""`(.+?)`""".toRegex(), "$1")
            .replace("""\[(.+?)\]\(.+?\)""".toRegex(), "$1")
            .replace("""^#+\s+""".toRegex(RegexOption.MULTILINE), "")
            .replace("""^\s*[-*+]\s+""".toRegex(RegexOption.MULTILINE), "")
            .replace("""^\s*\d+\.\s+""".toRegex(RegexOption.MULTILINE), "")
    }

    private fun renderMarkdownHtml(markdown: String, background: Color, foreground: Color): String {
        val source = if (markdown.isBlank()) " " else markdown
        val parser = MarkdownParser(markdownFlavour)
        val parsedTree = parser.buildMarkdownTreeFromString(source)
        val html = HtmlGenerator(source, parsedTree, markdownFlavour).generateHtml()
        val body = extractHtmlBody(html)
        val labelFont = UIUtil.getLabelFont()
        val fontFamily = labelFont.family.replace("'", "\\'")
        val fgColor = ColorUtil.toHtmlColor(foreground)
        val bgColor = ColorUtil.toHtmlColor(background)
        val codeBg = ColorUtil.toHtmlColor(if (UIUtil.isUnderDarcula()) Color(0x2D2F34) else Color(0xF3F4F7))
        val borderColor = ColorUtil.toHtmlColor(JBColor(0xD0D7DE, 0x4A4D54))
        val linkColor = ColorUtil.toHtmlColor(JBColor(0x1A73E8, 0x8AB4F8))
        val blockquoteColor = ColorUtil.toHtmlColor(JBColor(0x5E6C84, 0x9AA5C0))
        return """
            <html>
            <head>
            <style>
            body {
                font-family: '$fontFamily';
                font-size: ${labelFont.size}pt;
                color: $fgColor;
                background-color: $bgColor;
                margin: 0;
                word-wrap: break-word;
            }
            pre {
                background-color: $codeBg;
                padding: 8px;
                border-radius: 6px;
                overflow-x: auto;
            }
            code {
                background-color: $codeBg;
                padding: 2px 4px;
                border-radius: 4px;
            }
            blockquote {
                border-left: 4px solid $borderColor;
                margin: 8px 0;
                padding-left: 12px;
                color: $blockquoteColor;
            }
            table {
                border-collapse: collapse;
                margin: 8px 0;
            }
            th, td {
                border: 1px solid $borderColor;
                padding: 4px 8px;
            }
            a {
                color: $linkColor;
                text-decoration: none;
            }
            ul, ol {
                margin-left: 20px;
            }
            </style>
            </head>
            <body>$body</body>
            </html>
        """.trimIndent()
    }

    private fun extractHtmlBody(html: String): String {
        val bodyStart = html.indexOf("<body", ignoreCase = true)
        if (bodyStart < 0) return html
        val startTagEnd = html.indexOf('>', bodyStart)
        if (startTagEnd < 0) return html.substring(bodyStart)
        val endIndex = html.lastIndexOf("</body>", ignoreCase = true)
        if (endIndex < 0 || endIndex <= startTagEnd) return html.substring(startTagEnd + 1)
        return html.substring(startTagEnd + 1, endIndex)
    }

    private fun recordReasoningTimelineEntry(text: String?) {
        val detail = text?.trim()
        if (detail.isNullOrBlank()) return
        recordTimelineEvent(
            category = TimelineCategory.THINKING,
            title = "Thinking",
            detail = detail,
            collapsible = true,
            status = TimelineStatus.INFO,
            pinned = false
        )
    }

    private fun recordAgentTimelineEntry(text: String?) {
        val detail = text?.trim()
        if (detail.isNullOrBlank()) return
        recordTimelineEvent(
            category = TimelineCategory.AGENT,
            title = "Assistant reply",
            detail = detail,
            collapsible = true,
            status = TimelineStatus.SUCCEEDED,
            pinned = false
        )
    }

    private fun reasoningBufferKey(threadId: String?, turnId: String?): String {
        return when {
            !turnId.isNullOrBlank() -> timelineKey("reasoning", turnId)
            !threadId.isNullOrBlank() -> timelineKey("reasoning", "thread-$threadId")
            else -> timelineKey("reasoning", "global")
        }
    }

    private fun agentBufferKey(threadId: String?, turnId: String?): String {
        return when {
            !turnId.isNullOrBlank() -> timelineKey("agent", turnId)
            !threadId.isNullOrBlank() -> timelineKey("agent", "thread-$threadId")
            else -> timelineKey("agent", "global")
        }
    }

    private fun appendReasoningDelta(threadId: String?, turnId: String?, chunk: String) {
        if (chunk.isBlank()) return
        val key = reasoningBufferKey(threadId, turnId)
        val buffer = reasoningTimelineBuffers.getOrPut(key) { StringBuilder() }
        buffer.append(chunk)
    }

    private fun finalizeReasoningEntry(threadId: String?, turnId: String?, finalChunk: String?): String? {
        val key = reasoningBufferKey(threadId, turnId)
        val buffer = reasoningTimelineBuffers.remove(key)
        val merged = mergeBufferedText(buffer, finalChunk).trim()
        return merged.takeIf { it.isNotBlank() }
    }

    private fun clearReasoningTimelineBuffer(threadId: String?, turnId: String?) {
        val key = reasoningBufferKey(threadId, turnId)
        reasoningTimelineBuffers.remove(key)
    }

    private fun appendAgentResponseDelta(threadId: String?, turnId: String?, chunk: String) {
        if (chunk.isBlank()) return
        val key = agentBufferKey(threadId, turnId)
        val buffer = agentResponseTimelineBuffers.getOrPut(key) { StringBuilder() }
        buffer.append(chunk)
    }

    private fun finalizeAgentResponseEntry(threadId: String?, turnId: String?, finalChunk: String?): String? {
        val key = agentBufferKey(threadId, turnId)
        val buffer = agentResponseTimelineBuffers.remove(key)
        val merged = mergeBufferedText(buffer, finalChunk).trim()
        return merged.takeIf { it.isNotBlank() }
    }

    private fun clearAgentResponseTimeline(threadId: String?, turnId: String?) {
        val key = agentBufferKey(threadId, turnId)
        agentResponseTimelineBuffers.remove(key)
    }

    private fun mergeBufferedText(buffer: StringBuilder?, finalChunk: String?): String {
        val existing = buffer?.toString().orEmpty()
        val addition = finalChunk.orEmpty()
        if (existing.isBlank()) return addition
        if (addition.isBlank()) return existing
        return if (existing.endsWith(addition)) existing else existing + addition
    }

    private fun handleTokenCount(event: CodexEvent.TokenCount) {
        tokenUsageSnapshot = TokenUsageSnapshot(
            turnTokens = event.lastTokenUsage,
            totalTokens = event.totalTokenUsage,
            updatedAt = Instant.now()
        )
    }

    private fun handleRateLimitsUpdated(event: CodexEvent.RateLimitsUpdated) {
        val snapshot = parseRateLimitSnapshot(event.limits)
        rateLimitSnapshot = snapshot
        updateUsageLabel(snapshot)
    }

    private fun updateUsageLabel(snapshot: RateLimitSnapshot?) {
        SwingUtilities.invokeLater {
            if (snapshot == null || (snapshot.primary == null && snapshot.secondary == null)) {
                usageLabel.text = "Rate: —"
                usageLabel.toolTipText = "Rate limits unavailable"
                usageLabel.foreground = JBColor.GRAY
                return@invokeLater
            }
            val chipText = snapshot.primary?.let { formatRateLimitChip(it) } ?: "—"
            usageLabel.text = "Rate: $chipText"
            usageLabel.toolTipText = buildRateLimitTooltip(snapshot)
            val usage = listOfNotNull(snapshot.primary?.usageFraction(), snapshot.secondary?.usageFraction()).maxOrNull()
            val warn = usage != null && usage >= RATE_LIMIT_WARN_THRESHOLD
            usageLabel.foreground = if (warn) JBColor(0xE65100, 0xFFB74D) else JBColor.GRAY
        }
    }

    private fun formatRateLimitChip(window: RateLimitWindow): String {
        val percent = window.usedPercent ?: window.usageFraction()?.times(100)
        val percentText = percent?.let { "${it.roundToInt()}%" } ?: "—"
        val resetText = formatResetTime(window.resetsAtEpochSeconds)
        return if (resetText != null) "$percentText · reset $resetText" else percentText
    }

    private fun describeRateLimitWindow(window: RateLimitWindow): String {
        val percent = window.usedPercent ?: window.usageFraction()?.times(100)
        val percentText = percent?.let { "${it.roundToInt()}%" } ?: "unknown"
        val windowText = window.windowMinutes?.let { "${it}m window" }
        val resetText = formatResetTime(window.resetsAtEpochSeconds)?.let { "resets $it" }
        val parts = mutableListOf("Used $percentText")
        windowText?.let { parts += it }
        resetText?.let { parts += it }
        return "${window.label}: ${parts.joinToString(" • ")}"
    }

    private fun buildRateLimitTooltip(snapshot: RateLimitSnapshot?): String {
        if (snapshot == null) return "Rate limits unavailable"
        val parts = mutableListOf<String>()
        snapshot.primary?.let { parts += describeRateLimitWindow(it) }
        snapshot.secondary?.let { parts += describeRateLimitWindow(it) }
        if (parts.isEmpty()) return "Rate limits unavailable"
        return parts.joinToString(separator = "\n")
    }

    private fun formatResetTime(epochSeconds: Long?): String? {
        if (epochSeconds == null || epochSeconds <= 0) return null
        return try {
            val zoned = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
            resetTimeFormatter.format(zoned)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRateLimitSnapshot(payload: JsonObject): RateLimitSnapshot? {
        val container = payload["rateLimits"]?.jsonObject ?: payload
        val primary = container["primary"]?.jsonObject?.let { parseRateLimitWindow("Primary", it) }
        val secondary = container["secondary"]?.jsonObject?.let { parseRateLimitWindow("Secondary", it) }
        if (primary == null && secondary == null) return null
        return RateLimitSnapshot(primary, secondary)
    }

    private fun parseRateLimitWindow(label: String, json: JsonObject): RateLimitWindow? {
        if (json.isEmpty()) return null
        val usedPercent = json.doubleAny("usedPercent", "used_percentage", "usedPercentPct")
        val windowMinutes = json.intAny("windowDurationMins", "windowMinutes", "window")
        val resetsAt = json.longAny("resetsAt", "resetAt", "resetEpochSeconds")
        val limit = json.longAny("limit", "budget", "capacity")
        val used = json.longAny("used", "usage", "usedTokens", "tokensUsed")
        val remaining = json.longAny("remaining", "tokensRemaining")
        return RateLimitWindow(label, usedPercent, windowMinutes, resetsAt, limit, used, remaining)
    }

    private fun RateLimitWindow.usageFraction(): Double? {
        usedPercent?.let { return (it / 100.0).coerceIn(0.0, 1.0) }
        if (limit != null && limit > 0) {
            used?.let { return (it.toDouble() / limit).coerceIn(0.0, 1.0) }
            remaining?.let { return (1.0 - (it.toDouble() / limit)).coerceIn(0.0, 1.0) }
        }
        return null
    }

    private fun handleToolBeginEvent(event: CodexEvent.McpToolCallBegin) {
        val argsPreview = prettyPrintJson(event.arguments)
        val summary = argsPreview?.let { clipForTimeline(it, 180) } ?: "Started"
        val detail = buildToolDetail(arguments = argsPreview, result = null, error = null)
        val actions = mutableListOf<TimelineAction>()
        if (!argsPreview.isNullOrBlank()) {
            actions += TimelineAction(TimelineActionType.COPY, "Copy arguments") { copyToClipboard(argsPreview) }
        }
        val item = TimelineToolItem(
            id = nextTimelineId(),
            key = timelineKey("tool", event.callId),
            category = TimelineCategory.TOOL,
            status = TimelineStatus.RUNNING,
            title = "${event.server}.${event.tool}",
            summary = summary,
            server = event.server,
            toolName = event.tool,
            requestPreview = argsPreview,
            resultPreview = null,
            actions = actions,
            expandableContent = detail?.let { TimelineContent.Text(it) }
        )
        addTimelineItem(item)
    }

    private fun handleToolEndEvent(event: CodexEvent.McpToolCallEnd) {
        val key = timelineKey("tool", event.callId)
        val resultPreview = event.result?.let { prettyPrintJson(it) }
        val error = event.error
        val status = if (error.isNullOrBlank()) TimelineStatus.SUCCEEDED else TimelineStatus.FAILED
        val summary = when {
            !error.isNullOrBlank() -> clipForTimeline(error)
            !resultPreview.isNullOrBlank() -> clipForTimeline(resultPreview)
            else -> "Completed"
        }
        val updated = updateTimelineItem(key) { existing ->
            val toolItem = existing as? TimelineToolItem ?: return@updateTimelineItem null
            val detail = buildToolDetail(toolItem.requestPreview, resultPreview ?: toolItem.resultPreview, error)
            val resultText = resultPreview ?: toolItem.resultPreview
            val actions = mutableListOf<TimelineAction>()
            toolItem.requestPreview?.let {
                actions += TimelineAction(TimelineActionType.COPY, "Copy arguments") { copyToClipboard(it) }
            }
            if (!resultText.isNullOrBlank()) {
                actions += TimelineAction(TimelineActionType.COPY, "Copy result") { copyToClipboard(resultText) }
            }
            toolItem.copy(
                status = status,
                summary = summary,
                resultPreview = resultText,
                actions = actions,
                expandableContent = detail?.let { TimelineContent.Text(it) },
                timestamp = Instant.now()
            )
        }
        if (!updated) {
            val fallbackDetail = buildToolDetail(null, resultPreview, error)
            val fallbackActions = mutableListOf<TimelineAction>()
            if (!resultPreview.isNullOrBlank()) {
                fallbackActions += TimelineAction(TimelineActionType.COPY, "Copy result") { copyToClipboard(resultPreview) }
            }
            addTimelineItem(
                TimelineToolItem(
                    id = nextTimelineId(),
                    key = key,
                    category = TimelineCategory.TOOL,
                    status = status,
                    title = "Tool ${event.callId}",
                    summary = summary,
                    server = null,
                    toolName = null,
                    requestPreview = null,
                    resultPreview = resultPreview,
                    actions = fallbackActions,
                    expandableContent = fallbackDetail?.let { TimelineContent.Text(it) }
                )
            )
        }
    }

    private fun handleExecBegin(event: CodexEvent.ExecCommandBegin) {
        val commandLine = event.command.joinToString(" ")
        val summary = clipForTimeline(commandLine, 160)
        val log = TimelineContent.ExecLog(commandLine, event.cwd)
        val actions = listOf(
            TimelineAction(TimelineActionType.COPY, "Copy command") { copyToClipboard(commandLine) }
        )
        val item = TimelineExecItem(
            id = nextTimelineId(),
            key = timelineKey("exec", event.callId),
            category = TimelineCategory.EXEC,
            status = TimelineStatus.RUNNING,
            title = "Exec ${friendlyFileName(event.cwd)}",
            summary = summary,
            log = log,
            actions = actions
        )
        addTimelineItem(item)
    }

    private fun handleExecOutput(event: CodexEvent.ExecCommandOutputDelta) {
        val key = timelineKey("exec", event.callId)
        updateTimelineItem(key) { existing ->
            val execItem = existing as? TimelineExecItem ?: return@updateTimelineItem null
            val log = execItem.log
            val chunk = event.chunk
            if (event.stream.equals("stderr", true)) {
                log.stderr.add(chunk)
            } else {
                log.stdout.add(chunk)
            }
            execItem.copy(
                summary = buildExecSummary(log),
                timestamp = Instant.now(),
                log = log,
                expandableContent = log
            )
        }
    }

    private fun handleExecEnd(event: CodexEvent.ExecCommandEnd) {
        val key = timelineKey("exec", event.callId)
        val exitCode = event.exitCode
        val updated = updateTimelineItem(key) { existing ->
            val execItem = existing as? TimelineExecItem ?: return@updateTimelineItem null
            val status = determineExecStatus(exitCode, execItem.log)
            val summary = buildExecSummary(execItem.log, exitCode)
            val logText = formatExecLog(execItem.log)
            val actions = execItem.actions.toMutableList()
            if (logText.isNotBlank()) {
                actions += TimelineAction(TimelineActionType.COPY, "Copy output") { copyToClipboard(logText) }
            }
            execItem.copy(
                status = status,
                summary = summary,
                actions = actions,
                timestamp = Instant.now(),
                log = execItem.log,
                expandableContent = execItem.log
            )
        }
        if (!updated) {
            val log = TimelineContent.ExecLog("(unknown)", null)
            val status = determineExecStatus(exitCode, log)
            val summary = "Exit $exitCode"
            addTimelineItem(
                TimelineExecItem(
                    id = nextTimelineId(),
                    key = key,
                    category = TimelineCategory.EXEC,
                    status = status,
                    title = "Exec ${event.callId}",
                    summary = summary,
                    log = log
                )
            )
        }
    }
    
    private fun determineExecStatus(exitCode: Int, log: TimelineContent.ExecLog): TimelineStatus {
        return when {
            exitCode == 0 -> TimelineStatus.SUCCEEDED
            log.stderr.isNotEmpty() -> TimelineStatus.FAILED
            log.stdout.isNotEmpty() -> TimelineStatus.SUCCEEDED
            else -> TimelineStatus.FAILED
        }
    }

    private fun handlePatchApproval(event: CodexEvent.ApplyPatchApprovalRequest) {
        val key = timelineKey("patch", event.callId)
        val files = event.fileChanges.keys.toList()
        val summary = if (files.isEmpty()) "No file changes" else clipForTimeline(files.joinToString(), 180)
        val detail = buildPatchDetail(event.fileChanges, event.reason)
        val actions = mutableListOf<TimelineAction>()
        detail?.let {
            actions += TimelineAction(TimelineActionType.COPY, "Copy patch") { copyToClipboard(it) }
        }
        val item = TimelineApprovalItem(
            id = nextTimelineId(),
            key = key,
            category = TimelineCategory.APPROVAL,
            status = TimelineStatus.RUNNING,
            title = "Approval required",
            summary = summary,
            files = files,
            requestReason = event.reason,
            actions = actions,
            expandableContent = detail?.let { TimelineContent.Text(it) }
        )
        addTimelineItem(item)
    }

    private fun handlePatchApplyBegin(event: CodexEvent.PatchApplyBegin) {
        val key = timelineKey("patch", event.callId)
        val statusText = if (event.autoApproved) "Auto-approved" else "Applying patch"
        val updated = updateTimelineItem(key) { existing ->
            val approvalItem = existing as? TimelineApprovalItem ?: return@updateTimelineItem null
            approvalItem.copy(
                status = TimelineStatus.RUNNING,
                summary = statusText,
                timestamp = Instant.now()
            )
        }
        if (!updated) {
            recordTimelineEvent(TimelineCategory.PATCH, statusText, key = key)
        }
    }

    private fun handlePatchApplyEnd(event: CodexEvent.PatchApplyEnd) {
        val key = timelineKey("patch", event.callId)
        val status = if (event.success) TimelineStatus.SUCCEEDED else TimelineStatus.FAILED
        val summary = if (event.success) "Patch applied" else "Patch failed"
        val updated = updateTimelineItem(key) { existing ->
            val approvalItem = existing as? TimelineApprovalItem ?: return@updateTimelineItem null
            approvalItem.copy(
                status = status,
                summary = summary,
                timestamp = Instant.now()
            )
        }
        if (!updated) {
            val category = if (event.success) TimelineCategory.PATCH else TimelineCategory.ERROR
            recordTimelineEvent(category, summary, "callId=${event.callId}", key = key)
        }
    }

    private fun handleSearchBegin(event: CodexEvent.WebSearchBegin) {
        val key = timelineKey("search", event.callId)
        val summary = clipForTimeline(event.query, 160)
        val actions = listOf(
            TimelineAction(TimelineActionType.COPY, "Copy query") { copyToClipboard(event.query) }
        )
        val item = TimelineSearchItem(
            id = nextTimelineId(),
            key = key,
            category = TimelineCategory.SEARCH,
            status = TimelineStatus.RUNNING,
            title = "Web search",
            summary = summary,
            query = event.query,
            provider = "web",
            resultPreview = null,
            actions = actions,
            expandableContent = TimelineContent.Text(event.query)
        )
        addTimelineItem(item)
    }

    private fun handleSearchEnd(event: CodexEvent.WebSearchEnd) {
        val key = timelineKey("search", event.callId)
        val updated = updateTimelineItem(key) { existing ->
            val searchItem = existing as? TimelineSearchItem ?: return@updateTimelineItem null
            searchItem.copy(
                status = TimelineStatus.SUCCEEDED,
                summary = searchItem.summary ?: "Completed",
                timestamp = Instant.now()
            )
        }
        if (!updated) {
            recordTimelineEvent(TimelineCategory.SEARCH, "Search", "completed", key = key)
        }
    }

    private fun updatePlanList(steps: List<PlanStep>, explanation: String?) {
        if (!this::planStepsContainer.isInitialized || !this::planPanelWrapper.isInitialized) return

        val items = steps.map { PlanStepView(it.step, it.status) }
        activePlanSteps = items
        val completedCount = items.count { it.isCompleted() }
        val totalCount = items.size

        SwingUtilities.invokeLater {
            if (totalCount == 0) {
                planPanelWrapper.isVisible = false
                planAutoContextButton.isEnabled = false
                planStepsContainer.removeAll()
                planProgressBadge.setProgress(0, 0)
                planStepsContainer.revalidate()
                planStepsContainer.repaint()
                return@invokeLater
            }

            planPanelWrapper.isVisible = true
            planAutoContextButton.isEnabled = true
            planProgressLabel.text = "$completedCount out of $totalCount tasks completed"
            planProgressBadge.setProgress(completedCount, totalCount)

            if (explanation.isNullOrBlank()) {
                planExplanationLabel.isVisible = false
            } else {
                planExplanationLabel.isVisible = true
                planExplanationLabel.text = explanation
            }

            planStepsContainer.removeAll()
            items.forEachIndexed { index, view ->
                planStepsContainer.add(createPlanStepRow(index + 1, view))
            }
            planStepsContainer.revalidate()
            planStepsContainer.repaint()
        }
    }

    private fun appendChunkToPane(target: JTextPane, chunk: TranscriptChunk) {
        val attrs = transcriptAttributes[chunk.style] ?: transcriptAttributes[TranscriptStyle.INFO]
        try {
            val doc = target.styledDocument
            doc.insertString(doc.length, chunk.text, attrs)
            target.caretPosition = doc.length
        } catch (_: BadLocationException) {
        }
    }

    private fun appendReasoning(text: String) {
        reasoningBuffer.append(text)
        SwingUtilities.invokeLater {
            try {
                val doc = reasoningPane.styledDocument
                doc.insertString(doc.length, text, reasoningAttributes)
                reasoningPane.caretPosition = doc.length
            } catch (_: BadLocationException) {
            }
        }
    }

    private fun updateThreadMetadata(model: String?) {
        if (!model.isNullOrBlank()) currentModelLabel = model
    }

    private fun updateConnectionStatus(text: String, icon: Icon, color: JBColor) {
        SwingUtilities.invokeLater {
            connectionStatusLabel.text = text
            connectionStatusLabel.icon = icon
            connectionStatusLabel.foreground = color
        }
    }

    private fun refreshAttachmentChips() {
        SwingUtilities.invokeLater {
            attachmentChipsPanel.removeAll()
            if (attachedFiles.isEmpty()) {
                attachmentChipsPanel.add(JBLabel("None").apply { foreground = JBColor.GRAY })
            } else {
                attachedFiles.forEach { path ->
                    val name = friendlyFileName(path)
                    attachmentChipsPanel.add(buildAttachmentChip(name) {
                        attachedFiles.remove(path)
                        refreshAttachmentChips()
                    })
                }
            }
            attachmentChipsPanel.revalidate()
            attachmentChipsPanel.repaint()
        }
    }

    private fun buildAttachmentChip(name: String, onRemove: () -> Unit): JComponent {
        return JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = true
            background = JBColor(0xEEF5FF, 0x2C2F3A)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(outlineColor, 1, 1, 1, 1),
                JBUI.Borders.empty(2, 6)
            )
            add(JBLabel(name))
            val iconButton = IconButton("Remove attachment", AllIcons.Actions.Close, AllIcons.Actions.Close)
            add(InplaceButton(iconButton, ActionListener { onRemove() }).apply {
                isOpaque = false
                preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
            })
        }
    }

    private fun friendlyFileName(path: String): String {
        return try {
            val name = Paths.get(path).fileName?.toString()
            if (name.isNullOrBlank()) path else name
        } catch (_: Exception) {
            path
        }
    }

    private fun appendTranscript(text: String, style: TranscriptStyle = TranscriptStyle.INFO) {
        transcriptBuffer.append(text)
        _transcriptUpdates.tryEmit(TranscriptChunk(text, style))
    }

    private inner class TimelineCard : JPanel(BorderLayout()) {
        private val detailCardBackground = JBColor(0xF6F8FA, 0x2B2D30)
        private val rail = TimelineRailComponent()
        private val contentHolder = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 8, 0)
        }
        private val stack = JBPanel<JBPanel<*>>(VerticalLayout(6)).apply {
            isOpaque = false
        }
        private val titleRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
        }
        private val titleFlow = JBPanel<JBPanel<*>>(HorizontalLayout(6)).apply {
            isOpaque = false
        }
        private val titleComponent = SimpleColoredComponent()
        private val planTagLabel = JBLabel().apply {
            isOpaque = true
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor(0xC5CAE9, 0x3A3F54)),
                JBUI.Borders.empty(0, 6)
            )
            background = JBColor(0xE8EAF6, 0x2C2F3A)
            foreground = JBColor(0x1A237E, 0xC5CAE9)
            font = font.deriveFont(font.size - 2f)
            isVisible = false
        }
        private val collapseIcon = JLabel(AllIcons.General.ExpandComponent).apply {
            isVisible = false
        }
        private val statusBadgeLabel = JBLabel().apply {
            isOpaque = true
        }
        private val summaryArea = JBTextArea().apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
            margin = JBUI.emptyInsets()
            font = UIUtil.getLabelFont()
        }
        private val actionPanel = JBPanel<JBPanel<*>>(HorizontalLayout(6)).apply {
            isOpaque = false
        }
        private val detailCard = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            background = detailCardBackground
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor(0xD0D7DE, 0x3C3F41)),
                JBUI.Borders.empty(8, 12)
            )
        }
        private val detailPlainText = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = null
            margin = JBUI.emptyInsets()
            font = UIUtil.getLabelFont()
            background = detailCardBackground
            isOpaque = false
        }
        private val detailMarkdownPane = JEditorPane("text/html", "").apply {
            isEditable = false
            border = null
            margin = JBUI.emptyInsets()
            editorKit = HTMLEditorKit()
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)
            background = detailCardBackground
            isOpaque = false
        }
        private val detailCardLayout = CardLayout()
        private val detailPlainCard = "plain"
        private val detailMarkdownCard = "markdown"
        private val detailContentStack = JPanel(detailCardLayout).apply {
            isOpaque = false
            background = detailCardBackground
            add(detailPlainText, detailPlainCard)
            add(detailMarkdownPane, detailMarkdownCard)
        }
        private val detailCardDefaultBorder = detailCard.border
        private val borderlessDetailBorder = JBUI.Borders.empty()
        private var activeDetailBackground: Color = detailCardBackground
        internal var boundItem: TimelineItem? = null

        init {
            isOpaque = false
            rail.isOpaque = false
            detailCard.add(detailContentStack, BorderLayout.CENTER)
            titleFlow.add(titleComponent)
            titleFlow.add(planTagLabel)
            val metaRight = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(collapseIcon)
                add(statusBadgeLabel)
            }
            titleRow.add(titleFlow, BorderLayout.CENTER)
            titleRow.add(metaRight, BorderLayout.EAST)
            stack.add(titleRow)
            stack.add(summaryArea)
            stack.add(actionPanel)
            stack.add(detailCard)
            contentHolder.add(stack, BorderLayout.CENTER)
            add(rail, BorderLayout.WEST)
            add(contentHolder, BorderLayout.CENTER)
            border = JBUI.Borders.empty(4, 0)

            val doubleClickToggle = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val item = boundItem ?: return
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    if (e.clickCount < 2) return
                    if (!item.isExpandable) return
                    toggleTimelineEntry(item.id)
                }
            }
            addMouseListener(doubleClickToggle)
            contentHolder.addMouseListener(doubleClickToggle)
            stack.addMouseListener(doubleClickToggle)

            collapseIcon.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            collapseIcon.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val item = boundItem ?: return
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    if (!item.isExpandable) return
                    toggleTimelineEntry(item.id)
                    e.consume()
                }
            })
        }

        fun bind(item: TimelineItem, index: Int, totalCount: Int) {
            boundItem = item

            val backgroundColor = UIUtil.getPanelBackground()
            val foregroundColor = UIUtil.getLabelForeground()

            background = backgroundColor
            contentHolder.background = backgroundColor
            stack.background = backgroundColor
            titleRow.background = backgroundColor
            titleFlow.background = backgroundColor
            summaryArea.foreground = foregroundColor

            val accent = item.category.accent
            rail.accent = accent
            rail.showTop = index > 0
            rail.showBottom = index < totalCount - 1

            titleComponent.clear()
            titleComponent.append(item.title, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, accent))
            planTagLabel.isVisible = !item.planTag.isNullOrBlank()
            planTagLabel.text = item.planTag ?: ""
            collapseIcon.isVisible = item.isExpandable
            val expanded = item.isExpandable && expandedTimelineItems.contains(item.id)
            collapseIcon.icon = if (expanded) AllIcons.General.CollapseComponent else AllIcons.General.ExpandComponent
            collapseIcon.toolTipText = if (item.isExpandable) {
                if (expanded) "Collapse details" else "Expand details"
            } else null
            cursor = if (item.isExpandable) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()

            configureStatusBadge(statusBadgeLabel, item.status)
            statusBadgeLabel.isVisible = item.status != TimelineStatus.INFO

            val showSummary = !item.summary.isNullOrBlank() && (!item.isExpandable || !expanded)
            summaryArea.isVisible = showSummary
            summaryArea.text = if (showSummary) item.summary else ""

            configureDetailChromeForCategory(item.category)
            populateActionPanel(item)
            populateDetailCard(item, foregroundColor, expanded)
        }

        private fun populateActionPanel(item: TimelineItem) {
            actionPanel.removeAll()
            if (item.actions.isEmpty()) {
                actionPanel.isVisible = false
                return
            }
            item.actions.forEach { action ->
                val iconButton = IconButton(action.tooltip, action.type.icon, action.type.icon)
                actionPanel.add(InplaceButton(iconButton, ActionListener { invokeTimelineAction(action) }).apply {
                    isOpaque = false
                    putClientProperty("JButton.buttonType", "square")
                })
            }
            actionPanel.isVisible = true
        }

        private fun configureDetailChromeForCategory(category: TimelineCategory) {
            val borderless = category == TimelineCategory.USER || category == TimelineCategory.AGENT
            if (borderless) {
                val bg = UIUtil.getPanelBackground()
                detailCard.border = borderlessDetailBorder
                detailCard.background = bg
                detailCard.isOpaque = false
                detailContentStack.background = bg
                activeDetailBackground = bg
            } else {
                detailCard.border = detailCardDefaultBorder
                detailCard.background = detailCardBackground
                detailCard.isOpaque = true
                detailContentStack.background = detailCardBackground
                activeDetailBackground = detailCardBackground
            }
            detailPlainText.background = activeDetailBackground
            detailMarkdownPane.background = activeDetailBackground
        }

        private fun populateDetailCard(item: TimelineItem, foregroundColor: Color, expanded: Boolean) {
            val content = item.expandableContent
            detailCard.isVisible = expanded && content != null
            if (!detailCard.isVisible) return
            when (content) {
                is TimelineContent.Text -> {
                    if (content.format == TimelineTextFormat.MARKDOWN) {
                        detailMarkdownPane.text = renderMarkdownHtml(content.text, activeDetailBackground, foregroundColor)
                        detailMarkdownPane.caretPosition = 0
                        detailCardLayout.show(detailContentStack, detailMarkdownCard)
                    } else {
                        detailPlainText.foreground = foregroundColor
                        detailPlainText.text = content.text
                        detailPlainText.background = activeDetailBackground
                        detailCardLayout.show(detailContentStack, detailPlainCard)
                    }
                }
                is TimelineContent.ExecLog -> {
                    detailPlainText.foreground = foregroundColor
                    detailPlainText.text = formatExecLog(content)
                    detailPlainText.background = activeDetailBackground
                    detailCardLayout.show(detailContentStack, detailPlainCard)
                }
                null -> {
                    detailPlainText.foreground = foregroundColor
                    detailPlainText.text = ""
                    detailCardLayout.show(detailContentStack, detailPlainCard)
                }
            }
        }
    }

    private inner class TimelineRailComponent : JComponent() {
        var showTop: Boolean = false
        var showBottom: Boolean = false
        var accent: JBColor = JBColor.GRAY

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(24), JBUI.scale(48))

        override fun paintComponent(g: Graphics) {
            val g2 = g as? Graphics2D ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val centerX = width / 2
            val circleY = height / 2
            g2.color = JBColor(0xD0D7DE, 0x3C3F41)
            if (showTop) {
                g2.drawLine(centerX, 0, centerX, circleY)
            }
            if (showBottom) {
                g2.drawLine(centerX, circleY, centerX, height)
            }
            g2.color = accent
            val diameter = JBUI.scale(10)
            g2.fillOval(centerX - diameter / 2, circleY - diameter / 2, diameter, diameter)
        }
    }

    private fun createPlanStepRow(position: Int, value: PlanStepView): JComponent {
        val indicator = PlanStepIndicator(value.toState())
        val numberLabel = JBLabel("${position}.").apply {
            font = font.deriveFont(font.style or Font.BOLD)
            foreground = JBColor(0x1F2430, 0xDDE4EE)
        }
        val description = JBTextArea(value.step).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
            margin = JBUI.emptyInsets()
            font = UIUtil.getLabelFont()
            foreground = JBColor(0x1F2433, 0xE6EAF2)
        }
        val descriptionRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(numberLabel, BorderLayout.WEST)
            add(description, BorderLayout.CENTER)
            border = JBUI.Borders.empty(0, 8, 0, 0)
        }

        val left = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 4, 0, 0)
            add(indicator, BorderLayout.WEST)
            add(descriptionRow, BorderLayout.CENTER)
        }

        val statusLabel = JBLabel(formatPlanStatus(value.status)).apply {
            foreground = JBColor(0x60718B, 0x9AA5C0)
            font = font.deriveFont(font.size - 1f)
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 0, 6, 0)
            add(left, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.EAST)
        }
    }

    private fun PlanStepView.isCompleted(): Boolean = toState() == PlanStepState.DONE

    private fun PlanStepView.toState(): PlanStepState {
        val normalized = status.lowercase(Locale.getDefault())
        return when {
            normalized.contains("done") || normalized.contains("complete") || normalized.contains("success") -> PlanStepState.DONE
            normalized.contains("progress") || normalized.contains("active") || normalized.contains("working") -> PlanStepState.ACTIVE
            normalized.contains("blocked") || normalized.contains("waiting") -> PlanStepState.TODO
            else -> PlanStepState.TODO
        }
    }

    private fun formatPlanStatus(status: String): String {
        if (status.isBlank()) return "Pending"
        val normalized = status.replace('_', ' ').trim()
        return normalized.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun injectPlanContextIntoComposer() {
        if (activePlanSteps.isEmpty()) return
        val builder = StringBuilder().apply {
            append("Plan context:\n")
            activePlanSteps.forEachIndexed { index, step ->
                append("${index + 1}. [${formatPlanStatus(step.status)}] ${step.step}\n")
            }
        }
        val addition = builder.toString()
        val existing = inputArea.text
        inputArea.text = when {
            existing.isBlank() -> addition
            existing.endsWith("\n") -> existing + addition
            else -> existing + "\n\n" + addition
        }
        inputArea.caretPosition = inputArea.text.length
        inputArea.requestFocusInWindow()
    }

    private inner class PlanStepIndicator(private val state: PlanStepState) : JComponent() {
        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(28), JBUI.scale(28))

        override fun paintComponent(g: Graphics) {
            val g2 = g as? Graphics2D ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val diameter = min(width, height) - JBUI.scale(6)
            val x = (width - diameter) / 2
            val y = (height - diameter) / 2
            when (state) {
                PlanStepState.DONE -> {
                    g2.color = JBColor(0x4B6BFB, 0x90A4FF)
                    g2.fillOval(x, y, diameter, diameter)
                    g2.color = JBColor.WHITE
                    g2.stroke = BasicStroke(JBUI.scale(3).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.drawLine(x + diameter / 4, y + diameter / 2, x + diameter / 2, y + diameter * 3 / 4)
                    g2.drawLine(x + diameter / 2, y + diameter * 3 / 4, x + diameter * 3 / 4, y + diameter / 3)
                }
                PlanStepState.ACTIVE -> {
                    g2.color = JBColor(0x4B6BFB, 0x90A4FF)
                    g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.drawOval(x, y, diameter, diameter)
                    g2.fillOval(x + diameter / 3, y + diameter / 3, diameter / 3, diameter / 3)
                }
                PlanStepState.TODO -> {
                    g2.color = JBColor(0xC9D2E6, 0x4A4F60)
                    g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.drawOval(x, y, diameter, diameter)
                }
            }
        }
    }

    private inner class PlanProgressBadge : JComponent() {
        private var completed: Int = 0
        private var total: Int = 0

        fun setProgress(completed: Int, total: Int) {
            this.completed = completed
            this.total = total
            repaint()
        }

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(48), JBUI.scale(48))

        override fun paintComponent(g: Graphics) {
            val g2 = g as? Graphics2D ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val diameter = min(width, height) - JBUI.scale(6)
            val x = (width - diameter) / 2
            val y = (height - diameter) / 2
            g2.stroke = BasicStroke(JBUI.scale(4).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val trackColor = JBColor(0xDDE3F6, 0x3A3F4F)
            g2.color = trackColor
            g2.drawOval(x, y, diameter, diameter)
            if (total > 0 && completed > 0) {
                val sweep = (completed.toDouble() / total * 360.0).coerceAtMost(360.0).toInt().coerceAtLeast(2)
                g2.color = JBColor(0x4B6BFB, 0x90A4FF)
                g2.drawArc(x, y, diameter, diameter, 90, -sweep)
            }
            val dotDiameter = JBUI.scale(6)
            val dotY = (y - dotDiameter / 2 + JBUI.scale(2)).coerceAtLeast(0)
            g2.color = JBColor(0x4B6BFB, 0x90A4FF)
            g2.fillOval(width / 2 - dotDiameter / 2, dotY, dotDiameter, dotDiameter)
        }
    }

    private inner class StatusBubble(private val status: TimelineStatus) : JComponent() {
        init {
            isOpaque = false
        }

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(12), JBUI.scale(12))

        override fun paintComponent(g: Graphics) {
            val g2 = g as? Graphics2D ?: return
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val diameter = min(width, height)
            val x = (width - diameter) / 2
            val y = (height - diameter) / 2
            g2.color = status.color
            g2.fillOval(x, y, diameter, diameter)
        }
    }

    private enum class PlanStepState { TODO, ACTIVE, DONE }

    private data class TranscriptChunk(val text: String, val style: TranscriptStyle)

    private enum class TranscriptStyle(val color: JBColor) {
        INFO(JBColor(0x2F3542, 0xC7CCD5)),
        SYSTEM(JBColor(0x455A64, 0xB0BEC5)),
        USER(JBColor(0x0D47A1, 0x90CAF9)),
        AGENT(JBColor(0x1B5E20, 0xA5D6A7)),
        WARNING(JBColor(0xE65100, 0xFFB74D)),
        ERROR(JBColor(0xB71C1C, 0xEF9A9A))
    }

    private data class TokenUsageSnapshot(
        val turnTokens: Int? = null,
        val totalTokens: Int? = null,
        val updatedAt: Instant = Instant.EPOCH
    )

    private data class RateLimitWindow(
        val label: String,
        val usedPercent: Double?,
        val windowMinutes: Int?,
        val resetsAtEpochSeconds: Long?,
        val limit: Long?,
        val used: Long?,
        val remaining: Long?
    )

    private data class RateLimitSnapshot(
        val primary: RateLimitWindow?,
        val secondary: RateLimitWindow?
    )

    private sealed interface TimelineItem {
        val id: Long
        val key: String
        val category: TimelineCategory
        val status: TimelineStatus
        val title: String
        val summary: String?
        val timestamp: Instant
        val pinned: Boolean
        val planTag: String?
        val actions: List<TimelineAction>
        val expandableContent: TimelineContent?
        val isExpandable: Boolean get() = expandableContent != null
    }

    private data class TimelineNote(
        override val id: Long,
        override val key: String,
        override val category: TimelineCategory,
        override val status: TimelineStatus,
        override val title: String,
        override val summary: String?,
        override val timestamp: Instant = Instant.now(),
        override val pinned: Boolean = false,
        override val planTag: String? = null,
        override val actions: List<TimelineAction> = emptyList(),
        override val expandableContent: TimelineContent? = null
    ) : TimelineItem

    private data class TimelineExecItem(
        override val id: Long,
        override val key: String,
        override val category: TimelineCategory,
        override val status: TimelineStatus,
        override val title: String,
        override val summary: String?,
        val log: TimelineContent.ExecLog,
        override val timestamp: Instant = Instant.now(),
        override val pinned: Boolean = false,
        override val planTag: String? = null,
        override val actions: List<TimelineAction> = emptyList(),
        override val expandableContent: TimelineContent = log
    ) : TimelineItem

    private data class TimelineToolItem(
        override val id: Long,
        override val key: String,
        override val category: TimelineCategory,
        override val status: TimelineStatus,
        override val title: String,
        override val summary: String?,
        val server: String?,
        val toolName: String?,
        val requestPreview: String?,
        val resultPreview: String?,
        override val timestamp: Instant = Instant.now(),
        override val pinned: Boolean = false,
        override val planTag: String? = null,
        override val actions: List<TimelineAction> = emptyList(),
        override val expandableContent: TimelineContent? = null
    ) : TimelineItem

    private data class TimelineSearchItem(
        override val id: Long,
        override val key: String,
        override val category: TimelineCategory,
        override val status: TimelineStatus,
        override val title: String,
        override val summary: String?,
        val query: String,
        val provider: String?,
        val resultPreview: String?,
        override val timestamp: Instant = Instant.now(),
        override val pinned: Boolean = false,
        override val planTag: String? = null,
        override val actions: List<TimelineAction> = emptyList(),
        override val expandableContent: TimelineContent? = null
    ) : TimelineItem

    private data class TimelineApprovalItem(
        override val id: Long,
        override val key: String,
        override val category: TimelineCategory,
        override val status: TimelineStatus,
        override val title: String,
        override val summary: String?,
        val files: List<String>,
        val requestReason: String?,
        override val timestamp: Instant = Instant.now(),
        override val pinned: Boolean = true,
        override val planTag: String? = null,
        override val actions: List<TimelineAction> = emptyList(),
        override val expandableContent: TimelineContent? = null
    ) : TimelineItem

    private sealed interface TimelineContent {
        data class Text(val text: String, val format: TimelineTextFormat = TimelineTextFormat.PLAIN) : TimelineContent
        data class ExecLog(
            val command: String,
            val workingDirectory: String?,
            val stdout: MutableList<String> = mutableListOf(),
            val stderr: MutableList<String> = mutableListOf()
        ) : TimelineContent
    }
    private enum class TimelineTextFormat { PLAIN, MARKDOWN }

    private data class TimelineAction(
        val type: TimelineActionType,
        val tooltip: String,
        val handler: () -> Unit
    )

    private enum class TimelineActionType(val icon: Icon) {
        COPY(AllIcons.Actions.Copy),
        OPEN_DIFF(AllIcons.Actions.Diff),
        RETRY(AllIcons.Actions.Refresh),
        OPEN_FILE(AllIcons.General.OpenDisk)
    }

    private enum class TimelineStatus(
        val label: String,
        light: Int,
        dark: Int
    ) {
        RUNNING("Running", 0x1565C0, 0x90CAF9),
        SUCCEEDED("Success", 0x1B5E20, 0x81C784),
        FAILED("Failed", 0xB71C1C, 0xEF9A9A),
        WARNING("Warning", 0xE65100, 0xFFB74D),
        INFO("Info", 0x546E7A, 0xB0BEC5);

        val color: JBColor = JBColor(light, dark)
        val onColor: JBColor = JBColor.WHITE
    }

    private enum class TimelineCategory(
        val label: String,
        light: Int,
        dark: Int,
        val icon: Icon
    ) {
        USER("User", 0x0D47A1, 0x90CAF9, AllIcons.Actions.Edit),
        AGENT("Assistant", 0x1B5E20, 0xA5D6A7, AllIcons.Nodes.Plugin),
        THINKING("Thinking", 0x6A1B9A, 0xCE93D8, AllIcons.Actions.Lightning),
        PLAN("Plan", 0x37474F, 0xB0BEC5, AllIcons.Actions.Checked),
        ATTACHMENT("Attachment", 0x455A64, 0xB0BEC5, AllIcons.Actions.Download),
        APPROVAL("Approval", 0x006064, 0x4DD0E1, AllIcons.General.Balloon),
        TOOL("Tools", 0x00695C, 0x80CBC4, AllIcons.General.Settings),
        EXEC("Exec", 0x4E342E, 0xBCAAA4, AllIcons.Debugger.Console),
        PATCH("Patch", 0x5D4037, 0xD7CCC8, AllIcons.Actions.Diff),
        SEARCH("Search", 0x1A237E, 0x9FA8DA, AllIcons.Actions.Search),
        WARNING("Warnings", 0xE65100, 0xFFB74D, AllIcons.General.Warning),
        ERROR("Errors", 0xB71C1C, 0xEF9A9A, AllIcons.General.Error),
        INFO("Info", 0x5E6C84, 0xB0BEC5, AllIcons.General.Information);

        val accent: JBColor = JBColor(light, dark)
    }

    private data class PlanStepView(val step: String, val status: String)

    private data class OutgoingMessage(val text: String, val attachments: List<String>)

    private fun parseCliArgs(flags: String): List<String> {
        if (flags.isBlank()) return emptyList()
        return ParametersListUtil.parse(flags)
    }

    companion object {
        private const val WORKING_TITLE = "Working..."
        private const val RATE_LIMIT_WARN_THRESHOLD = 0.8
    }

    private fun runOnEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeLater(block)
        }
    }

    private suspend fun <T> onEdt(block: () -> T): T =
        suspendCancellableCoroutine { cont ->
            val runnable = Runnable {
                try {
                    cont.resume(block())
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }

            if (SwingUtilities.isEventDispatchThread()) {
                runnable.run()
            } else {
                SwingUtilities.invokeLater(runnable)
            }

            cont.invokeOnCancellation { }
        }
    
    override fun dispose() {
        cancelEventProcessing()
        conversationInitJob?.cancel()
        scope.cancel()
    }
}
