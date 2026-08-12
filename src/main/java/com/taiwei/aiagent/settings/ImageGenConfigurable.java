package com.taiwei.aiagent.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * 图像生成设置页面
 * Settings → Tools → 太微 → 图像生成
 */
public class ImageGenConfigurable implements Configurable {

    private JPanel mainPanel;
    private JTextField baseUrlField;
    private JPasswordField apiKeyField;
    private JTextField modelNameField;
    private JComboBox<String> imageSizeCombo;
    private JSpinner imageCountSpinner;

    private static final String[] SIZE_OPTIONS = {
            "256x256", "512x512", "1024x1024", "1792x1024", "1024x1792"
    };

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "图像生成";
    }

    @Override
    public @Nullable JComponent createComponent() {
        mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(JBUI.Borders.empty(12));

        JLabel titleLabel = new JLabel("图像生成模型配置");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(titleLabel);
        mainPanel.add(Box.createVerticalStrut(10));

        JTextArea hintArea = new JTextArea(
                "配置 OpenAI 兼容的图像生成 API（如 DALL-E 3、Stable Diffusion 等）。\n" +
                "Agent 可调用 generate_image 工具，根据文字描述生成图像并在对话中内联展示，支持点击下载。"
        );
        hintArea.setEditable(false);
        hintArea.setFont(new Font("Dialog", Font.PLAIN, 12));
        hintArea.setBackground(UIManager.getColor("Panel.background"));
        hintArea.setForeground(UIManager.getColor("Label.disabledForeground"));
        hintArea.setBorder(JBUI.Borders.empty(0, 0, 12, 0));
        hintArea.setLineWrap(true);
        hintArea.setWrapStyleWord(true);
        hintArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(hintArea);

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);

        // API base URL
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        JLabel baseUrlLabel = new JLabel("API 地址:");
        baseUrlLabel.setPreferredSize(new Dimension(100, 28));
        formPanel.add(baseUrlLabel, gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        baseUrlField = new JTextField(30);
        baseUrlField.setToolTipText("例如: https://api.openai.com/v1/");
        formPanel.add(baseUrlField, gbc);

        // API Key
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        formPanel.add(new JLabel("API Key:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        apiKeyField = new JPasswordField(30);
        formPanel.add(apiKeyField, gbc);

        // Model name
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        formPanel.add(new JLabel("模型名称:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        modelNameField = new JTextField(30);
        modelNameField.setToolTipText("例如: dall-e-3、dall-e-2");
        formPanel.add(modelNameField, gbc);

        // Image size
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
        formPanel.add(new JLabel("默认尺寸:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        imageSizeCombo = new JComboBox<>(SIZE_OPTIONS);
        formPanel.add(imageSizeCombo, gbc);

        // Image count
        gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 0;
        formPanel.add(new JLabel("默认数量:"), gbc);
        gbc.gridx = 1; gbc.weightx = 0;
        imageCountSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 4, 1));
        imageCountSpinner.setPreferredSize(new Dimension(80, 28));
        formPanel.add(imageCountSpinner, gbc);

        mainPanel.add(formPanel);
        mainPanel.add(Box.createVerticalGlue());

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        ImageGenSettings s = ImageGenSettings.getInstance();
        return !baseUrlField.getText().trim().equals(s.getBaseUrl())
                || !new String(apiKeyField.getPassword()).trim().equals(s.getApiKey())
                || !modelNameField.getText().trim().equals(s.getModelName())
                || !String.valueOf(imageSizeCombo.getSelectedItem()).equals(s.getImageSize())
                || (Integer) imageCountSpinner.getValue() != s.getImageCount();
    }

    @Override
    public void apply() throws ConfigurationException {
        ImageGenSettings s = ImageGenSettings.getInstance();
        String baseUrl = baseUrlField.getText().trim();
        if (!baseUrl.isEmpty() && !baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        s.setBaseUrl(baseUrl);
        s.setApiKey(new String(apiKeyField.getPassword()).trim());
        s.setModelName(modelNameField.getText().trim());
        s.setImageSize(String.valueOf(imageSizeCombo.getSelectedItem()));
        s.setImageCount((Integer) imageCountSpinner.getValue());
    }

    @Override
    public void reset() {
        ImageGenSettings s = ImageGenSettings.getInstance();
        baseUrlField.setText(s.getBaseUrl());
        apiKeyField.setText(s.getApiKey());
        modelNameField.setText(s.getModelName());

        String size = s.getImageSize();
        for (int i = 0; i < imageSizeCombo.getItemCount(); i++) {
            if (imageSizeCombo.getItemAt(i).equals(size)) {
                imageSizeCombo.setSelectedIndex(i);
                break;
            }
        }
        imageCountSpinner.setValue(s.getImageCount());
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        baseUrlField = null;
        apiKeyField = null;
        modelNameField = null;
        imageSizeCombo = null;
        imageCountSpinner = null;
    }
}
