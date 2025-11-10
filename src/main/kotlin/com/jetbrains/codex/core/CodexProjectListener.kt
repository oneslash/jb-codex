package com.jetbrains.codex.core

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

class CodexProjectListener : ProjectManagerListener {
    override fun projectClosing(project: Project) {
        project.getService(CodexService::class.java)?.stop()
    }
}











