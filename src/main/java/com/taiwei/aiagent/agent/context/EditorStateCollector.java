package com.taiwei.aiagent.agent.context;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;

/**
 * 收集当前编辑器状态（活跃文件、光标位置、选中文本），
 * 作为结构化上下文自动附加到用户消息，让模型理解"这个文件/这段代码/这里"等指代。
 */
public final class EditorStateCollector {

    /** 选中文本注入上限，避免大段选区吃掉过多 token */
    private static final int MAX_SELECTION_CHARS = 4000;

    private EditorStateCollector() {
    }

    /**
     * 收集编辑器状态并格式化为可附加到用户消息的上下文块。
     *
     * @return 无打开的编辑器时返回空字符串
     */
    public static String collect(Project project) {
        if (project == null || project.isDisposed()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        try {
            ApplicationManager.getApplication().invokeAndWait(() -> {
                Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (editor == null) {
                    return;
                }
                VirtualFile vFile = FileDocumentManager.getInstance().getFile(editor.getDocument());
                if (vFile == null) {
                    return;
                }

                sb.append("<editor_state>\n");
                sb.append("活跃文件: ").append(relativePath(project, vFile));

                LogicalPosition caret = editor.getCaretModel().getLogicalPosition();
                sb.append("（光标位于第 ").append(caret.line + 1).append(" 行）\n");

                SelectionModel selection = editor.getSelectionModel();
                String selectedText = selection.getSelectedText();
                if (selectedText != null && !selectedText.isEmpty()) {
                    int startLine = editor.getDocument().getLineNumber(selection.getSelectionStart()) + 1;
                    int endLine = editor.getDocument().getLineNumber(
                            Math.max(selection.getSelectionStart(), selection.getSelectionEnd() - 1)) + 1;
                    if (selectedText.length() > MAX_SELECTION_CHARS) {
                        selectedText = selectedText.substring(0, MAX_SELECTION_CHARS)
                                + "\n... [选中内容过长，已截断]";
                    }
                    sb.append("选中文本（第 ").append(startLine).append("~").append(endLine).append(" 行）:\n");
                    sb.append("```\n").append(selectedText);
                    if (!selectedText.endsWith("\n")) {
                        sb.append("\n");
                    }
                    sb.append("```\n");
                }
                sb.append("</editor_state>\n");
            });
        } catch (Exception e) {
            return "";
        }
        return sb.toString();
    }

    private static String relativePath(Project project, VirtualFile vFile) {
        try {
            VirtualFile baseDir = project.getBaseDir();
            if (baseDir != null) {
                String rel = VfsUtilCore.getRelativePath(vFile, baseDir);
                if (rel != null) {
                    return rel;
                }
            }
        } catch (Exception ignored) {
        }
        return vFile.getPath();
    }
}
