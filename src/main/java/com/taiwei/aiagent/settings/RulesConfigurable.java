package com.taiwei.aiagent.settings;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.taiwei.aiagent.util.I18nUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 自定义规则配置页面
 * Settings → Tools → 太微 → 规则
 *
 * 支持两级规则：
 * 1. 全局规则（Settings 中配置，持久化到 ai-agent-settings.xml）
 * 2. 项目级规则（项目根目录下 .taiwei/rules.md 文件）
 */
public class RulesConfigurable implements Configurable {

    private JPanel mainPanel;
    private JTextArea globalRulesTextArea;
    private JLabel projectRulesStatusLabel;
    private JButton openProjectRulesButton;
    private JPanel projectRulesPanel;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return I18nUtil.getMessage("rules.title");
    }

    @Override
    public @Nullable JComponent createComponent() {
        // ===== 全局规则区域 =====
        JLabel globalLabel = new JLabel(I18nUtil.getMessage("rules.globalRulesLabel"));
        globalLabel.setFont(globalLabel.getFont().deriveFont(Font.BOLD, 14f));

        JLabel globalHintLabel = new JLabel(I18nUtil.getMessage("rules.globalRulesHint"));
        globalHintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        JLabel editorHintLabel = new JLabel(I18nUtil.getMessage("rules.globalRulesEditorHint"));
        editorHintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        globalRulesTextArea = new JTextArea();
        globalRulesTextArea.setLineWrap(true);
        globalRulesTextArea.setWrapStyleWord(true);
        globalRulesTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        globalRulesTextArea.setMargin(JBUI.insets(8));

        JBScrollPane scrollPane = new JBScrollPane(globalRulesTextArea);
        scrollPane.setPreferredSize(new Dimension(600, 200));
        scrollPane.setMinimumSize(new Dimension(400, 120));

        JPanel globalPanel = new JPanel(new BorderLayout(0, 6));
        globalPanel.add(globalLabel, BorderLayout.NORTH);
        JPanel globalContentPanel = new JPanel();
        globalContentPanel.setLayout(new BoxLayout(globalContentPanel, BoxLayout.Y_AXIS));
        globalContentPanel.add(globalHintLabel);
        globalContentPanel.add(Box.createVerticalStrut(8));
        globalContentPanel.add(editorHintLabel);
        globalContentPanel.add(Box.createVerticalStrut(6));
        globalContentPanel.add(scrollPane);
        globalPanel.add(globalContentPanel, BorderLayout.CENTER);

        // ===== 项目级规则区域 =====
        JLabel projectLabel = new JLabel(I18nUtil.getMessage("rules.projectRulesLabel"));
        projectLabel.setFont(projectLabel.getFont().deriveFont(Font.BOLD, 14f));

        projectRulesStatusLabel = new JLabel();
        projectRulesStatusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        openProjectRulesButton = new JButton(I18nUtil.getMessage("rules.openProjectRules"));
        openProjectRulesButton.setVisible(false);
        openProjectRulesButton.addActionListener(e -> openProjectRulesFile());

        JPanel projectButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        projectButtonPanel.add(openProjectRulesButton);

        projectRulesPanel = new JPanel(new BorderLayout(0, 6));
        projectRulesPanel.add(projectLabel, BorderLayout.NORTH);
        JPanel projectContentPanel = new JPanel();
        projectContentPanel.setLayout(new BoxLayout(projectContentPanel, BoxLayout.Y_AXIS));
        projectContentPanel.add(projectRulesStatusLabel);
        projectContentPanel.add(Box.createVerticalStrut(6));
        projectContentPanel.add(projectButtonPanel);
        projectRulesPanel.add(projectContentPanel, BorderLayout.CENTER);

        // ===== 组装主面板 =====
        mainPanel = FormBuilder.createFormBuilder()
                .addComponent(globalPanel)
                .addComponent(new JPanel()) // spacer
                .addComponent(projectRulesPanel)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(10));

        updateProjectRulesStatus();
        reset();
        return mainPanel;
    }

    /**
     * 更新项目级规则状态显示
     */
    private void updateProjectRulesStatus() {
        String basePath = getProjectBasePath();
        if (basePath == null) {
            projectRulesStatusLabel.setText(I18nUtil.getMessage("rules.noProjectRules"));
            openProjectRulesButton.setVisible(false);
            return;
        }

        Path rulesFile = Paths.get(basePath, ".taiwei", "rules.md");
        if (Files.exists(rulesFile)) {
            String displayPath = ".taiwei/rules.md";
            projectRulesStatusLabel.setText(
                    String.format(I18nUtil.getMessage("rules.projectRulesHint"), displayPath));
            openProjectRulesButton.setVisible(true);
        } else {
            projectRulesStatusLabel.setText(I18nUtil.getMessage("rules.noProjectRules"));
            openProjectRulesButton.setVisible(false);
        }
    }

    /**
     * 在 IDE 中打开项目级规则文件
     */
    private void openProjectRulesFile() {
        String basePath = getProjectBasePath();
        if (basePath == null) return;

        Path rulesFile = Paths.get(basePath, ".taiwei", "rules.md");
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(rulesFile.toString());
        if (vf != null) {
            Project project = getActiveProject();
            if (project != null) {
                FileEditorManager.getInstance(project).openFile(vf, true);
            }
        }
    }

    private String getProjectBasePath() {
        Project project = getActiveProject();
        return project != null ? project.getBasePath() : null;
    }

    private Project getActiveProject() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        return projects.length > 0 ? projects[0] : null;
    }

    // ===== Configurable 接口 =====

    @Override
    public boolean isModified() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        String current = settings.getCustomRules();
        String edited = globalRulesTextArea != null ? globalRulesTextArea.getText() : "";
        return !edited.equals(current);
    }

    @Override
    public void apply() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        settings.setCustomRules(globalRulesTextArea != null ? globalRulesTextArea.getText() : "");
        settings.fireSettingsChanged();
    }

    @Override
    public void reset() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        if (globalRulesTextArea != null) {
            globalRulesTextArea.setText(settings.getCustomRules());
        }
        updateProjectRulesStatus();
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        globalRulesTextArea = null;
        projectRulesStatusLabel = null;
        openProjectRulesButton = null;
        projectRulesPanel = null;
    }
}
