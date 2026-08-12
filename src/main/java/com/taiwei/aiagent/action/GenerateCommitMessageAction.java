package com.taiwei.aiagent.action;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.CommitMessageI;
import com.intellij.openapi.vcs.VcsDataKeys;
import com.intellij.openapi.vcs.ui.Refreshable;
import com.taiwei.aiagent.llm.LlmClient;
import com.taiwei.aiagent.llm.LlmStreamListener;
import com.taiwei.aiagent.llm.openai.OpenAiLlmClient;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.settings.AiAgentSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * AI 生成提交信息动作：
 * 出现在提交面板的提交信息工具栏中，读取 git 暂存区 diff，
 * 流式生成提交信息并实时显示在提交框中。
 * 点击后显示底部状态栏进度条，生成期间禁止重复点击，生成前先清空当前内容。
 */
public class GenerateCommitMessageAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(GenerateCommitMessageAction.class);

    private static final int MAX_DIFF_CHARS = 16000;
    private static final int GIT_TIMEOUT_SECONDS = 10;

    /** 防止重复点击的标志 */
    private static volatile boolean generating = false;

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        boolean visible = e.getProject() != null
                && settings.isGitCommitReviewEnabled()
                && getCommitPanel(e) != null;
        e.getPresentation().setVisible(visible);
        if (visible) {
            // 生成中保持可见但禁用，并改变文字提示
            e.getPresentation().setEnabled(!generating);
            e.getPresentation().setText(generating ? "太微：正在生成..." : "太微：生成提交信息");
        }
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (generating) return;

        Project project = e.getProject();
        CommitMessageI commitPanel = getCommitPanel(e);
        if (project == null || commitPanel == null) {
            return;
        }
        String basePath = project.getBasePath();
        if (basePath == null) {
            return;
        }

        // 标记正在生成，禁止重复点击
        generating = true;

        // 立即更新按钮状态（在 EDT 上直接修改 presentation）
        e.getPresentation().setEnabled(false);
        e.getPresentation().setText("太微：正在生成...");

        // 先清空当前提交框内容
        ApplicationManager.getApplication().invokeLater(() ->
                commitPanel.setCommitMessage(""));

        // 使用 Backgroundable 在底部状态栏显示进度条
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "太微：正在生成提交信息...", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("太微：正在获取代码变更...");

                String diff = runGit(basePath, "diff", "--cached");
                if (diff == null || diff.isBlank()) {
                    diff = runGit(basePath, "diff");
                }
                String status = runGit(basePath, "status", "--porcelain");

                if (diff == null || diff.isBlank()) {
                    resetButtonState(e);
                    notifyError(project, "没有检测到任何变更（git diff 为空），无法生成提交信息。");
                    return;
                }
                if (diff != null && diff.length() > MAX_DIFF_CHARS) {
                    diff = diff.substring(0, MAX_DIFF_CHARS) + "\n...（diff 过长，已截断）";
                }

                indicator.setText("太微：AI 正在生成提交信息...");

                AiAgentSettings settings = AiAgentSettings.getInstance();
                LlmClient client;
                try {
                    client = new OpenAiLlmClient(
                            settings.getBaseUrl(), settings.getApiKey(), settings.getModel());
                } catch (Exception ex) {
                    LOG.warn("创建 LLM 客户端失败", ex);
                    resetButtonState(e);
                    notifyError(project, "创建 LLM 客户端失败: " + ex.getMessage());
                    return;
                }
                try {
                    String userPrompt = buildPrompt(status != null ? status : "", diff != null ? diff : "");
                    List<ChatMessage> request = new ArrayList<>();
                    request.add(ChatMessage.user(userPrompt));

                    StringBuilder accumulated = new StringBuilder();
                    CountDownLatch latch = new CountDownLatch(1);
                    AtomicBoolean hasError = new AtomicBoolean(false);
                    AtomicReference<String> errorMsg = new AtomicReference<>();

                    client.chatStream(request, null, new LlmStreamListener() {
                        @Override
                        public void onContent(String delta) {
                            accumulated.append(delta);
                            String current = EditCodeAction.stripCodeFence(accumulated.toString());
                            ApplicationManager.getApplication().invokeLater(() ->
                                    commitPanel.setCommitMessage(current));
                        }

                        @Override
                        public void onToolCall(String toolCallId, String functionName, String arguments) {
                        }

                        @Override
                        public void onComplete() {
                            latch.countDown();
                        }

                        @Override
                        public void onError(String error, Throwable throwable) {
                            hasError.set(true);
                            errorMsg.set(error);
                            latch.countDown();
                        }
                    });

                    // 等待流式响应完成，同时响应取消操作
                    while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                        if (indicator.isCanceled()) {
                            client.cancel();
                            return;
                        }
                    }

                    if (hasError.get()) {
                        notifyError(project, "生成提交信息失败: " + errorMsg.get());
                        return;
                    }

                    String finalMessage = EditCodeAction.stripCodeFence(accumulated.toString());
                    if (finalMessage.isBlank()) {
                        notifyError(project, "生成提交信息失败: LLM 未返回有效内容");
                        return;
                    }

                    indicator.setText("太微：提交信息已生成");
                    ApplicationManager.getApplication().invokeLater(() ->
                            commitPanel.setCommitMessage(finalMessage));

                } catch (Exception ex) {
                    LOG.warn("生成提交信息失败", ex);
                    notifyError(project, "生成提交信息失败: " + ex.getMessage());
                } finally {
                    client.close();
                    resetButtonState(e);
                }
            }
        });
    }

    @Nullable
    private static CommitMessageI getCommitPanel(@NotNull AnActionEvent e) {
        Refreshable data = Refreshable.PANEL_KEY.getData(e.getDataContext());
        if (data instanceof CommitMessageI) {
            return (CommitMessageI) data;
        }
        return VcsDataKeys.COMMIT_MESSAGE_CONTROL.getData(e.getDataContext());
    }

    @Nullable
    static String runGit(String basePath, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            for (String arg : args) {
                command.add(arg);
            }
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(basePath));
            pb.redirectErrorStream(false);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            return output.toString();
        } catch (Exception e) {
            LOG.warn("执行 git 命令失败: " + String.join(" ", args) + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * 从模板构建提交评审提示词
     */
    private static String buildPrompt(String status, String diff) {
        String template = loadTemplate("templates/commit_review_prompt.vm");
        return template
                .replace("${status}", status)
                .replace("${diff}", diff);
    }

    private static String loadTemplate(String resourcePath) {
        try (InputStream is = GenerateCommitMessageAction.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOG.warn("Template not found: " + resourcePath);
                return "git status:\n${status}\ngit diff:\n${diff}";
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            LOG.warn("Failed to load template: " + resourcePath, e);
            return "git status:\n${status}\ngit diff:\n${diff}";
        }
    }

    private static void notifyError(Project project, String message) {
        ApplicationManager.getApplication().invokeLater(() ->
                Messages.showErrorDialog(project, message, "太微 - 生成提交信息"));
    }

    private static void resetButtonState(@NotNull AnActionEvent e) {
        generating = false;
        ApplicationManager.getApplication().invokeLater(() -> {
            e.getPresentation().setEnabled(true);
            e.getPresentation().setText("太微：生成提交信息");
        });
    }
}
