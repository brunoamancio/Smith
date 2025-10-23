package com.agents.smith.toolwindow

import com.agents.smith.state.SmithState
import com.agents.smith.viewmodel.SmithViewModel
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.CollectionListModel
import com.intellij.util.ui.JBEmptyBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

class SmithToolWindowPanel(private val project: Project) :
    JBPanel<SmithToolWindowPanel>(BorderLayout()),
    Disposable {

    private val connectionLabel = JBLabel("Offline")
    private val streamStatusLabel = JBLabel("Idle")
    private val historyModel = CollectionListModel<String>()
    private val historyList = JBList(historyModel)
    private val chatModel = CollectionListModel<SmithState.Message>()
    private val chatList = JBList(chatModel)
    private val promptEditor = JBTextArea(4, 0)
    private val sendButton = JBButton("Send")
    private val insertButton = JBButton("Insert").apply {
        isEnabled = false
        toolTipText = "Insert is disabled until Smith connects to a backend."
    }
    private val explainButton = JBButton("Explain").apply {
        isEnabled = false
        toolTipText = "Explain is disabled until Smith connects to a backend."
    }
    private val patchButton = JBButton("Apply Patch").apply {
        isEnabled = false
        toolTipText = "Patch preview is disabled until Smith connects to a backend."
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
        historyList.emptyText.text = "No messages yet."
        historyList.cellRenderer = createHistoryRenderer()

        chatList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        chatList.emptyText.text = "Start a conversation below."
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
        promptEditor.emptyText.text = "Ask Smith to explain, generate, or edit code..."
        promptEditor.margin = JBEmptyBorder(8)

        val promptContainer = JBPanel<JBPanel<*>>(BorderLayout(8, 0)).apply {
            add(JBScrollPane(promptEditor), BorderLayout.CENTER)
            add(buildPromptButtons(), BorderLayout.EAST)
        }

        return promptContainer
    }

    private fun buildPromptButtons(): JComponent {
        return JBPanel<JBPanel<*>>(VerticalLayout(8)).apply {
            add(sendButton)
            add(insertButton)
            add(explainButton)
            add(patchButton)
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

        historyModel.replaceAll(
            state.history.mapIndexed { index, message ->
                val prefix = "#${index + 1} ${message.role.name.lowercase().replaceFirstChar { it.uppercase() }}"
                val summary = StringUtil.shortenTextWithEllipsis(message.content, 60, 0)
                "$prefix - $summary"
            }
        )

        chatModel.replaceAll(state.history)
        if (chatModel.size > 0) {
            val lastIndex = chatModel.size - 1
            chatList.ensureIndexIsVisible(lastIndex)
            historyList.ensureIndexIsVisible(lastIndex)
        }
    }

    private fun createSettingsButton(): JComponent {
        val button = JBButton(AllIcons.General.Settings)
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
