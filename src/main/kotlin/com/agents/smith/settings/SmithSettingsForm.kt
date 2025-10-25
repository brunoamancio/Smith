package com.agents.smith.settings

import com.agents.smith.state.SmithState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class SmithSettingsForm {

    val panel: JPanel by lazy { createRootPanel() }
    val isInitialized: Boolean
        get() = fieldInitialized

    private var fieldInitialized = false

    private val modelField = ComboBox(
        arrayOf(
            "gpt-5-codex",
            "gpt-5",
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-4o-mini"
        )
    ).apply {
        isEditable = true
    }
    private val streamCheckBox = JBCheckBox("Stream responses")
    private val maxTokensSpinner = JSpinner(SpinnerNumberModel(1024, 256, 32768, 256))
    private val readTimeoutSpinner = JSpinner(SpinnerNumberModel(SmithSettingsService.DEFAULT_READ_TIMEOUT_SECONDS, 30, 60 * 60, 30))
    private val endpointField = JBTextField()
    private val tokenField = JBPasswordField()
    private val allowFileSystemCheck = JBCheckBox("Allow file system access (ACP fs.*)")
    private val testConnectionButton = JButton("Test Connection")
    private val testResultLabel = JLabel(" ")

    private fun createRootPanel(): JPanel {
        val content = buildPanel()
        resetTestStatus()
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty()
            add(content, BorderLayout.NORTH)
        }.also { fieldInitialized = true }
    }

    private fun buildPanel(): JPanel {
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Default model:", modelField, 1, false)
            .addComponent(streamCheckBox)
            .addLabeledComponent("Max tokens:", maxTokensSpinner, 1, false)
            .addLabeledComponent("ACP read timeout (seconds):", readTimeoutSpinner, 1, false)
            .addSeparator()
            .addLabeledComponent("ACP endpoint URL:", endpointField, 1, false)
            .addLabeledComponent("ACP API token:", tokenField, 1, false)
            .addComponent(allowFileSystemCheck)
            .addComponent(testConnectionButton)
            .addComponent(testResultLabel.apply {
                border = JBUI.Borders.empty(2, 0, 0, 0)
                foreground = JBColor.GRAY
            })

        return builder.panel.apply {
            border = JBUI.Borders.empty(4, 0, 0, 0)
        }
    }

    fun isModified(settings: SmithState.Settings, storedToken: String): Boolean {
        val tokenText = tokenField.password.decode()
        return modelField.selectedItem?.toString().orEmpty() != settings.model ||
            streamCheckBox.isSelected != settings.stream ||
            (maxTokensSpinner.value as Int) != settings.maxTokens ||
            (readTimeoutSpinner.value as Int) != settings.acpReadTimeoutSeconds ||
            endpointField.text.trim() != settings.acpEndpoint ||
            tokenText != storedToken ||
            allowFileSystemCheck.isSelected != settings.acpCapabilities.allowFileSystem
    }

    fun reset(settings: SmithState.Settings, token: String) {
        modelField.selectedItem = settings.model
        streamCheckBox.isSelected = settings.stream
        maxTokensSpinner.value = settings.maxTokens
        readTimeoutSpinner.value = settings.acpReadTimeoutSeconds
        endpointField.text = settings.acpEndpoint
        tokenField.text = token
        allowFileSystemCheck.isSelected = settings.acpCapabilities.allowFileSystem
        resetTestStatus()
    }

    fun buildSettings(current: SmithState.Settings): Pair<SmithState.Settings, String?> {
        val (updatedSettings, rawToken) = snapshotSettings(current)
        val sanitizedToken = rawToken.takeIf { it.isNotBlank() }
        tokenField.text = sanitizedToken.orEmpty()
        return updatedSettings to sanitizedToken
    }

    fun snapshotSettings(current: SmithState.Settings): Pair<SmithState.Settings, String> {
        val tokenText = tokenField.password.decode()
        val updatedSettings = current.copy(
            model = modelField.selectedItem?.toString().orEmpty().ifBlank { current.model },
            stream = streamCheckBox.isSelected,
            maxTokens = maxTokensSpinner.value as Int,
            acpEndpoint = endpointField.text.trim(),
            acpReadTimeoutSeconds = readTimeoutSpinner.value as Int,
            acpTokenAlias = if (tokenText.isNotBlank()) current.acpTokenAlias ?: SmithSettingsService.DEFAULT_TOKEN_ALIAS else null,
            acpCapabilities = current.acpCapabilities.copy(
                allowFileSystem = allowFileSystemCheck.isSelected
            )
        )
        return updatedSettings to tokenText
    }

    fun onTestConnectionRequested(handler: () -> Unit) {
        testConnectionButton.actionListeners.forEach { testConnectionButton.removeActionListener(it) }
        testConnectionButton.addActionListener { handler() }
    }

    fun showTestInProgress() {
        testConnectionButton.isEnabled = false
        testResultLabel.foreground = JBColor.GRAY
        testResultLabel.text = "Testing connection..."
    }

    fun showTestResult(success: Boolean, message: String) {
        testConnectionButton.isEnabled = true
        testResultLabel.foreground = if (success) JBColor(0x4CAF50, 0x4CAF50) else JBColor(0xF44336, 0xF44336)
        testResultLabel.text = message
    }

    fun resetTestStatus() {
        testConnectionButton.isEnabled = true
        testResultLabel.foreground = JBColor.GRAY
        testResultLabel.text = " "
    }

    private fun CharArray?.decode(): String = this?.let { String(it) } ?: ""

}
