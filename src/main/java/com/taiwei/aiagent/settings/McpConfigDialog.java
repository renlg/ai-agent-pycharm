package com.taiwei.aiagent.settings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.taiwei.aiagent.mcp.McpInitResult;
import com.taiwei.aiagent.mcp.McpManager;
import com.taiwei.aiagent.mcp.McpTransportType;
import com.taiwei.aiagent.util.I18nUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 服务器添加/编辑弹窗
 * 根据连接方式动态切换 STDIO / SSE / STREAMABLE_HTTP 表单
 */
public class McpConfigDialog extends DialogWrapper {

    private static final String CARD_STDIO = "STDIO";
    private static final String CARD_URL = "URL";

    private final Project project;
    private final AiAgentSettings.McpConfig config;

    private JTextField nameField;
    private JComboBox<McpTransportType> typeCombo;
    private JCheckBox enabledCheckBox;
    private JSpinner timeoutSpinner;

    private JTextField commandField;
    private JTextField argsField;
    private KeyValueTableEditor envEditor;

    private JTextField urlField;
    private KeyValueTableEditor headersEditor;

    private CardLayout cardLayout;
    private JPanel cardPanel;

    McpConfigDialog(Component parent, String title, AiAgentSettings.McpConfig config) {
        super(parent, true);
        this.project = resolveProject();
        this.config = config;
        setTitle(title);
        init();
    }

