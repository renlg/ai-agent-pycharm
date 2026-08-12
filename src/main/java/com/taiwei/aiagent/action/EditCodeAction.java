package com.taiwei.aiagent.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.taiwei.aiagent.diff.DiffEntry;
import com.taiwei.aiagent.diff.DiffReviewService;
import com.taiwei.aiagent.llm.LlmClient;
import com.taiwei.aiagent.llm.LlmResponse;
import com.taiwei.aiagent.llm.openai.OpenAiLlmClient;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.settings.AiAgentSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 内联编辑动作（类似 Continue/Copilot 的 Cmd+I）：
 * 用户选中代码后输入修改指令，LLM 直接改写选中片段并原地替换，
 * 变更记录到 {@link DiffReviewService}，可通过编辑器通知条接受/回滚。
 * 与聊天面板解耦，不占用会话历史。
 */
public class EditCodeAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(EditCodeAction.class);

    private static final int MAX_CODE_CHARS = 20000;

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        e.getPresentation().setEnabledAndVisible(
                project != null && editor != null && editor.getSelectionModel().hasSelection());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }

        String selected = editor.getSelectionModel().getSelectedText();
        if (selected == null || selected.isBlank()) {
            Messages.showInfoMessage(project, "请先选中要修改的代码。", "太微 - 修改选中代码");
            return;
        }
        if (selected.length() > MAX_CODE_CHARS) {
            Messages.showWarningDialog(project, "选中代码过长（超过 " + MAX_CODE_CHARS + " 字符），请缩小选区后重试。", "太微 - 修改选中代码");
            return;
        }

        String instruction = Messages.showMultilineInputDialog(
                project,
                "描述要对选中代码做的修改（例如：改成 Stream 写法、补充空指针判断、抽取方法等）：",
                "太微 - 修改选中代码",
                null,
                Messages.getQuestionIcon(),
                null);
        if (instruction == null || instruction.isBlank()) {
            return;
        }

        Document document = editor.getDocument();
        // 用 RangeMarker 跟踪选区，避免 LLM 请求期间文档变化导致偏移失效
        RangeMarker marker = document.createRangeMarker(
                editor.getSelectionModel().getSelectionStart(),
                editor.getSelectionModel().getSelectionEnd());

        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        String filePath = file != null ? file.getPath() : null;
        String language = detectLanguage(file);

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "太微：正在生成修改...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                AiAgentSettings settings = AiAgentSettings.getInstance();
                LlmClient client = new OpenAiLlmClient(
                        settings.getBaseUrl(), settings.getApiKey(), settings.getModel());
                try {
                    List<ChatMessage> request = new ArrayList<>();
                    request.add(ChatMessage.system(
                            "你是一个代码修改助手。用户会给你一段代码和修改指令。"
                                    + "你只输出修改后的完整代码片段，用于原样替换用户选中的部分："
                                    + "不要输出任何解释、前后缀或 Markdown 代码块围栏，保持原有缩进风格。"));
                    request.add(ChatMessage.user(
                            "文件: " + (filePath != null ? filePath : "未知")
                                    + "\n\n选中代码:\n```" + language + "\n" + selected + "\n```"
                                    + "\n\n修改指令: " + instruction));

                    LlmResponse response = client.chat(request, null);
                    if (response == null || !response.isSuccess()
                            || response.getContent() == null || response.getContent().isBlank()) {
                        String err = response != null ? response.getErrorMessage() : "LLM 未返回内容";
                        notifyError(project, "生成修改失败: " + err);
                        return;
                    }

                    String newCode = stripCodeFence(response.getContent());
                    applyEdit(project, document, marker, newCode, filePath);
                } catch (Exception ex) {
                    LOG.warn("内联编辑失败", ex);
                    notifyError(project, "生成修改失败: " + ex.getMessage());
                } finally {
                    client.close();
                }
            }
        });
    }

    /**
     * 在 EDT 上替换选区并记录 diff（记录整个文件的新旧内容，供审查/回滚）
     */
    private void applyEdit(Project project, Document document, RangeMarker marker,
                           String newCode, @Nullable String filePath) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!marker.isValid()) {
                notifyError(project, "选区已失效（文档被修改），请重新选择后重试。");
                return;
            }
            String oldFileText = document.getText();
            WriteCommandAction.runWriteCommandAction(project, "太微修改选中代码", null, () -> {
                document.replaceString(marker.getStartOffset(), marker.getEndOffset(), newCode);
                FileDocumentManager.getInstance().saveDocument(document);
            });
            marker.dispose();

            if (filePath != null) {
                DiffEntry entry = new DiffEntry(filePath, oldFileText, document.getText());
                DiffReviewService.getInstance(project).addDiff(entry);
            }
        });
    }

    /**
     * 去除 LLM 输出中可能包裹的 Markdown 代码块围栏
     */
    static String stripCodeFence(String content) {
        String text = content.strip();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return text;
        }
        text = text.substring(firstNewline + 1);
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        return text.stripTrailing();
    }

    private static void notifyError(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() ->
                Messages.showErrorDialog(project, message, "太微 - 修改选中代码"));
    }

    private static String detectLanguage(@Nullable VirtualFile file) {
        if (file == null || file.getExtension() == null) {
            return "";
        }
        return file.getExtension().toLowerCase();
    }
}
