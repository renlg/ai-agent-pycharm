package com.taiwei.aiagent.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.taiwei.aiagent.agent.AgentContext;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.util.I18nUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.List;

/**
 * 工具管理面板：查看当前已加载的工具（内置 + MCP）、启用/禁用。
 * 非模态弹窗，允许用户在弹窗打开的同时继续与聊天面板交互。
 */
public class ToolManagerDialog extends DialogWrapper {

    private final Project project;
    private final AiAgentSettings settings;
    private ToolTableModel tableModel;
    private JBTable table;

    public ToolManagerDialog(@NotNull Project project) {
        super(project, false, IdeModalityType.MODELESS);
        this.project = project;
        this.settings = AiAgentSettings.getInstance();
        setTitle(I18nUtil.getMessage("tool.manager.title"));
        setCancelButtonText(I18nUtil.getMessage("tool.manager.closeBtn"));
        setModal(false);
        init();
    }

    @Override
    protected JComponent createCenterPanel() {
        tableModel = new ToolTableModel(loadTools());
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

    private List<Tool> loadTools() {
        return AgentContext.buildDefaultTools(project);
    }

    private void onRefresh() {
        tableModel.setTools(loadTools());
    }

    // ========== 刷新按钮 ==========

    private class RefreshAction extends AbstractAction {
        RefreshAction() {
            super(I18nUtil.getMessage("tool.manager.refreshBtn"));
        }

        @Override
        public void actionPerformed(java.awt.event.ActionEvent e) {
            onRefresh();
        }
    }

    // ========== Table Model ==========

    private class ToolTableModel extends AbstractTableModel {
        private final String[] columns = {
                I18nUtil.getMessage("tool.manager.columnName"),
                I18nUtil.getMessage("tool.manager.columnDescription"),
                I18nUtil.getMessage("tool.manager.columnStatus")
        };
        private List<Tool> tools;

        ToolTableModel(List<Tool> tools) {
            this.tools = tools;
        }

        void setTools(List<Tool> tools) {
            this.tools = tools;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return tools.size();
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
            Tool tool = tools.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return tool.getName();
                case 1:
                    return tool.getDescription();
                case 2:
                    return settings.isToolEnabled(tool.getName());
                default:
                    return "";
            }
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex != 2) return;
            Tool tool = tools.get(rowIndex);
            settings.setToolEnabled(tool.getName(), Boolean.TRUE.equals(value));
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }
}
