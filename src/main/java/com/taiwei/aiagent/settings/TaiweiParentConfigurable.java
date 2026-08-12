package com.taiwei.aiagent.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.taiwei.aiagent.ui.ToolManagerDialog;
import com.taiwei.aiagent.util.I18nUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
/**
 * 太微设置主页面
 * Settings → Tools → 太微
 * 提供功能开关：自动补全、Git 提交评审
 */
public class TaiweiParentConfigurable implements Configurable {

    private JPanel mainPanel;
    private JCheckBox completionCheckBox;
    private JCheckBox inlineActionCheckBox;
    private JCheckBox gitCommitReviewCheckBox;
    private JCheckBox bypassHostnameVerificationCheckBox;
    private JComboBox<String> toolManagerCombo;
    private static final String PLACEHOLDER = "────── 操作 ──────";
    private static final String ACTION_MANAGE = "管理工具...";

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "太微";
    }

    @Override
    public @Nullable JComponent createComponent() {
        completionCheckBox = new JCheckBox(I18nUtil.getMessage("general.completionEnabled"));
        inlineActionCheckBox = new JCheckBox(I18nUtil.getMessage("general.inlineActionEnabled"));
        inlineActionCheckBox.setToolTipText(I18nUtil.getMessage("general.inlineActionEnabled.desc"));
        gitCommitReviewCheckBox = new JCheckBox(I18nUtil.getMessage("general.gitCommitReviewEnabled"));
        bypassHostnameVerificationCheckBox = new JCheckBox(I18nUtil.getMessage("general.bypassHostnameVerification"));
        bypassHostnameVerificationCheckBox.setToolTipText(I18nUtil.getMessage("general.bypassHostnameVerification.desc"));

        JLabel modelEntryLabel = new JLabel(I18nUtil.getMessage("settings.modelEntry"));
        modelEntryLabel.setForeground(UIUtil.getLabelForeground());
        modelEntryLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        modelEntryLabel.setToolTipText(I18nUtil.getMessage("settings.modelEntry.tooltip"));
        modelEntryLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openModelSettings();
            }
            @Override
            public void mouseEntered(MouseEvent e) {
                modelEntryLabel.setForeground(UIUtil.getLabelForeground());
                ((JLabel) e.getSource()).setText("<html><u>" + I18nUtil.getMessage("settings.modelEntry") + "</u></html>");
            }
            @Override
            public void mouseExited(MouseEvent e) {
                ((JLabel) e.getSource()).setText(I18nUtil.getMessage("settings.modelEntry"));
            }
        });
        JPanel modelEntryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        modelEntryPanel.add(modelEntryLabel);

        JLabel codeSectionLabel = new JLabel(I18nUtil.getMessage("general.codeCompletionSectionTitle"));
        codeSectionLabel.setFont(codeSectionLabel.getFont().deriveFont(Font.BOLD));

        toolManagerCombo = new JComboBox<>(new String[]{PLACEHOLDER, ACTION_MANAGE});
        toolManagerCombo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && ACTION_MANAGE.equals(e.getItem())) {
                openToolManager();
                toolManagerCombo.setSelectedItem(PLACEHOLDER);
            }
        });
        JPanel toolManagerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        toolManagerPanel.add(new JLabel("工具管理: "));
        toolManagerPanel.add(toolManagerCombo);

        mainPanel = FormBuilder.createFormBuilder()
                .addComponent(modelEntryPanel)
                .addComponent(new JSeparator())
                .addComponent(codeSectionLabel)
                .addComponent(completionCheckBox)
                .addComponent(inlineActionCheckBox)
                .addComponent(gitCommitReviewCheckBox)
                .addComponent(bypassHostnameVerificationCheckBox)
                .addComponent(toolManagerPanel)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        mainPanel.setBorder(JBUI.Borders.empty(10));

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        return completionCheckBox.isSelected() != settings.isCompletionEnabled()
                || inlineActionCheckBox.isSelected() != settings.isInlineActionEnabled()
                || gitCommitReviewCheckBox.isSelected() != settings.isGitCommitReviewEnabled()
                || bypassHostnameVerificationCheckBox.isSelected() != settings.isBypassHostnameVerificationEnabled();
    }

    @Override
    public void apply() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        settings.setCompletionEnabled(completionCheckBox.isSelected());
        settings.setInlineActionEnabled(inlineActionCheckBox.isSelected());
        settings.setGitCommitReviewEnabled(gitCommitReviewCheckBox.isSelected());
        settings.setBypassHostnameVerificationEnabled(bypassHostnameVerificationCheckBox.isSelected());
        settings.fireSettingsChanged();
    }

    @Override
    public void reset() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        completionCheckBox.setSelected(settings.isCompletionEnabled());
        inlineActionCheckBox.setSelected(settings.isInlineActionEnabled());
        gitCommitReviewCheckBox.setSelected(settings.isGitCommitReviewEnabled());
        bypassHostnameVerificationCheckBox.setSelected(settings.isBypassHostnameVerificationEnabled());
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        completionCheckBox = null;
        inlineActionCheckBox = null;
        gitCommitReviewCheckBox = null;
        bypassHostnameVerificationCheckBox = null;
        toolManagerCombo = null;
    }

    private void openToolManager() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        if (projects.length == 0) {
            Messages.showInfoMessage(mainPanel, I18nUtil.getMessage("tool.manager.noProject"), I18nUtil.getMessage("tool.manager.button"));
            return;
        }
        new ToolManagerDialog(projects[0]).show();
    }

    private void openModelSettings() {
        Project[] projects = ProjectManager.getInstance().getOpenProjects();
        if (projects.length > 0) {
            ShowSettingsUtil.getInstance().showSettingsDialog(projects[0], ModelConfigurable.class);
        }
    }
}
