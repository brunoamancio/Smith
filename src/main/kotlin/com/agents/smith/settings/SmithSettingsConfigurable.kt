package com.agents.smith.settings

import com.agents.smith.viewmodel.SmithViewModel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.JComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SmithSettingsConfigurable(private val project: Project) : Configurable {

    private val settingsService = SmithSettingsService.getInstance(project)
    private val viewModel = SmithViewModel()
    private val form = SmithSettingsForm()
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getDisplayName(): String = "Smith"

    override fun createComponent(): JComponent {
        if (!form.isInitialized) {
            reset()
        }
        form.onTestConnectionRequested { runTestConnection() }
        return form.panel
    }

    override fun isModified(): Boolean {
        val state = settingsService.toSmithSettings()
        val token = settingsService.loadToken()
        return form.isModified(state, token)
    }

    override fun apply() {
        val current = settingsService.toSmithSettings()
        val (updatedSettings, token) = form.buildSettings(current)
        settingsService.save(updatedSettings, token)
        form.resetTestStatus()
    }

    override fun reset() {
        val state = settingsService.toSmithSettings()
        val token = settingsService.loadToken()
        form.reset(state, token)
    }

    override fun disposeUIResources() {
        scope.cancel()
        viewModel.dispose()
    }

    private fun runTestConnection() {
        val baseSettings = settingsService.toSmithSettings()
        val (snapshot, token) = form.snapshotSettings(baseSettings)
        if (snapshot.acpEndpoint.isBlank()) {
            form.showTestResult(false, "Enter an ACP endpoint URL.")
            return
        }

        form.showTestInProgress()
        scope.launch {
            val result = viewModel.testConnection(snapshot, token)
            ApplicationManager.getApplication().invokeLater {
                form.showTestResult(result.success, result.message)
            }
        }
    }
}
