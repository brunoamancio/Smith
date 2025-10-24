package com.agents.smith.settings

import com.agents.smith.state.SmithState
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import java.awt.BorderLayout

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
    private val endpointField = JBTextField()
    private val tokenField = JBPasswordField()
    private val allowFileSystemCheck = JBCheckBox("Allow file system access (ACP fs.*)")

    private fun createRootPanel(): JPanel {
        val content = buildPanel()
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
            .addSeparator()
            .addLabeledComponent("ACP endpoint URL:", endpointField, 1, false)
            .addLabeledComponent("ACP API token:", tokenField, 1, false)
            .addComponent(allowFileSystemCheck)

        return builder.panel.apply {
            border = JBUI.Borders.empty(4, 0, 0, 0)
        }
    }

    fun isModified(settings: SmithState.Settings, storedToken: String): Boolean {
        val tokenText = tokenField.password.decode()
        return modelField.selectedItem?.toString().orEmpty() != settings.model ||
            streamCheckBox.isSelected != settings.stream ||
            (maxTokensSpinner.value as Int) != settings.maxTokens ||
            endpointField.text.trim() != settings.acpEndpoint ||
            tokenText != storedToken ||
            allowFileSystemCheck.isSelected != settings.acpCapabilities.allowFileSystem
    }

    fun reset(settings: SmithState.Settings, token: String) {
        modelField.selectedItem = settings.model
        streamCheckBox.isSelected = settings.stream
        maxTokensSpinner.value = settings.maxTokens
        endpointField.text = settings.acpEndpoint
        tokenField.text = token
        allowFileSystemCheck.isSelected = settings.acpCapabilities.allowFileSystem
    }

    fun buildSettings(current: SmithState.Settings): Pair<SmithState.Settings, String?> {
        val updatedSettings = current.copy(
            model = modelField.selectedItem?.toString().orEmpty().ifBlank { current.model },
            stream = streamCheckBox.isSelected,
            maxTokens = maxTokensSpinner.value as Int,
            acpEndpoint = endpointField.text.trim(),
            acpTokenAlias = if (tokenField.password.isNotEmpty()) current.acpTokenAlias ?: SmithSettingsService.DEFAULT_TOKEN_ALIAS else null,
            acpCapabilities = current.acpCapabilities.copy(
                allowFileSystem = allowFileSystemCheck.isSelected
            )
        )
        val tokenText = tokenField.password.decode()
        val sanitizedToken = tokenText.takeIf { it.isNotBlank() }
        tokenField.text = sanitizedToken.orEmpty()
        return updatedSettings to sanitizedToken
    }

    private fun CharArray?.decode(): String = this?.let { String(it) } ?: ""

}
