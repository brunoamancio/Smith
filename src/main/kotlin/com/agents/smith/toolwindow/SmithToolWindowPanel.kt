package com.agents.smith.toolwindow

import com.agents.smith.state.SmithState
import com.agents.smith.viewmodel.SmithViewModel
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBEmptyBorder
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.KeyboardFocusManager
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPopupMenu
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.JMenuItem
import javax.swing.SwingConstants

class SmithToolWindowPanel(private val project: Project) :
    JBPanel<SmithToolWindowPanel>(BorderLayout()),
    Disposable {

    private val connectionLabel = JBLabel("Offline")
    private val streamStatusLabel = JBLabel("Idle")
    private val historyModel = DefaultListModel<String>()
    private val historyList = JBList<String>(historyModel)
    private val chatModel = DefaultListModel<SmithState.Message>()
    private val chatList = JBList(chatModel)
    private val promptEditor = JBTextArea(4, 0)
    private val sendButton = JButton(AllIcons.Actions.Forward).apply {
        text = ""
        putClientProperty("JButton.buttonType", "roundRect")
        border = JBUI.Borders.empty(6)
        isFocusPainted = false
        toolTipText = "Send (Ctrl+Enter)"
    }

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private var stateJob: Job? = null
    private var viewModel: SmithViewModel? = null

    init {
        border = JBEmptyBorder(12)
        layout = BorderLayout(12, 12)
        add(buildHeader(), BorderLayout.NORTH)
        add(buildContent(), BorderLayout.CENTER)
        configurePromptActions()
    }

    fun bind(viewModel: SmithViewModel) {
        if (this.viewModel != null) return

        this.viewModel = viewModel
        Disposer.register(this, viewModel)

        stateJob = uiScope.launch {
            viewModel.state.collect { state ->
                render(state)
            }
        }
    }

    fun asContent(toolWindowTitle: String = "Smith"): Content {
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(this, toolWindowTitle, false)
        content.setDisposer(this)
        return content
    }

    private fun buildHeader(): JComponent {
        val titleLabel = JBLabel("Smith")
        titleLabel.font = titleLabel.font.deriveFont(titleLabel.font.size2D + 2f)

        val statusPanel = JBPanel<JBPanel<*>>(HorizontalLayout(8)).apply {
            isOpaque = false
            add(connectionLabel)
            add(streamStatusLabel)
            add(createSettingsButton())
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBEmptyBorder(0, 0, 8, 0)
            add(titleLabel, BorderLayout.WEST)
            add(statusPanel, BorderLayout.EAST)
        }
    }

    private fun buildContent(): JComponent {
        historyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        historyList.cellRenderer = createHistoryRenderer()

        chatList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        chatList.cellRenderer = SmithMessageRenderer()
        chatList.visibleRowCount = 10

        val historyPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBEmptyBorder(0, 0, 0, 8)
            add(TitledSeparator("History"), BorderLayout.NORTH)
            add(JBScrollPane(historyList), BorderLayout.CENTER)
        }

        val conversationPanel = JBPanel<JBPanel<*>>(BorderLayout(0, 8)).apply {
            add(TitledSeparator("Conversation"), BorderLayout.NORTH)
            add(JBScrollPane(chatList), BorderLayout.CENTER)
            add(buildPromptEditor(), BorderLayout.SOUTH)
        }

        return JBSplitter(false, 0.25f).apply {
            firstComponent = historyPanel
            secondComponent = conversationPanel
        }
    }

    private fun buildPromptEditor(): JComponent {
        promptEditor.lineWrap = true
        promptEditor.wrapStyleWord = true
        promptEditor.toolTipText = "Describe what you need help with. Press Ctrl+Enter to send."
        promptEditor.emptyText.text = "Type your task here, press Ctrl+Enter"
        promptEditor.margin = JBUI.insets(0)
        promptEditor.border = JBUI.Borders.empty()
        promptEditor.background = JBColor(0x1F1F24, 0x2B2D30)

        val scrollPane = JBScrollPane(promptEditor).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            viewport.background = promptEditor.background
        }

        val promptCard = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            background = promptEditor.background
        }

        val focusColor = JBColor(0x3D7DFF, 0x3D7DFF)
        val idleColor = JBColor(0x3A3F4B, 0x3A3F4B)

        fun applyBorder(focused: Boolean) {
            val color = if (focused) focusColor else idleColor
            val thickness = if (focused) 2 else 1
            promptCard.border = JBUI.Borders.merge(
                JBUI.Borders.customLine(color, thickness),
                JBUI.Borders.empty(12),
                true
            )
        }

        applyBorder(false)

        val focusListener = object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) = applyBorder(true)

            override fun focusLost(e: FocusEvent?) {
                val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                val hasFocusInside = focusOwner != null && promptCard.isAncestorOf(focusOwner)
                applyBorder(hasFocusInside)
            }
        }

        val actionsRow = createPromptActionsRow(focusListener)

        promptCard.add(scrollPane, BorderLayout.CENTER)
        promptCard.add(actionsRow, BorderLayout.SOUTH)

        promptEditor.addFocusListener(focusListener)

        return promptCard
    }

    private fun createPromptActionsRow(focusListener: FocusAdapter): JComponent {
        val plusButton = createToolbarIconButton(AllIcons.General.Add, "More actions").apply {
            addActionListener { showPromptActionsMenu(this) }
        }

        val modeOptions = arrayOf(
            ModeOption("Code", "Delegate coding task"),
            ModeOption("Ask", "Ask Junie anything")
        )

        val modeSelector = ComboBox(modeOptions).apply {
            renderer = ModeOptionRenderer()
            selectedIndex = 0
            isOpaque = false
            border = JBUI.Borders.empty()
            putClientProperty("JComponent.sizeVariant", "small")
            maximumRowCount = modeOptions.size
        }

        val braveToggle = createToolbarToggle("Brave Mode", null)
        val thinkMoreToggle = createToolbarToggle("Think More", null)

        val leftGroup = JBPanel<JBPanel<*>>(HorizontalLayout(12)).apply {
            isOpaque = false
            add(plusButton)
            add(modeSelector)
            add(braveToggle)
            add(thinkMoreToggle)
        }

        sendButton.preferredSize = JBUI.size(36, 36)
        sendButton.minimumSize = sendButton.preferredSize

        val sendWrapper = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(sendButton, BorderLayout.CENTER)
        }

        val actionsRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12, 12, 12)
            add(leftGroup, BorderLayout.WEST)
            add(sendWrapper, BorderLayout.EAST)
        }

        listOf<Component>(plusButton, modeSelector, braveToggle, thinkMoreToggle, sendButton).forEach {
            it.addFocusListener(focusListener)
        }

        return actionsRow
    }

    private fun createToolbarToggle(text: String, icon: Icon?): JToggleButton {
        return JToggleButton(text).apply {
            this.icon = icon
            horizontalAlignment = SwingConstants.LEFT
            iconTextGap = 6
            isContentAreaFilled = false
            isOpaque = false
            border = JBUI.Borders.empty(4, 8)
            putClientProperty("JButton.buttonType", "toolbar")
            isFocusPainted = false
        }
    }

    private fun createToolbarIconButton(icon: Icon, tooltip: String): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isContentAreaFilled = false
            isOpaque = false
            isFocusPainted = false
            border = JBUI.Borders.empty(4, 8)
            putClientProperty("JButton.buttonType", "toolbar")
        }
    }

    private fun showPromptActionsMenu(invoker: Component) {
        val menu = JPopupMenu().apply {
            add(createMenuItem("Project File/Image...") { handleProjectFileAction() })
            add(createMenuItem("Create project guidelines") { handleCreateGuidelines() })
            add(createMenuItem("Create AI ignore file") { handleCreateIgnoreFile() })
        }

        menu.show(invoker, 0, invoker.height)
    }

    private fun createMenuItem(text: String, onClick: () -> Unit): JMenuItem {
        return JMenuItem(text).apply {
            addActionListener { onClick() }
        }
    }

    private fun handleProjectFileAction() {
        Messages.showInfoMessage(
            project,
            "Project file and image uploads will be available soon.",
            "Coming Soon"
        )
    }

    private fun handleCreateGuidelines() {
        Messages.showInfoMessage(
            project,
            "Guideline creation is not implemented yet.",
            "Coming Soon"
        )
    }

    private fun handleCreateIgnoreFile() {
        Messages.showInfoMessage(
            project,
            "AI ignore file generation is not implemented yet.",
            "Coming Soon"
        )
    }

    private data class ModeOption(val label: String, val description: String)

    private inner class ModeOptionRenderer : ListCellRenderer<ModeOption> {
        override fun getListCellRendererComponent(
            list: JList<out ModeOption>,
            value: ModeOption?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val option = value ?: return JBLabel()
            if (index == -1) {
                return JBLabel(option.label)
            }

            val titleLabel = JBLabel(option.label)
            val descriptionLabel = JBLabel(option.description).apply {
                font = font.deriveFont(font.size2D - 1f)
                foreground = JBColor(0x8C8C8C, 0x9DA1A9)
            }

            return JBPanel<JBPanel<*>>(VerticalLayout(2)).apply {
                border = JBEmptyBorder(6, 8, 6, 8)
                isOpaque = true
                background = if (isSelected) list.selectionBackground else list.background

                titleLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
                if (isSelected) {
                    descriptionLabel.foreground = list.selectionForeground
                }

                add(titleLabel)
                add(descriptionLabel)
            }
        }
    }

    private fun configurePromptActions() {
        sendButton.isEnabled = false
        sendButton.addActionListener { sendFromPrompt() }

        promptEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                sendButton.isEnabled = promptEditor.text.isNotBlank()
            }
        })

        promptEditor.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
            "smith-send"
        )
        promptEditor.actionMap.put("smith-send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                sendFromPrompt()
            }
        })
    }

    private fun sendFromPrompt() {
        val text = promptEditor.text
        if (text.isBlank()) return

        promptEditor.text = ""
        sendButton.isEnabled = false

        viewModel?.sendUserMessage(text)
    }

    private fun render(state: SmithState) {
        connectionLabel.text = if (state.connected) "Connected" else "Offline"
        streamStatusLabel.text = if (state.streaming) "Streaming..." else "Idle"

        historyModel.apply {
            removeAllElements()
            state.history.forEachIndexed { index, message ->
                val prefix = "#${index + 1} ${message.role.name.lowercase().replaceFirstChar { it.uppercase() }}"
                val summary = StringUtil.shortenTextWithEllipsis(message.content, 60, 0)
                addElement("$prefix - $summary")
            }
        }

        chatModel.apply {
            removeAllElements()
            state.history.forEach { addElement(it) }
        }
        if (chatModel.size() > 0) {
            val lastIndex = chatModel.size() - 1
            chatList.ensureIndexIsVisible(lastIndex)
            historyList.ensureIndexIsVisible(lastIndex)
        }
    }

    private fun createSettingsButton(): JComponent {
        val button = JButton(AllIcons.General.Settings)
        button.toolTipText = "Open Smith settings"
        button.addActionListener {
            Messages.showInfoMessage(
                project,
                "Smith settings will live here. Until backend integration is ready, configure your endpoint manually.",
                "Smith Settings"
            )
        }
        return button
    }

    private fun createHistoryRenderer(): ListCellRenderer<in String> {
        return ListCellRenderer { _, value, index, isSelected, cellHasFocus ->
            val label = JBLabel(value ?: "").apply {
                border = JBEmptyBorder(6, 8, 6, 8)
            }
            label.isOpaque = true
            label.background = if (isSelected) historyList.selectionBackground else historyList.background
            label.foreground = if (isSelected) historyList.selectionForeground else historyList.foreground
            label
        }
    }

    override fun dispose() {
        stateJob?.cancel()
        stateJob = null
        uiScope.cancel("Smith tool window disposed")
    }

    private inner class SmithMessageRenderer : ListCellRenderer<SmithState.Message> {
        override fun getListCellRendererComponent(
            list: JList<out SmithState.Message>,
            value: SmithState.Message?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val safeValue = value ?: return JBLabel()
            val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = JBEmptyBorder(8, 8, 8, 8)
                isOpaque = true
                background = if (isSelected) list.selectionBackground else list.background
            }

            val roleLabel = JBLabel(
                safeValue.role.name.lowercase().replaceFirstChar { it.uppercase() }
            ).apply {
                border = JBEmptyBorder(0, 0, 4, 0)
            }

            val contentLabel = JBLabel(
                "<html>${StringUtil.escapeXmlEntities(safeValue.content).replace("\n", "<br>")}</html>"
            )

            panel.add(roleLabel, BorderLayout.NORTH)
            panel.add(contentLabel, BorderLayout.CENTER)

            return panel
        }
    }
}
