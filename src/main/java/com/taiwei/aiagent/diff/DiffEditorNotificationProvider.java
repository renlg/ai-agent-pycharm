package com.taiwei.aiagent.diff;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.EditorNotifications;
import com.taiwei.aiagent.util.I18nUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;
import java.util.function.Function;

/**
 * Editor notification provider that displays a banner at the top of the editor
 * when the current file has un-reviewed AI changes.
 * Uses IntelliJ 2024.1+ EditorNotificationProvider API.
 */
public class DiffEditorNotificationProvider implements EditorNotificationProvider {

    @Override
    public @Nullable Function<? super @NotNull FileEditor, ? extends @Nullable JComponent> collectNotificationData(
            @NotNull Project project, @NotNull VirtualFile file) {

        return fileEditor -> {
            if (!(fileEditor instanceof TextEditor)) return null;

            DiffReviewService service = DiffReviewService.getInstance(project);
            String filePath = file.getPath();
            List<DiffEntry> fileDiffs = service.getDiffsForFile(filePath);
            if (fileDiffs.isEmpty()) {
                return null;
            }

            return createNotificationPanel(project, file, filePath, service);
        };
    }

    @NotNull
    private JComponent createNotificationPanel(@NotNull Project project, @NotNull VirtualFile file,
                                                String filePath, DiffReviewService service) {
        EditorNotificationPanel panel = new EditorNotificationPanel(EditorNotificationPanel.Status.Info);

        int totalCount = service.getDiffCount();
        int currentIdx = service.getCurrentIndex();
        panel.setText(I18nUtil.getMessage("diff.banner", currentIdx + 1, totalCount));

        DiffHighlighter highlighter = service.getHighlighter();

        // 上一个 / 下一个文件导航
        if (totalCount > 1) {
            panel.createActionLabel(I18nUtil.getMessage("diff.previous"), () -> {
                int idx = service.getCurrentIndex();
                if (idx > 0) {
                    service.setCurrentIndex(idx - 1);
                    navigateToCurrentDiff(project, service);
                }
            });
            panel.createActionLabel(I18nUtil.getMessage("diff.next"), () -> {
                int idx = service.getCurrentIndex();
                if (idx < totalCount - 1) {
                    service.setCurrentIndex(idx + 1);
                    navigateToCurrentDiff(project, service);
                }
            });
        }

        // 隐藏/显示 Diff
        boolean isHidden = service.isDiffHidden(filePath);
        panel.createActionLabel(I18nUtil.getMessage(isHidden ? "diff.show" : "diff.hide"), () -> {
            boolean nowHidden = service.toggleHidden(filePath);
            if (nowHidden) {
                highlighter.clearHighlightsForFile(filePath);
            } else {
                List<DiffEntry> diffs = service.getDiffsForFile(filePath);
                if (!diffs.isEmpty()) {
                    highlighter.highlight(diffs.get(diffs.size() - 1));
                }
            }
            EditorNotifications.getInstance(project).updateAllNotifications();
        });

        // 撤销更改
        panel.createActionLabel(I18nUtil.getMessage("diff.revert"), () -> {
            highlighter.clearHighlightsForFile(filePath);
            service.revertByFile(filePath);
            EditorNotifications.getInstance(project).updateAllNotifications();
        });

        // 保留更改
        panel.createActionLabel(I18nUtil.getMessage("diff.keep"), () -> {
            highlighter.clearHighlightsForFile(filePath);
            service.acceptByFile(filePath);
            EditorNotifications.getInstance(project).updateAllNotifications();
        });

        // 自动高亮（第一次打开时）
        if (!isHidden) {
            ApplicationManager.getApplication().invokeLater(() -> {
                List<DiffEntry> diffs = service.getDiffsForFile(filePath);
                if (!diffs.isEmpty()) {
                    highlighter.highlight(diffs.get(diffs.size() - 1));
                }
            });
        }

        return panel;
    }

    private void navigateToCurrentDiff(Project project, DiffReviewService service) {
        DiffEntry entry = service.getCurrentDiff();
        if (entry == null) return;
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(entry.getFilePath());
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true);
        }
        EditorNotifications.getInstance(project).updateAllNotifications();
    }
}
