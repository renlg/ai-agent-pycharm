package com.taiwei.aiagent.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 太微插件设置页面
 * Settings → Tools → 太微
 * 上方模型列表 + 下方添加/编辑/删除按钮 + 全局参数
 */
public class AiAgentConfigurable implements Configurable {

    private JPanel mainPanel;
    private ModelTableModel tableModel;
    private JBTable table;
    private JTextArea dangerousCommandsArea;

    private List<AiAgentSettings.ModelConfig> editingConfigs;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "太微";
    }

    @Override
    public String getHelpTopic() {
        return "preferences.taiwei";
    }

    @Override
    public @Nullable JComponent createComponent() {
        editingConfigs = new ArrayList<>();

        // ===== 上方：模型列表表格 =====
        tableModel = new ModelTableModel();
        table = new JBTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(120);
        table.getColumnModel().getColumn(1).setPreferredWidth(250);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.setRowHeight(28);

        JBScrollPane scrollPane = new JBScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(600, 180));

        // 按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton addBtn = new JButton("添加");
        JButton editBtn = new JButton("编辑");
        JButton deleteBtn = new JButton("删除");

        addBtn.addActionListener(e -> onAddModel());
        editBtn.addActionListener(e -> onEditModel());
        deleteBtn.addActionListener(e -> onDeleteModel());

        buttonPanel.add(addBtn);
        buttonPanel.add(editBtn);
        buttonPanel.add(deleteBtn);

        // ===== 模型配置区域 =====
        JPanel modelSection = new JPanel(new BorderLayout(0, 4));
        JLabel sectionLabel = new JLabel("模型配置");
        sectionLabel.setFont(sectionLabel.getFont().deriveFont(Font.BOLD, 13f));
        modelSection.add(sectionLabel, BorderLayout.NORTH);
        modelSection.add(scrollPane, BorderLayout.CENTER);
        modelSection.add(buttonPanel, BorderLayout.SOUTH);

        // ===== 提示信息 =====
        JTextArea hintArea = new JTextArea(
                "常用模型参考:\n" +
                "  OpenAI:     api.openai.com        | gpt-4o, gpt-4o-mini\n" +
                "  DeepSeek:   api.deepseek.com      | deepseek-chat, deepseek-reasoner\n" +
                "  通义千问:   dashscope.aliyuncs.com | qwen-turbo, qwen-plus\n" +
                "  Moonshot:   api.moonshot.cn       | moonshot-v1-8k, moonshot-v1-32k"
        );
        hintArea.setEditable(false);
        hintArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        hintArea.setBackground(new JBColor(new Color(0xF5F5F5), new Color(0x2B2B2B)));
        hintArea.setBorder(JBUI.Borders.empty(6));
        hintArea.setLineWrap(true);

        // ===== 组装主面板 =====
        mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(JBUI.Borders.empty(10));

        modelSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(modelSection);
        mainPanel.add(Box.createVerticalStrut(16));

        hintArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(hintArea);
        mainPanel.add(Box.createVerticalStrut(16));

        // ===== 危险命令配置区域 =====
        JLabel dangerLabel = new JLabel("危险命令配置");
        dangerLabel.setFont(dangerLabel.getFont().deriveFont(Font.BOLD, 13f));
        dangerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(dangerLabel);
        mainPanel.add(Box.createVerticalStrut(8));

        JTextArea hintArea2 = new JTextArea("匹配到以下模式的命令需要手动点击运行按钮");
        hintArea2.setEditable(false);
        hintArea2.setFont(new Font("Monospaced", Font.PLAIN, 11));
        hintArea2.setBackground(new JBColor(new Color(0xF5F5F5), new Color(0x2B2B2B)));
        hintArea2.setBorder(JBUI.Borders.empty(4));
        hintArea2.setLineWrap(true);
        hintArea2.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(hintArea2);
        mainPanel.add(Box.createVerticalStrut(4));

        dangerousCommandsArea = new JTextArea(10, 50);
        dangerousCommandsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        dangerousCommandsArea.setLineWrap(false);
        dangerousCommandsArea.setBorder(JBUI.Borders.empty(4));
        JBScrollPane dangerScroll = new JBScrollPane(dangerousCommandsArea);
        dangerScroll.setPreferredSize(new Dimension(600, 160));
        dangerScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(dangerScroll);
        mainPanel.add(Box.createVerticalGlue());

        reset();
        return mainPanel;
    }

    // ===== 按钮事件 =====

    private void onAddModel() {
        ModelConfigDialog dialog = new ModelConfigDialog(mainPanel, "添加模型", new AiAgentSettings.ModelConfig());
        if (dialog.showAndGet()) {
            AiAgentSettings.ModelConfig config = dialog.getConfig();
            editingConfigs.add(config);
            tableModel.fireTableDataChanged();
            int row = tableModel.getRowCount() - 1;
            table.setRowSelectionInterval(row, row);
        }
    }

    private void onEditModel() {
        int row = table.getSelectedRow();
        if (row < 0) {
            Messages.showInfoMessage(mainPanel, "请先选择一个模型", "提示");
            return;
        }
        AiAgentSettings.ModelConfig config = editingConfigs.get(row);
        ModelConfigDialog dialog = new ModelConfigDialog(mainPanel, "编辑模型", config.copy());
        if (dialog.showAndGet()) {
            AiAgentSettings.ModelConfig updated = dialog.getConfig();
            editingConfigs.set(row, updated);
            tableModel.fireTableDataChanged();
        }
    }

    private void onDeleteModel() {
        int row = table.getSelectedRow();
        if (row < 0) {
            Messages.showInfoMessage(mainPanel, "请先选择一个模型", "提示");
            return;
        }
        if (editingConfigs.size() <= 1) {
            Messages.showWarningDialog(mainPanel, "至少需要保留一个模型配置", "无法删除");
            return;
        }
        AiAgentSettings.ModelConfig config = editingConfigs.get(row);
        String displayName = config.name.isEmpty() ? config.modelName : config.name;
        int result = Messages.showYesNoDialog(
                mainPanel,
                "确定要删除模型 \"" + displayName + "\" 吗？",
                "确认删除",
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

    // ===== Configurable 接口 =====

    @Override
    public boolean isModified() {
        AiAgentSettings settings = AiAgentSettings.getInstance();

        if (!getDangerousCommandsText().equals(
                String.join("\n", settings.getDangerousCommands()))) return true;

        List<AiAgentSettings.ModelConfig> current = settings.getModelConfigs();
        if (editingConfigs.size() != current.size()) return true;
        for (int i = 0; i < editingConfigs.size(); i++) {
            AiAgentSettings.ModelConfig a = editingConfigs.get(i);
            AiAgentSettings.ModelConfig b = current.get(i);
            if (!a.name.equals(b.name) || !a.baseUrl.equals(b.baseUrl)
                    || !a.apiKey.equals(b.apiKey) || !a.modelName.equals(b.modelName)
                    || a.visionCapable != b.visionCapable
                    || a.contextWindowSize != b.contextWindowSize
                    || a.temperature != b.temperature
                    || a.maxTokens != b.maxTokens) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void apply() throws ConfigurationException {
        if (editingConfigs.isEmpty()) {
            throw new ConfigurationException("至少需要配置一个模型");
        }
        for (int i = 0; i < editingConfigs.size(); i++) {
            AiAgentSettings.ModelConfig config = editingConfigs.get(i);
            if (config.baseUrl.isEmpty()) {
                throw new ConfigurationException("模型 \"" + getDisplayName(config) + "\" 的 API 地址不能为空");
            }
            if (config.modelName.isEmpty()) {
                throw new ConfigurationException("模型 \"" + getDisplayName(config) + "\" 的模型名称不能为空");
            }
            if (!config.baseUrl.endsWith("/")) {
                config.baseUrl += "/";
            }
        }

        AiAgentSettings settings = AiAgentSettings.getInstance();
        settings.setModelConfigs(new ArrayList<>(editingConfigs));

        // 保存危险命令配置
        String dangerText = getDangerousCommandsText();
        String[] lines = dangerText.split("\n", -1);
        List<String> dangerList = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                dangerList.add(trimmed);
            }
        }
        settings.setDangerousCommands(dangerList);
        
        // 保持当前选中的模型索引（如果索引有效）
        int currentActiveIndex = settings.getActiveModelIndex();
        if (currentActiveIndex >= editingConfigs.size()) {
            settings.setActiveModelIndex(0);
        }

        settings.fireSettingsChanged();
    }

    @Override
    public void reset() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        editingConfigs.clear();
        for (AiAgentSettings.ModelConfig config : settings.getModelConfigs()) {
            editingConfigs.add(config.copy());
        }
        tableModel.fireTableDataChanged();
        if (!editingConfigs.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }

        // 加载危险命令配置
        dangerousCommandsArea.setText(String.join("\n", settings.getDangerousCommands()));
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        table = null;
        tableModel = null;
        editingConfigs = null;
    }

    private String getDisplayName(AiAgentSettings.ModelConfig config) {
        return config.name.isEmpty() ? (config.modelName.isEmpty() ? "未命名" : config.modelName) : config.name;
    }

    private String getDangerousCommandsText() {
        String text = dangerousCommandsArea.getText();
        return text == null ? "" : text;
    }

    // ===== 表格模型 =====

    private class ModelTableModel extends AbstractTableModel {
        private final String[] COLUMNS = {"显示名称", "API 地址", "模型名称"};

        @Override
        public int getRowCount() {
            return editingConfigs.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            AiAgentSettings.ModelConfig config = editingConfigs.get(rowIndex);
            switch (columnIndex) {
                case 0: return getDisplayName(config);
                case 1: return config.baseUrl;
                case 2: return config.modelName;
                default: return "";
            }
        }
    }

    // ===== 模型配置弹窗 =====

    private static class ModelConfigDialog extends DialogWrapper {
        private JTextField nameField;
        private JTextField baseUrlField;
        private JPasswordField apiKeyField;
        private JTextField modelNameField;
        private JSpinner contextWindowSpinner;
        private JSpinner temperatureSpinner;
        private JSpinner maxTokensSpinner;
        private JCheckBox visionCheckbox;
        private final AiAgentSettings.ModelConfig config;

        ModelConfigDialog(Component parent, String title, AiAgentSettings.ModelConfig config) {
            super(parent, true);
            this.config = config;
            setTitle(title);
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            nameField = new JTextField(config.name, 30);
            baseUrlField = new JTextField(config.baseUrl, 30);
            apiKeyField = new JPasswordField(config.apiKey, 30);
            modelNameField = new JTextField(config.modelName, 30);

            contextWindowSpinner = new JSpinner(new SpinnerNumberModel(config.contextWindowSize, 4096, 2000000, 1024));
            contextWindowSpinner.setPreferredSize(new Dimension(120, 28));

            temperatureSpinner = new JSpinner(new SpinnerNumberModel(config.temperature, 0.0, 2.0, 0.1));
            temperatureSpinner.setPreferredSize(new Dimension(120, 28));
            JSpinner.NumberEditor tempEditor = new JSpinner.NumberEditor(temperatureSpinner, "0.0");
            temperatureSpinner.setEditor(tempEditor);

            maxTokensSpinner = new JSpinner(new SpinnerNumberModel(config.maxTokens, 256, 128000, 256));
            maxTokensSpinner.setPreferredSize(new Dimension(120, 28));

            visionCheckbox = new JCheckBox("支持视觉 / 图片输入", config.visionCapable);

            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setPreferredSize(new Dimension(450, 350));

            panel.add(createFieldRow("显示名称:", nameField));
            panel.add(Box.createVerticalStrut(8));
            panel.add(createFieldRow("API 地址:", baseUrlField));
            panel.add(Box.createVerticalStrut(8));
            panel.add(createFieldRow("API Key:", apiKeyField));
            panel.add(Box.createVerticalStrut(8));
            panel.add(createFieldRow("模型名称:", modelNameField));
            panel.add(Box.createVerticalStrut(8));
            panel.add(createFieldRow("上下文窗口:", contextWindowSpinner));
            panel.add(Box.createVerticalStrut(8));
            panel.add(createFieldRow("温度:", temperatureSpinner));
            panel.add(Box.createVerticalStrut(8));
            panel.add(createFieldRow("最大输出:", maxTokensSpinner));
            panel.add(Box.createVerticalStrut(8));
            visionCheckbox.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(visionCheckbox);

            return panel;
        }

        private JPanel createFieldRow(String label, JComponent field) {
            JPanel row = new JPanel(new BorderLayout(8, 0));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel lbl = new JLabel(label);
            lbl.setPreferredSize(new Dimension(80, 28));
            lbl.setHorizontalAlignment(SwingConstants.RIGHT);
            row.add(lbl, BorderLayout.WEST);
            row.add(field, BorderLayout.CENTER);
            return row;
        }

        AiAgentSettings.ModelConfig getConfig() {
            config.name = nameField.getText().trim();
            config.baseUrl = baseUrlField.getText().trim();
            config.apiKey = new String(apiKeyField.getPassword()).trim();
            config.modelName = modelNameField.getText().trim();
            config.contextWindowSize = (Integer) contextWindowSpinner.getValue();
            config.temperature = (Double) temperatureSpinner.getValue();
            config.maxTokens = (Integer) maxTokensSpinner.getValue();
            config.visionCapable = visionCheckbox.isSelected();
            return config;
        }
    }
}
