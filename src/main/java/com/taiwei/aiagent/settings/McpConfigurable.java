package com.taiwei.aiagent.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.taiwei.aiagent.mcp.McpTransportType;
import com.taiwei.aiagent.util.I18nUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 服务器配置页面
 * Settings → Tools → 太微 → MCP
 */
public class McpConfigurable implements Configurable {

    private JPanel mainPanel;
    private McpTableModel tableModel;
    private JBTable table;

    private List<AiAgentSettings.McpConfig> editingConfigs;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return I18nUtil.getMessage("mcp.title");
    }

    @Override
    public @Nullable JComponent createComponent() {
        editingConfigs = new ArrayList<>();

        // ===== MCP 服务器列表表格 =====
        tableModel = new McpTableModel();
        table = new JBTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(2).setPreferredWidth(250);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);
        table.getColumnModel().getColumn(3).setCellRenderer(new StatusCellRenderer());
        table.setRowHeight(32);

        JBScrollPane scrollPane = new JBScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(700, 280));
        scrollPane.setMinimumSize(new Dimension(500, 180));

        // 按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        JButton addBtn = new JButton(I18nUtil.getMessage("mcp.addBtn"));
        JButton editBtn = new JButton(I18nUtil.getMessage("mcp.editBtn"));
        JButton deleteBtn = new JButton(I18nUtil.getMessage("mcp.deleteBtn"));
        JButton testBtn = new JButton(I18nUtil.getMessage("mcp.testBtn"));

        addBtn.addActionListener(e -> onAdd());
        editBtn.addActionListener(e -> onEdit());
        deleteBtn.addActionListener(e -> onDelete());
        testBtn.addActionListener(e -> onTest());

        buttonPanel.add(addBtn);
        buttonPanel.add(editBtn);
        buttonPanel.add(deleteBtn);
        buttonPanel.add(testBtn);

        // ===== 组装主面板 =====
        JPanel section = new JPanel(new BorderLayout(0, 8));
        section.add(scrollPane, BorderLayout.CENTER);
        section.add(buttonPanel, BorderLayout.SOUTH);

        mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(JBUI.Borders.empty(12));

        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(section);
        mainPanel.add(Box.createVerticalGlue());

        reset();
        return mainPanel;
    }

    // ===== 按钮事件 =====

    private void onAdd() {
        McpConfigDialog dialog = new McpConfigDialog(mainPanel, I18nUtil.getMessage("mcp.dialog.addTitle"), new AiAgentSettings.McpConfig());
        if (dialog.showAndGet()) {
            editingConfigs.add(dialog.getConfig());
            tableModel.fireTableDataChanged();
            int row = tableModel.getRowCount() - 1;
            table.setRowSelectionInterval(row, row);
        }
    }

    private void onEdit() {
        int row = table.getSelectedRow();
        if (row < 0) {
            Messages.showInfoMessage(mainPanel, I18nUtil.getMessage("mcp.selectFirst"), I18nUtil.getMessage("mcp.selectFirst"));
            return;
        }
        AiAgentSettings.McpConfig config = editingConfigs.get(row);
        McpConfigDialog dialog = new McpConfigDialog(mainPanel, I18nUtil.getMessage("mcp.dialog.editTitle"), config.copy());
        if (dialog.showAndGet()) {
            editingConfigs.set(row, dialog.getConfig());
            tableModel.fireTableDataChanged();
        }
    }

    private void onDelete() {
        int row = table.getSelectedRow();
        if (row < 0) {
            Messages.showInfoMessage(mainPanel, I18nUtil.getMessage("mcp.selectFirst"), I18nUtil.getMessage("mcp.selectFirst"));
            return;
        }
        AiAgentSettings.McpConfig config = editingConfigs.get(row);
        int result = Messages.showYesNoDialog(
                mainPanel,
                String.format(I18nUtil.getMessage("mcp.deleteConfirm"), config.name),
                I18nUtil.getMessage("mcp.deleteBtn"),
                Messages.getQuestionIcon()
        );
        if (result == Messages.YES) {
            editingConfigs.remove(row);
            tableModel.fireTableDataChanged();
            if (!editingConfigs.isEmpty()) {
                int newSel = Math.min(row, tableModel.getRowCount() - 1);
                table.setRowSelectionInterval(newSel, newSel);
            }
        }
    }

    private void onTest() {
        int row = table.getSelectedRow();
        if (row < 0) {
            Messages.showInfoMessage(mainPanel, I18nUtil.getMessage("mcp.selectFirst"), I18nUtil.getMessage("mcp.selectFirst"));
            return;
        }
        AiAgentSettings.McpConfig config = editingConfigs.get(row);
        McpConfigDialog.testConnection(mainPanel, config);
    }

    // ===== Configurable 接口 =====

    @Override
    public boolean isModified() {
        List<AiAgentSettings.McpConfig> current = AiAgentSettings.getInstance().getMcpConfigs();
        if (editingConfigs.size() != current.size()) return true;
        for (int i = 0; i < editingConfigs.size(); i++) {
            if (!configsEqual(editingConfigs.get(i), current.get(i))) return true;
        }
        return false;
    }

    @Override
    public void apply() throws ConfigurationException {
        for (AiAgentSettings.McpConfig config : editingConfigs) {
            if (config.name.trim().isEmpty()) {
                throw new ConfigurationException(I18nUtil.getMessage("mcp.dialog.nameLabel") + " 不能为空");
            }
        }
        AiAgentSettings settings = AiAgentSettings.getInstance();
        settings.setMcpConfigs(new ArrayList<>(editingConfigs));
        settings.fireSettingsChanged();
    }

    @Override
    public void reset() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        editingConfigs.clear();
        for (AiAgentSettings.McpConfig config : settings.getMcpConfigs()) {
            editingConfigs.add(config.copy());
        }
        tableModel.fireTableDataChanged();
        if (!editingConfigs.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        table = null;
        tableModel = null;
        editingConfigs = null;
    }

    private boolean configsEqual(AiAgentSettings.McpConfig a, AiAgentSettings.McpConfig b) {
        if (!a.name.equals(b.name)) return false;
        if (a.enabled != b.enabled) return false;
        if (a.transportType != b.transportType) return false;
        if (!a.command.equals(b.command)) return false;
        if (!a.args.equals(b.args)) return false;
        if (!headerListEquals(a.env, b.env)) return false;
        if (!a.url.equals(b.url)) return false;
        if (!headerListEquals(a.headers, b.headers)) return false;
        if (a.timeoutSeconds != b.timeoutSeconds) return false;
        return a.disabledTools.equals(b.disabledTools);
    }

    private boolean headerListEquals(List<AiAgentSettings.HeaderEntry> a, List<AiAgentSettings.HeaderEntry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).key.equals(b.get(i).key) || !a.get(i).value.equals(b.get(i).value)) return false;
        }
        return true;
    }

    // ===== Table Model =====

    private class McpTableModel extends AbstractTableModel {
        private final String[] COLUMNS = {
                I18nUtil.getMessage("mcp.columnName"),
                I18nUtil.getMessage("mcp.columnType"),
                I18nUtil.getMessage("mcp.columnUrl"),
                I18nUtil.getMessage("mcp.columnStatus")
        };

        @Override
        public int getRowCount() {
            return editingConfigs.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            AiAgentSettings.McpConfig config = editingConfigs.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return config.name;
                case 1:
                    return config.transportType.name();
                case 2:
                    return config.transportType == McpTransportType.STDIO ? config.command : config.url;
                case 3:
                    return config.enabled ? I18nUtil.getMessage("mcp.enabled") : I18nUtil.getMessage("mcp.disabled");
                default:
                    return "";
            }
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }
    }

    // ===== 状态列渲染器（启用=绿色，禁用=灰色） =====

    private class StatusCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            boolean enabled = row >= 0 && row < editingConfigs.size() && editingConfigs.get(row).enabled;
            if (!isSelected) {
                label.setForeground(enabled
                        ? new JBColor(new Color(0x008000), new Color(0x6A8759))
                        : JBColor.GRAY);
            }
            return label;
        }
    }
}
