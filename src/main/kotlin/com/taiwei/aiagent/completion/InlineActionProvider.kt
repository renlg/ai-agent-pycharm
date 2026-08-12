package com.taiwei.aiagent.completion

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.taiwei.aiagent.settings.AiAgentSettings
import java.util.concurrent.ConcurrentHashMap

class InlineActionProvider : EditorFactoryListener {

    companion object {
        private val toolbars = ConcurrentHashMap<Editor, InlineActionToolbar>()
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        editor.selectionModel.addSelectionListener(SelectionHandler(editor))
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        hideToolbar(event.editor)
    }

    private inner class SelectionHandler(private val editor: Editor) : SelectionListener {
        override fun selectionChanged(e: SelectionEvent) {
            val project = editor.project ?: return
            if (!AiAgentSettings.getInstance().isInlineActionEnabled) {
                hideToolbar(editor)
                return
            }

            val selection = editor.selectionModel.selectedText ?: ""
            if (selection.length > 3) {
                showToolbar(editor, project, selection)
            } else {
                hideToolbar(editor)
            }
        }
    }

    private fun showToolbar(editor: Editor, project: Project, selectedText: String) {
        val existing = toolbars[editor]
        if (existing != null) {
            existing.updateSelection(selectedText)
            return
        }

        val virtualFile = editor.virtualFile ?: return
        val filePath = getRelativePath(project, virtualFile)
        val language = detectLanguage(virtualFile)

        val toolbar = InlineActionToolbar(editor, project, selectedText, filePath, language)
        toolbars[editor] = toolbar
        toolbar.show()
    }

    private fun hideToolbar(editor: Editor) {
        val toolbar = toolbars.remove(editor)
        toolbar?.hide()
    }

    private fun getRelativePath(project: Project, file: VirtualFile): String {
        val basePath = project.basePath ?: return file.name
        val path = file.path
        return if (path.startsWith(basePath)) {
            val rel = path.substring(basePath.length)
            if (rel.startsWith("/")) rel.substring(1) else rel
        } else {
            file.name
        }
    }

    private fun detectLanguage(file: VirtualFile): String {
        val ext = file.extension?.lowercase() ?: return ""
        return when (ext) {
            "java" -> "java"
            "kt", "kts" -> "kotlin"
            "py" -> "python"
            "js", "mjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "go" -> "go"
            "rs" -> "rust"
            "c", "h" -> "c"
            "cpp", "cc", "hpp" -> "cpp"
            "cs" -> "csharp"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "sql" -> "sql"
            "sh", "zsh", "bash" -> "bash"
            "xml" -> "xml"
            "json" -> "json"
            "yaml", "yml" -> "yaml"
            "html" -> "html"
            "css" -> "css"
            "md" -> "markdown"
            "gradle" -> "groovy"
            else -> ext
        }
    }
}
