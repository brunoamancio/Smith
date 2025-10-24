package com.agents.smith.toolwindow

import com.agents.smith.state.SmithState
import com.agents.smith.viewmodel.SmithViewModel
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
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
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

class SmithToolWindowPanel(private val project: Project) :
    JBPanel<SmithToolWindowPanel>(BorderLayout()),
    Disposable {

    private val promptBackground = JBColor(0x1F2023, 0x1F2023)
    private val userBubbleColor = JBColor(0x26292F, 0x26292F)
    private val agentBubbleColor = JBColor(0x21252B, 0x21252B)
    private val columnHeaderColor = JBColor(0x8C919E, 0x8C919E)
    private val idleBorderColor = JBColor(0x3A3F4B, 0x3A3F4B)

    private val historyModel = DefaultListModel<String>()
    private val historyList = JBList(historyModel)
    private val chatModel = DefaultListModel<SmithState.Message>()
    private val chatList = JBList(chatModel)

    private val conversationListModel = DefaultListModel<ConversationSummary>()
    private val conversationList = JBList(conversationListModel)

    private val backButton = JButton(AllIcons.Actions.Back).apply {
        isVisible = false
        isContentAreaFilled = false
        isFocusPainted = false
        border = JBUI.Borders.empty()
        margin = JBUI.insets(0)
        val buttonSize = JBUI.size(20, 20)
        preferredSize = buttonSize
        minimumSize = buttonSize
        maximumSize = buttonSize
        putClientProperty("JButton.buttonType", "toolbar")
        putClientProperty("JComponent.sizeVariant", "small")
        toolTipText = "Back to tasks"
        addActionListener { showMainView() }
    }

    private val conversationTitleLabel = JBLabel("Task overview").apply {
        foreground = JBColor(0x5DF188, 0x5DF188)
        font = font.deriveFont(Font.BOLD, font.size2D + 1f)
        border = JBEmptyBorder(0, JBUI.scale(4), 0, 0)
    }

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

    private var showingConversation = false
    private var latestState: SmithState? = null
    private val centerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
    }

    init {
        border = JBEmptyBorder(12)
        layout = BorderLayout(12, 0)
        isOpaque = true
        background = promptBackground

        configureLists()
        add(buildHeader(), BorderLayout.NORTH)
        add(centerPanel, BorderLayout.CENTER)
        showMainView()
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

    fun asContent(): Content {
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(this, "", false)
        content.setDisposer(this)
        return content
    }

    private fun buildHeader(): JComponent {
        val titlePanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(backButton, BorderLayout.WEST)
            add(conversationTitleLabel, BorderLayout.CENTER)
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBEmptyBorder(0, 0, 6, 0)
            add(titlePanel, BorderLayout.WEST)
            add(createSettingsButton(), BorderLayout.EAST)
        }
    }

    private fun buildTableLayout(conversationContent: JComponent): JComponent {
        val table = JBPanel<JBPanel<*>>(GridBagLayout()).apply {
            isOpaque = true
            background = promptBackground
        }

        val conversationCell = createTableCell(
            conversationContent,
            top = true,
            left = true,
            bottom = false,
            right = false,
            padding = JBUI.insets(4, 8, 6, 4)
        )

        val tasksCell = createTableCell(
            buildTasksPane(),
            top = true,
            left = false,
            bottom = false,
            right = true,
            padding = JBUI.insets(4, 4, 6, 8)
        )
        tasksCell.preferredSize = JBUI.size(260, 0)

        val promptCell = createTableCell(
            buildPromptEditor(),
            top = true,
            left = true,
            bottom = true,
            right = true,
            padding = JBUI.insets(8, 8, 8, 8)
        )

        table.add(
            conversationCell,
            GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                weightx = 0.65
                weighty = 1.0
                fill = GridBagConstraints.BOTH
            }
        )

        table.add(
            tasksCell,
            GridBagConstraints().apply {
                gridx = 1
                gridy = 0
                weightx = 0.35
                weighty = 1.0
                fill = GridBagConstraints.BOTH
            }
        )

        table.add(
            promptCell,
            GridBagConstraints().apply {
                gridx = 0
                gridy = 1
                gridwidth = 2
                weightx = 1.0
                weighty = 0.0
                fill = GridBagConstraints.BOTH
            }
        )

        return table
    }

    private fun buildMainTable(): JComponent {
        return buildTableLayout(buildMainPlaceholderPane())
    }

    private fun buildConversationTable(): JComponent {
        return buildTableLayout(buildConversationPane())
    }

    private fun configureLists() {
        historyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        historyList.cellRenderer = createHistoryRenderer()
        historyList.background = promptBackground

        chatList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        chatList.cellRenderer = SmithMessageRenderer()
        chatList.visibleRowCount = 10
        chatList.background = promptBackground
        chatList.selectionBackground = JBColor(0x2F3136, 0x2F3136)

        conversationList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        conversationList.background = promptBackground
        configureConversationListNavigation()
    }

    private fun configureConversationListNavigation() {
        conversationList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && conversationList.selectedIndex >= 0) {
                    showConversationView()
                }
            }
        })

        conversationList.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "smith-open-conversation")
        conversationList.actionMap.put("smith-open-conversation", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (conversationList.selectedIndex >= 0) {
                    showConversationView()
                }
            }
        })
    }

    private fun buildMainPlaceholderPane(): JComponent {
        val heading = JBLabel("Task overview").apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
            border = JBEmptyBorder(12, 8, 4, 8)
        }

        val description = JBLabel(
            "<html>Select a recent task to review the conversation.<br>" +
                "Use the prompt below to start something new.</html>"
        ).apply {
            border = JBEmptyBorder(0, 8, 12, 8)
            foreground = JBColor(0x8C919E, 0x8C919E)
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(JBPanel<JBPanel<*>>(VerticalLayout(4)).apply {
                isOpaque = false
                add(heading)
                add(description)
            }, BorderLayout.NORTH)
        }
    }

    private fun buildConversationPane(): JComponent {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(JBScrollPane(chatList).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
                viewport.background = promptBackground
            }, BorderLayout.CENTER)
        }
    }

    private fun buildTasksPane(): JComponent {
        val header = JBLabel("Recent tasks").apply {
            foreground = columnHeaderColor
            border = JBEmptyBorder(0, 0, 4, 0)
        }

        val scroll = JBScrollPane(conversationList).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            viewport.background = promptBackground
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
    }

    private fun buildPromptEditor(): JComponent {
        promptEditor.lineWrap = true
        promptEditor.wrapStyleWord = true
        promptEditor.toolTipText = "Describe what you need help with. Press Ctrl+Enter to send."
        promptEditor.emptyText.text = "Type your task here, press Ctrl+Enter"
        promptEditor.margin = JBUI.insets(0)
        promptEditor.border = JBUI.Borders.empty()
        promptEditor.background = promptBackground

        val scrollPane = JBScrollPane(promptEditor).apply {
            border = null
            isOpaque = false
            viewport.isOpaque = false
            viewport.background = promptBackground
            viewportBorder = JBUI.Borders.empty(12, 12, 12, 12)
        }

        val actionsRow = createPromptActionsRow()

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(scrollPane, BorderLayout.CENTER)
            add(actionsRow, BorderLayout.SOUTH)
        }
    }

    private fun renderTable(component: JComponent) {
        centerPanel.removeAll()
        centerPanel.add(component, BorderLayout.CENTER)
        centerPanel.revalidate()
        centerPanel.repaint()
    }

    private fun showMainView() {
        val wasConversation = showingConversation
        showingConversation = false
        backButton.isVisible = false
        conversationTitleLabel.text = "Task overview"
        if (wasConversation || centerPanel.componentCount == 0) {
            renderTable(buildMainTable())
        }
    }

    private fun showConversationView() {
        val needsRender = !showingConversation
        showingConversation = true
        backButton.isVisible = true
        conversationTitleLabel.text = latestState?.let { deriveConversationTitle(it) } ?: "Conversation"
        if (needsRender) {
            renderTable(buildConversationTable())
        }
    }

    private fun createTableCell(
        content: JComponent,
        top: Boolean,
        left: Boolean,
        bottom: Boolean,
        right: Boolean,
        padding: Insets
    ): JComponent {
        val inner = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(padding.top, padding.left, padding.bottom, padding.right)
            add(content, BorderLayout.CENTER)
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = true
            background = promptBackground
            border = JBUI.Borders.customLine(
                idleBorderColor,
                if (top) 1 else 0,
                if (left) 1 else 0,
                if (bottom) 1 else 0,
                if (right) 1 else 0
            )
            add(inner, BorderLayout.CENTER)
        }
    }

    private fun createPromptActionsRow(): JComponent {
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

        val braveToggle = createToolbarToggle("Brave Mode", IconLoader.getIcon("icons/terminal.svg", javaClass))
        val thinkMoreToggle = createToolbarToggle("Think More", IconLoader.getIcon("icons/cloud.svg", javaClass))

        val leftGroup = JBPanel<JBPanel<*>>(HorizontalLayout(JBUI.scale(4))).apply {
            isOpaque = false
            add(plusButton)
            add(modeSelector)
            add(braveToggle)
            add(thinkMoreToggle)
        }

        val leftWrapper = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(leftGroup, BorderLayout.SOUTH)
        }

        val rightWrapper = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            add(sendButton, BorderLayout.SOUTH)
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 8, 0, 8)
            add(leftWrapper, BorderLayout.WEST)
            add(rightWrapper, BorderLayout.EAST)
        }
    }

    private fun createToolbarToggle(text: String, icon: Icon?): JToggleButton {
        return JToggleButton(text).apply {
            this.icon = icon
            horizontalAlignment = SwingConstants.LEFT
            iconTextGap = 6
            isContentAreaFilled = false
            isOpaque = false
            border = JBUI.Borders.empty(4, 4, 4, 4)
            putClientProperty("JButton.buttonType", "toolbar")
            putClientProperty("JComponent.sizeVariant", "small")
            isFocusPainted = false
        }
    }

    private fun createToolbarIconButton(icon: Icon, tooltip: String): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isContentAreaFilled = false
            isOpaque = false
            isFocusPainted = false
            border = JBUI.Borders.empty()
            preferredSize = JBUI.size(28, 28)
            minimumSize = preferredSize
            putClientProperty("JButton.buttonType", "toolbar")
            putClientProperty("JComponent.sizeVariant", "small")
        }
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
        showConversationView()
    }

    private fun render(state: SmithState) {
        latestState = state

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
        }

        updateConversationSummaries(state)

        if (showingConversation) {
            showConversationView()
        }
    }

    private fun updateConversationSummaries(state: SmithState) {
        val previewSource = state.history.lastOrNull { it.role != SmithState.Role.SYSTEM }?.content
        val preview = previewSource?.let { StringUtil.shortenTextWithEllipsis(it, 48, 0) } ?: ""
        val summary = ConversationSummary(
            id = state.sessionId,
            title = deriveConversationTitle(state),
            status = preview
        )

        val selectionIndex = conversationList.selectedIndex
        conversationListModel.removeAllElements()
        conversationListModel.addElement(summary)
        if (selectionIndex >= 0) {
            conversationList.selectedIndex = selectionIndex.coerceAtMost(conversationListModel.size() - 1)
        } else if (conversationListModel.size() > 0) {
            conversationList.selectedIndex = 0
        }
    }

    private fun deriveConversationTitle(state: SmithState): String {
        val firstUserMessage = state.history.firstOrNull { it.role == SmithState.Role.USER }?.content
        return firstUserMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { StringUtil.shortenTextWithEllipsis(it, 40, 0) }
            ?: "Untitled task"
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
        return ListCellRenderer { _, value, _, isSelected, _ ->
            JBLabel(value ?: "").apply {
                border = JBEmptyBorder(6, 8, 6, 8)
                isOpaque = true
                background = if (isSelected) historyList.selectionBackground else historyList.background
                foreground = if (isSelected) historyList.selectionForeground else historyList.foreground
            }
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

    private data class ConversationSummary(
        val id: String,
        val title: String,
        val status: String
    )
}
