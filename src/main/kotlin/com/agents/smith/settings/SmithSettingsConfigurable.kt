package com.agents.smith.settings

import com.agents.smith.state.SmithState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.JComponent

class SmithSettingsConfigurable(private val project: Project) : Configurable {

    private val settingsService = SmithSettingsService.getInstance(project)
    private val form = SmithSettingsForm()

    override fun getDisplayName(): String = "Smith"

    override fun createComponent(): JComponent {
        if (!form.isInitialized) {
            reset()
        }
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
    }

    override fun reset() {
        val state = settingsService.toSmithSettings()
        val token = settingsService.loadToken()
        form.reset(state, token)
    }
}
