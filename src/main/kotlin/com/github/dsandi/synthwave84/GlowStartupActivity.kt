package com.github.dsandi.synthwave84

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class GlowStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Apply to all editors already open when this project loads
        GlowEditorListener.applyToAllOpenEditors()

        // Register for future editor opens using the newer API
        EditorFactory.getInstance().addEditorFactoryListener(
            GlowEditorListener(),
            project
        )
    }
}