    private static Project resolveProject() {
        Project[] open = ProjectManager.getInstance().getOpenProjects();
        return open.length > 0 ? open[0] : ProjectManager.getInstance().getDefaultProject();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setPreferredSize(new Dimension(520, 480));

        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        formPanel.add(new JLabel(I18nUtil.getMessage("mcp.dialog.nameLabel")), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        nameField = new JTextField(config.name, 30);
        formPanel.add(nameField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        formPanel.add(new JLabel(I18nUtil.getMessage("mcp.dialog.typeLabel")), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        typeCombo = new JComboBox<>(McpTransportType.values());
        typeCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                            boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setText(transportLabel((McpTransportType) value));
                return this;
            }
        });
        typeCombo.setSelectedItem(config.transportType);
        typeCombo.addActionListener(e -> showCardFor((McpTransportType) typeCombo.getSelectedItem()));
        formPanel.add(typeCombo, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        formPanel.add(new JLabel(I18nUtil.getMessage("mcp.dialog.enabledLabel")), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        enabledCheckBox = new JCheckBox();
        enabledCheckBox.setSelected(config.enabled);
        formPanel.add(enabledCheckBox, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        formPanel.add(new JLabel(I18nUtil.getMessage("mcp.dialog.timeoutLabel")), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        timeoutSpinner = new JSpinner(new SpinnerNumberModel(config.timeoutSeconds, 1, 3600, 5));
        timeoutSpinner.setPreferredSize(new Dimension(120, 28));
        formPanel.add(timeoutSpinner, gbc);

        formPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(formPanel);
        panel.add(Box.createVerticalStrut(10));

        cardLayout = new CardLayout();
        cardPanel = new JPanel(cardLayout);
        cardPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        cardPanel.add(buildStdioCard(), CARD_STDIO);
        cardPanel.add(buildUrlCard(), CARD_URL);
        panel.add(cardPanel);
        panel.add(Box.createVerticalStrut(10));

        JButton testBtn = new JButton(I18nUtil.getMessage("mcp.dialog.testBtn"));
        testBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        testBtn.addActionListener(e -> onTestConnection());
        panel.add(testBtn);

        showCardFor(config.transportType);

        return panel;
    }

    private void showCardFor(McpTransportType type) {
        cardLayout.show(cardPanel, type == McpTransportType.STDIO ? CARD_STDIO : CARD_URL);
    }

    private static String transportLabel(McpTransportType type) {
        switch (type) {
            case STDIO:
                return I18nUtil.getMessage("mcp.stdioLabel");
            case SSE:
                return I18nUtil.getMessage("mcp.sseLabel");
            case STREAMABLE_HTTP:
                return I18nUtil.getMessage("mcp.httpLabel");
            default:
                return type.name();
        }
    }

    private JPanel buildStdioCard() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        formPanel.add(new JLabel(I18nUtil.getMessage("mcp.dialog.commandLabel")), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        commandField = new JTextField(config.command, 30);
        formPanel.add(commandField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        formPanel.add(new JLabel(I18nUtil.getMessage("mcp.dialog.argsLabel")), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        argsField = new JTextField(String.join(", ", config.args), 30);
        formPanel.add(argsField, gbc);

        formPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(formPanel);
        card.add(Box.createVerticalStrut(6));

        JLabel envLabel = new JLabel(I18nUtil.getMessage("mcp.dialog.envLabel"));
        envLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(envLabel);
        card.add(Box.createVerticalStrut(4));

        envEditor = new KeyValueTableEditor(config.env);
        envEditor.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(envEditor);

        return card;
    }

    private JPanel buildUrlCard() {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));

        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        formPanel.add(new JLabel(I18nUtil.getMessage("mcp.dialog.urlLabel")), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        urlField = new JTextField(config.url, 30);
        formPanel.add(urlField, gbc);

        formPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(formPanel);
        card.add(Box.createVerticalStrut(6));

        JLabel headersLabel = new JLabel(I18nUtil.getMessage("mcp.dialog.headersLabel"));
        headersLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(headersLabel);
        card.add(Box.createVerticalStrut(4));

        headersEditor = new KeyValueTableEditor(config.headers);
        headersEditor.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(headersEditor);

        return card;
    }

    private void stopAllEditing() {
        if (envEditor != null) envEditor.stopEditing();
        if (headersEditor != null) headersEditor.stopEditing();
    }

    private static List<String> parseArgs(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    private void onTestConnection() {
        stopAllEditing();
        McpTransportType selected = (McpTransportType) typeCombo.getSelectedItem();

        if (selected == McpTransportType.STDIO && commandField.getText().trim().isEmpty()) {
            Messages.showErrorDialog(getContentPanel(), "命令不能为空", "Error");
            return;
        }
        if (selected != McpTransportType.STDIO && urlField.getText().trim().isEmpty()) {
            Messages.showErrorDialog(getContentPanel(), "服务地址不能为空", "Error");
            return;
        }

        AiAgentSettings.McpConfig testConfig = new AiAgentSettings.McpConfig();
        testConfig.name = nameField.getText().trim();
        testConfig.transportType = selected;
        testConfig.enabled = true;
        testConfig.timeoutSeconds = (Integer) timeoutSpinner.getValue();
        testConfig.command = commandField.getText().trim();
        testConfig.args = parseArgs(argsField.getText());
        testConfig.env = new ArrayList<>(config.env);
        testConfig.url = urlField.getText().trim();
        testConfig.headers = new ArrayList<>(config.headers);

        McpInitResult result = McpManager.getInstance(project).testConnection(testConfig);
        if (result.isSuccess()) {
            Messages.showInfoMessage(getContentPanel(), I18nUtil.getMessage("mcp.dialog.testSuccess"),
                    I18nUtil.getMessage("mcp.dialog.testBtn"));
        } else {
            Messages.showErrorDialog(getContentPanel(),
                    I18nUtil.getMessage("mcp.dialog.testFailed") + ": " + result.getErrorMessage(),
                    I18nUtil.getMessage("mcp.dialog.testBtn"));
        }
    }

    /**
     * 供设置页面表格上的"测试连接"按钮复用：直接用已保存的配置测试连接。
     */
    static void testConnection(Component parent, AiAgentSettings.McpConfig config) {
        Project project = resolveProject();
        McpInitResult result = McpManager.getInstance(project).testConnection(config);
        if (result.isSuccess()) {
            Messages.showInfoMessage(parent, I18nUtil.getMessage("mcp.dialog.testSuccess"),
                    I18nUtil.getMessage("mcp.dialog.testBtn"));
        } else {
            Messages.showErrorDialog(parent,
                    I18nUtil.getMessage("mcp.dialog.testFailed") + ": " + result.getErrorMessage(),
                    I18nUtil.getMessage("mcp.dialog.testBtn"));
        }
    }

    @Override
    protected void doOKAction() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            Messages.showErrorDialog(getContentPanel(), "服务器名称不能为空", "Error");
            return;
        }
        McpTransportType selected = (McpTransportType) typeCombo.getSelectedItem();
        if (selected == McpTransportType.STDIO && commandField.getText().trim().isEmpty()) {
            Messages.showErrorDialog(getContentPanel(), "命令不能为空", "Error");
            return;
        }
        if (selected != McpTransportType.STDIO && urlField.getText().trim().isEmpty()) {
            Messages.showErrorDialog(getContentPanel(), "服务地址不能为空", "Error");
            return;
        }

        stopAllEditing();

        config.name = name;
        config.transportType = selected;
        config.enabled = enabledCheckBox.isSelected();
        config.timeoutSeconds = (Integer) timeoutSpinner.getValue();
        config.command = commandField.getText().trim();
        config.args = parseArgs(argsField.getText());
        config.url = urlField.getText().trim();

        super.doOKAction();
    }

    AiAgentSettings.McpConfig getConfig() {
        return config;
    }

    // ===== Key/Value 表格编辑组件（用于环境变量 / 请求头） =====

    private static class KeyValueTableEditor extends JPanel {
        private final JBTable table;

        KeyValueTableEditor(List<AiAgentSettings.HeaderEntry> entries) {
            super(new BorderLayout(0, 4));
            KeyValueTableModel model = new KeyValueTableModel(entries);
            table = new JBTable(model);
            table.setRowHeight(28);
            JBScrollPane scrollPane = new JBScrollPane(table);
            scrollPane.setPreferredSize(new Dimension(420, 110));

            JButton addBtn = new JButton(I18nUtil.getMessage("mcp.dialog.addHeaderBtn"));
            JButton removeBtn = new JButton(I18nUtil.getMessage("mcp.dialog.removeHeaderBtn"));
            addBtn.addActionListener(e -> {
                entries.add(new AiAgentSettings.HeaderEntry("", ""));
                model.fireTableRowsInserted(entries.size() - 1, entries.size() - 1);
            });
            removeBtn.addActionListener(e -> {
                stopEditing();
                int row = table.getSelectedRow();
                if (row >= 0) {
                    entries.remove(row);
                    model.fireTableRowsDeleted(row, row);
                }
            });

            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            btnPanel.add(addBtn);
            btnPanel.add(removeBtn);

            add(scrollPane, BorderLayout.CENTER);
            add(btnPanel, BorderLayout.SOUTH);
        }

        void stopEditing() {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
        }
    }

    private static class KeyValueTableModel extends AbstractTableModel {
        private final List<AiAgentSettings.HeaderEntry> entries;

        KeyValueTableModel(List<AiAgentSettings.HeaderEntry> entries) {
            this.entries = entries;
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? I18nUtil.getMessage("mcp.dialog.keyColumn") : I18nUtil.getMessage("mcp.dialog.valueColumn");
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            AiAgentSettings.HeaderEntry entry = entries.get(rowIndex);
            return columnIndex == 0 ? entry.key : entry.value;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            AiAgentSettings.HeaderEntry entry = entries.get(rowIndex);
            String text = aValue == null ? "" : aValue.toString();
            if (columnIndex == 0) {
                entry.key = text;
            } else {
                entry.value = text;
            }
        }
    }
}
