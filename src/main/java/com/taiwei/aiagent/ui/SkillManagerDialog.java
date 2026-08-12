package com.taiwei.aiagent.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.skill.Skill;
import com.taiwei.aiagent.skill.SkillManager;
import com.taiwei.aiagent.util.I18nUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Skill 管理面板：查看已加载的 Skill、启用/禁用、以及从磁盘刷新。
 * 非模态弹窗，允许用户在弹窗打开的同时继续与聊天面板交互。
 */
public class SkillManagerDialog extends DialogWrapper {

    private final SkillManager skillManager;
    private final AiAgentSettings settings;
    private SkillTableModel tableModel;
    private JBTable table;

    public SkillManagerDialog(@NotNull Project project) {
        super(project, false, IdeModalityType.MODELESS);
        this.skillManager = SkillManager.getInstance(project);
        this.settings = AiAgentSettings.getInstance();
        setTitle(I18nUtil.getMessage("skill.manager.title"));
        setCancelButtonText(I18nUtil.getMessage("skill.manager.closeBtn"));
        setModal(false);
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        tableModel = new SkillTableModel(loadSkills());
        table = new JBTable(tableModel);
        table.setRowHeight(28);
        table.getColumnModel().getColumn(0).setPreferredWidth(180);
        table.getColumnModel().getColumn(1).setPreferredWidth(400);
        table.getColumnModel().getColumn(2).setPreferredWidth(80);

        JBScrollPane scrollPane = new JBScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(700, 320));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getCancelAction()};
    }

    @Override
    protected @NotNull Action @NotNull [] createLeftSideActions() {
        return new Action[]{new RefreshAction()};
    }

    private List<Skill> loadSkills() {
        return new ArrayList<>(skillManager.listSkills());
    }

    private void onRefresh() {
        skillManager.refresh();
        tableModel.setSkills(loadSkills());
    }

    // ========== 刷新按钮 ==========

    private class RefreshAction extends AbstractAction {
        RefreshAction() {
            super(I18nUtil.getMessage("skill.manager.refreshBtn"));
        }

        @Override
        public void actionPerformed(java.awt.event.ActionEvent e) {
            onRefresh();
        }
    }

    // ========== Table Model ==========

    private class SkillTableModel extends AbstractTableModel {
        private final String[] columns = {
                I18nUtil.getMessage("skill.manager.columnName"),
                I18nUtil.getMessage("skill.manager.columnDescription"),
                I18nUtil.getMessage("skill.manager.columnStatus")
        };
        private List<Skill> skills;

        SkillTableModel(List<Skill> skills) {
            this.skills = skills;
        }

        void setSkills(List<Skill> skills) {
            this.skills = skills;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return skills.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 2 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 2;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Skill skill = skills.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return skill.getName();
                case 1:
                    return skill.getDescription();
                case 2:
                    return settings.isSkillEnabled(skill.getName());
                default:
                    return "";
            }
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex != 2) return;
            Skill skill = skills.get(rowIndex);
            settings.setSkillEnabled(skill.getName(), Boolean.TRUE.equals(value));
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
