package com.taiwei.aiagent.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.taiwei.aiagent.ui.EditorChatBridge;
import org.jetbrains.annotations.NotNull;

/**
 * 编辑器右键 AI 动作基类：取当前选中代码（未选中时取整个文件，带大小上限），
 * 构造提示词后激活"太微"工具窗口并投递到聊天面板。
 */
public abstract class BaseEditorChatAction extends AnAction {

    /** plugin.xml 中注册的工具窗口 id */
    private static final String TOOL_WINDOW_ID = "太微";

    private static final int MAX_CODE_CHARS = 20000;

    /**
     * 构造要发送给聊天面板的提示词
     *
     * @param filePath 文件相对项目根目录的路径（无法确定时为文件名）
     * @param language 依据文件扩展名推断的语言标识（用于 Markdown 代码块）
     * @param code     选中的代码（或整个文件内容，超长时已截断）
     */
    protected abstract String buildPrompt(String filePath, String language, String code);

    /** 为 true 时把提示词放入输入框而不直接发送 */
    protected boolean insertOnly() {
        return false;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        e.getPresentation().setEnabledAndVisible(project != null && editor != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }

        String code = editor.getSelectionModel().getSelectedText();
        if (code == null || code.isBlank()) {
            code = editor.getDocument().getText();
        }
        if (code.length() > MAX_CODE_CHARS) {
            code = code.substring(0, MAX_CODE_CHARS) + "\n// ...（代码过长，已截断）";
        }

        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        String filePath = relativePath(project, file);
        String language = detectLanguage(file);

        String prompt = buildPrompt(filePath, language, code);

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        Runnable deliver = () -> {
            EditorChatBridge bridge = EditorChatBridge.getInstance(project);
            if (insertOnly()) {
                bridge.insertToInput(prompt);
            } else {
                bridge.sendPrompt(prompt);
            }
        };
        if (toolWindow != null) {
            // activate 保证 ChatPanel 已创建并注册到 bridge，之后再投递
            toolWindow.activate(deliver, true);
        } else {
            deliver.run();
        }
    }

    private static String relativePath(Project project, VirtualFile file) {
        if (file == null) {
            return "当前文件";
        }
        String basePath = project.getBasePath();
        String path = file.getPath();
        if (basePath != null && path.startsWith(basePath)) {
            String rel = path.substring(basePath.length());
            return rel.startsWith("/") ? rel.substring(1) : rel;
        }
        return file.getName();
    }

    private static String detectLanguage(VirtualFile file) {
        if (file == null || file.getExtension() == null) {
            return "";
        }
        String ext = file.getExtension().toLowerCase();
        return switch (ext) {
            case "java" -> "java";
            case "kt", "kts" -> "kotlin";
            case "py" -> "python";
            case "js", "mjs" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "go" -> "go";
            case "rs" -> "rust";
            case "c", "h" -> "c";
            case "cpp", "cc", "hpp" -> "cpp";
            case "cs" -> "csharp";
            case "rb" -> "ruby";
            case "php" -> "php";
            case "swift" -> "swift";
            case "sql" -> "sql";
            case "sh", "zsh", "bash" -> "bash";
            case "xml" -> "xml";
            case "json" -> "json";
            case "yaml", "yml" -> "yaml";
            case "html" -> "html";
            case "css" -> "css";
            case "md" -> "markdown";
            case "gradle" -> "groovy";
            default -> ext;
        };
    }
}
