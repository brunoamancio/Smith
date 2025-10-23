package com.agents.smith.toolwindow

import com.agents.smith.viewmodel.SmithViewModel
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class SmithToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SmithToolWindowPanel(project)
        val viewModel = SmithViewModel()
        panel.bind(viewModel)

        val content = panel.asContent()
        toolWindow.contentManager.addContent(content)
    }
}
