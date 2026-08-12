package com.taiwei.aiagent.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.taiwei.aiagent.util.I18nUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型配置页面
 * Settings → Tools → 太微 → 模型
 */
public class ModelConfigurable implements Configurable {

    private JPanel mainPanel;
    private ModelTableModel tableModel;
    private JBTable table;

    private List<AiAgentSettings.ModelConfig> editingConfigs;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return I18nUtil.getMessage("model.title");
    }

    @Override
    public @Nullable JComponent createComponent() {
        editingConfigs = new ArrayList<>();

        // ===== 模型列表表格 =====
        tableModel = new ModelTableModel();
        table = new JBTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(120);
        table.getColumnModel().getColumn(1).setPreferredWidth(250);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        table.getColumnModel().getColumn(3).setPreferredWidth(80);
        table.setRowHeight(32);

        JBScrollPane scrollPane = new JBScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(700, 240));
        scrollPane.setMinimumSize(new Dimension(500, 180));

        // 按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        JButton addBtn = new JButton(I18nUtil.getMessage("model.addBtn"));
        JButton editBtn = new JButton(I18nUtil.getMessage("model.editBtn"));
        JButton deleteBtn = new JButton(I18nUtil.getMessage("model.deleteBtn"));

        addBtn.addActionListener(e -> onAddModel());
        editBtn.addActionListener(e -> onEditModel());
        deleteBtn.addActionListener(e -> onDeleteModel());

        buttonPanel.add(addBtn);
        buttonPanel.add(editBtn);
        buttonPanel.add(deleteBtn);

        // ===== 模型配置区域 =====
        JPanel modelSection = new JPanel(new BorderLayout(0, 8));
        JLabel sectionLabel = new JLabel(I18nUtil.getMessage("model.modelConfigTitle"));
        sectionLabel.setFont(sectionLabel.getFont().deriveFont(Font.BOLD, 14f));
        modelSection.add(sectionLabel, BorderLayout.NORTH);
        modelSection.add(scrollPane, BorderLayout.CENTER);
        modelSection.add(buttonPanel, BorderLayout.SOUTH);

        // ===== 组装主面板 =====
        mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(JBUI.Borders.empty(12));

        modelSection.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(modelSection);
        mainPanel.add(Box.createVerticalGlue());

        reset();
        return mainPanel;
    }

    // ===== 按钮事件 =====

    private void onAddModel() {
        ModelConfigDialog dialog = new ModelConfigDialog(mainPanel, I18nUtil.getMessage("model.dialog.addTitle"), new AiAgentSettings.ModelConfig());
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
            Messages.showInfoMessage(mainPanel, I18nUtil.getMessage("model.selectModelFirst"), I18nUtil.getMessage("model.selectModelFirst"));
            return;
        }
        AiAgentSettings.ModelConfig config = editingConfigs.get(row);
        ModelConfigDialog dialog = new ModelConfigDialog(mainPanel, I18nUtil.getMessage("model.dialog.editTitle"), config.copy());
        if (dialog.showAndGet()) {
            AiAgentSettings.ModelConfig updated = dialog.getConfig();
            editingConfigs.set(row, updated);
            tableModel.fireTableDataChanged();
        }
    }

    private void onDeleteModel() {
        int row = table.getSelectedRow();
        if (row < 0) {
            Messages.showInfoMessage(mainPanel, I18nUtil.getMessage("model.selectModelFirst"), I18nUtil.getMessage("model.selectModelFirst"));
            return;
        }
        if (editingConfigs.size() <= 1) {
            Messages.showWarningDialog(mainPanel, I18nUtil.getMessage("model.atLeastOneModel"), I18nUtil.getMessage("model.cannotDelete"));
            return;
        }
        AiAgentSettings.ModelConfig config = editingConfigs.get(row);
        String displayName = config.name.isEmpty() ? config.modelName : config.name;
        int result = Messages.showYesNoDialog(
                mainPanel,
                String.format(I18nUtil.getMessage("model.deleteConfirmMessage"), displayName),
                I18nUtil.getMessage("model.confirmDelete"),
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

        List<AiAgentSettings.ModelConfig> current = settings.getModelConfigs();
        if (editingConfigs.size() != current.size()) return true;
        for (int i = 0; i < editingConfigs.size(); i++) {
            AiAgentSettings.ModelConfig a = editingConfigs.get(i);
            AiAgentSettings.ModelConfig b = current.get(i);
            if (!a.name.equals(b.name) || !a.baseUrl.equals(b.baseUrl)
                    || !a.apiKey.equals(b.apiKey) || !a.modelName.equals(b.modelName)
                    || a.compressionThreshold != b.compressionThreshold
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
            throw new ConfigurationException(I18nUtil.getMessage("model.atLeastOneModelError"));
        }
        for (int i = 0; i < editingConfigs.size(); i++) {
            AiAgentSettings.ModelConfig config = editingConfigs.get(i);
            if (config.baseUrl.isEmpty()) {
                throw new ConfigurationException(String.format(I18nUtil.getMessage("model.apiUrlRequired"), getDisplayName(config)));
            }
            if (config.modelName.isEmpty()) {
                throw new ConfigurationException(String.format(I18nUtil.getMessage("model.modelNameRequired"), getDisplayName(config)));
            }
            if (!config.baseUrl.endsWith("/")) {
                config.baseUrl += "/";
            }
        }

        AiAgentSettings settings = AiAgentSettings.getInstance();
        settings.setModelConfigs(new ArrayList<>(editingConfigs));

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
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        table = null;
        tableModel = null;
        editingConfigs = null;
    }

    private String getDisplayName(AiAgentSettings.ModelConfig config) {
        return config.name.isEmpty() ? config.modelName : config.name;
    }

    // ===== Table Model =====

    private class ModelTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"显示名称", "API 地址", "模型名称", "视觉支持"};

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
            AiAgentSettings.ModelConfig config = editingConfigs.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return config.name.isEmpty() ? config.modelName : config.name;
                case 1:
                    return config.baseUrl;
                case 2:
                    return config.modelName;
                case 3:
                    return config.visionCapable ? "是" : "否";
                default:
                    return "";
            }
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }
    }

    // ===== Dialog =====

    private static class ModelConfigDialog extends DialogWrapper {
        private JTextField nameField;
        private JTextField baseUrlField;
        private JPasswordField apiKeyField;
        private JTextField modelNameField;
        private JSpinner compressionThresholdSpinner;
        private JSpinner contextWindowSpinner;
        private JSpinner temperatureSpinner;
        private JSpinner maxTokensSpinner;
        private JCheckBox visionCheckbox;
        private AiAgentSettings.ModelConfig config;

        protected ModelConfigDialog(Component parent, String title, AiAgentSettings.ModelConfig config) {
            super(parent, true);
            this.config = config;
            setTitle(title);
            init();
        }

        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.insets = new Insets(4, 4, 4, 4);

            gbc.gridx = 0;
            gbc.gridy = 0;
            panel.add(new JLabel(I18nUtil.getMessage("model.dialog.nameLabel")), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            nameField = new JTextField(config.name, 30);
            panel.add(nameField, gbc);

            gbc.gridx = 0;
            gbc.gridy = 1;
            gbc.weightx = 0;
            panel.add(new JLabel(I18nUtil.getMessage("model.dialog.baseUrlLabel")), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            baseUrlField = new JTextField(config.baseUrl, 30);
            panel.add(baseUrlField, gbc);

            gbc.gridx = 0;
            gbc.gridy = 2;
            gbc.weightx = 0;
            panel.add(new JLabel(I18nUtil.getMessage("model.dialog.apiKeyLabel")), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            apiKeyField = new JPasswordField(config.apiKey, 30);
            panel.add(apiKeyField, gbc);

            gbc.gridx = 0;
            gbc.gridy = 3;
            gbc.weightx = 0;
            panel.add(new JLabel(I18nUtil.getMessage("model.dialog.modelNameLabel")), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            modelNameField = new JTextField(config.modelName, 30);
            panel.add(modelNameField, gbc);

            gbc.gridx = 0;
            gbc.gridy = 4;
            gbc.weightx = 0;
            panel.add(new JLabel("上下文窗口"), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            contextWindowSpinner = new JSpinner(new SpinnerNumberModel(config.contextWindowSize, 4096, 2000000, 1024));
            contextWindowSpinner.setPreferredSize(new Dimension(140, 28));
            panel.add(contextWindowSpinner, gbc);

            gbc.gridx = 0;
            gbc.gridy = 5;
            gbc.weightx = 0;
            panel.add(new JLabel("温度"), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            temperatureSpinner = new JSpinner(new SpinnerNumberModel(config.temperature, 0.0, 2.0, 0.1));
            temperatureSpinner.setPreferredSize(new Dimension(140, 28));
            JSpinner.NumberEditor tempEditor = new JSpinner.NumberEditor(temperatureSpinner, "0.0");
            temperatureSpinner.setEditor(tempEditor);
            panel.add(temperatureSpinner, gbc);

            gbc.gridx = 0;
            gbc.gridy = 6;
            gbc.weightx = 0;
            panel.add(new JLabel("最大输出"), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            maxTokensSpinner = new JSpinner(new SpinnerNumberModel(config.maxTokens, 256, 128000, 256));
            maxTokensSpinner.setPreferredSize(new Dimension(140, 28));
            panel.add(maxTokensSpinner, gbc);

            gbc.gridx = 0;
            gbc.gridy = 7;
            gbc.weightx = 0;
            panel.add(new JLabel("压缩阈值 (%)"), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            compressionThresholdSpinner = new JSpinner(new SpinnerNumberModel(config.compressionThreshold, 0, 100, 5));
            compressionThresholdSpinner.setPreferredSize(new Dimension(140, 28));
            panel.add(compressionThresholdSpinner, gbc);

            gbc.gridx = 0;
            gbc.gridy = 8;
            gbc.weightx = 0;
            panel.add(new JLabel("视觉支持"), gbc);

            gbc.gridx = 1;
            gbc.weightx = 1.0;
            visionCheckbox = new JCheckBox("支持视觉 / 图片输入", config.visionCapable);
            panel.add(visionCheckbox, gbc);

            return panel;
        }

        @Override
        protected void doOKAction() {
            if (nameField.getText().trim().isEmpty()) {
                Messages.showErrorDialog(getContentPanel(), I18nUtil.getMessage("model.dialog.nameRequired"), "Error");
                return;
            }
            if (baseUrlField.getText().trim().isEmpty()) {
                Messages.showErrorDialog(getContentPanel(), I18nUtil.getMessage("model.dialog.baseUrlRequired"), "Error");
                return;
            }
            if (apiKeyField.getText().trim().isEmpty()) {
                Messages.showErrorDialog(getContentPanel(), I18nUtil.getMessage("model.dialog.apiKeyRequired"), "Error");
                return;
            }
            if (modelNameField.getText().trim().isEmpty()) {
                Messages.showErrorDialog(getContentPanel(), I18nUtil.getMessage("model.dialog.modelNameRequired"), "Error");
                return;
            }

            config.name = nameField.getText().trim();
            config.baseUrl = baseUrlField.getText().trim();
            config.apiKey = new String(apiKeyField.getPassword()).trim();
            config.modelName = modelNameField.getText().trim();
            config.contextWindowSize = (Integer) contextWindowSpinner.getValue();
            config.temperature = (Double) temperatureSpinner.getValue();
            config.maxTokens = (Integer) maxTokensSpinner.getValue();
            config.compressionThreshold = (Integer) compressionThresholdSpinner.getValue();
            config.visionCapable = visionCheckbox.isSelected();

            super.doOKAction();
        }

        public AiAgentSettings.ModelConfig getConfig() {
            return config;
        }
    }
}
