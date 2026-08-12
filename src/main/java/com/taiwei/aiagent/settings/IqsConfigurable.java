package com.taiwei.aiagent.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * 网络搜索设置页面
 * Settings → Tools → 太微 → 网络搜索
 */
public class IqsConfigurable implements Configurable {

    private JPanel mainPanel;
    private JComboBox<String> searchEngineCombo;
    private JPanel iqsConfigPanel;
    private JPanel serpApiConfigPanel;
    private JPasswordField accessKeyIdField;
    private JPasswordField accessKeySecretField;
    private JPasswordField serpApiKeyField;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "网络搜索";
    }

    @Override
    public @Nullable JComponent createComponent() {
        mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBorder(JBUI.Borders.empty(12));

        // 标题
        JLabel titleLabel = new JLabel("搜索引擎配置");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainPanel.add(titleLabel);
        mainPanel.add(Box.createVerticalStrut(8));

        // 搜索引擎选择
        JPanel enginePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        enginePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel engineLabel = new JLabel("搜索引擎: ");
        enginePanel.add(engineLabel);

        searchEngineCombo = new JComboBox<>(new String[]{"低成本默认（DuckDuckGo）", "阿里云 IQS", "SerpAPI"});
        searchEngineCombo.addActionListener(e -> onEngineChanged());
        enginePanel.add(searchEngineCombo);
        mainPanel.add(enginePanel);
        mainPanel.add(Box.createVerticalStrut(12));

        // IQS 配置面板（仅在选择阿里云 IQS 时显示）
        iqsConfigPanel = new JPanel();
        iqsConfigPanel.setLayout(new BoxLayout(iqsConfigPanel, BoxLayout.Y_AXIS));
        iqsConfigPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextArea hintArea = new JTextArea(
                "配置阿里云 AccessKey 后，Agent 可使用阿里云 IQS 网络搜索工具获取实时信息。\n" +
                "请前往阿里云控制台（RAM 访问控制）创建 AccessKey，并开通 IQS 服务。\n" +
                "需确保 AK/SK 具有 AliyunIQSFullAccess 权限。"
        );
        hintArea.setEditable(false);
        hintArea.setFont(new Font("Dialog", Font.PLAIN, 12));
        hintArea.setBackground(UIManager.getColor("Panel.background"));
        hintArea.setForeground(UIManager.getColor("Label.disabledForeground"));
        hintArea.setBorder(JBUI.Borders.empty(0, 0, 16, 0));
        hintArea.setLineWrap(true);
        hintArea.setWrapStyleWord(true);
        hintArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        iqsConfigPanel.add(hintArea);

        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        JLabel akIdLabel = new JLabel("AccessKey ID:");
        akIdLabel.setPreferredSize(new Dimension(120, 28));
        formPanel.add(akIdLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        accessKeyIdField = new JPasswordField(30);
        formPanel.add(accessKeyIdField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        JLabel akSecretLabel = new JLabel("AccessKey Secret:");
        akSecretLabel.setPreferredSize(new Dimension(120, 28));
        formPanel.add(akSecretLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        accessKeySecretField = new JPasswordField(30);
        formPanel.add(accessKeySecretField, gbc);

        iqsConfigPanel.add(formPanel);
        mainPanel.add(iqsConfigPanel);

        // SerpAPI 配置面板（仅在选择 SerpAPI 时显示）
        serpApiConfigPanel = new JPanel();
        serpApiConfigPanel.setLayout(new BoxLayout(serpApiConfigPanel, BoxLayout.Y_AXIS));
        serpApiConfigPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextArea serpHintArea = new JTextArea(
                "SerpAPI 提供基于 Google 的网络搜索服务。\n" +
                "请前往 https://serpapi.com 注册账号并获取 API Key。\n" +
                "免费版每月有 100 次搜索额度。"
        );
        serpHintArea.setEditable(false);
        serpHintArea.setFont(new Font("Dialog", Font.PLAIN, 12));
        serpHintArea.setBackground(UIManager.getColor("Panel.background"));
        serpHintArea.setForeground(UIManager.getColor("Label.disabledForeground"));
        serpHintArea.setBorder(JBUI.Borders.empty(0, 0, 16, 0));
        serpHintArea.setLineWrap(true);
        serpHintArea.setWrapStyleWord(true);
        serpHintArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        serpApiConfigPanel.add(serpHintArea);

        JPanel serpFormPanel = new JPanel(new GridBagLayout());
        serpFormPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints sgbc = new GridBagConstraints();
        sgbc.fill = GridBagConstraints.HORIZONTAL;
        sgbc.insets = new Insets(4, 4, 4, 4);

        sgbc.gridx = 0;
        sgbc.gridy = 0;
        sgbc.weightx = 0;
        JLabel serpKeyLabel = new JLabel("SerpAPI Key:");
        serpKeyLabel.setPreferredSize(new Dimension(120, 28));
        serpFormPanel.add(serpKeyLabel, sgbc);

        sgbc.gridx = 1;
        sgbc.weightx = 1.0;
        serpApiKeyField = new JPasswordField(30);
        serpFormPanel.add(serpApiKeyField, sgbc);

        serpApiConfigPanel.add(serpFormPanel);
        mainPanel.add(serpApiConfigPanel);
        mainPanel.add(Box.createVerticalGlue());

        reset();
        return mainPanel;
    }

    private void onEngineChanged() {
        int selected = searchEngineCombo.getSelectedIndex();
        iqsConfigPanel.setVisible(selected == 1);
        serpApiConfigPanel.setVisible(selected == 2);
    }

    @Override
    public boolean isModified() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        int selected = searchEngineCombo.getSelectedIndex();
        String currentType = selected == 1 ? "ALIYUN_IQS" : (selected == 2 ? "SERPAPI" : "LOW_COST");
        if (!currentType.equals(settings.getSearchEngineType())) {
            return true;
        }
        if (selected == 1) {
            IqsSettings iqsSettings = IqsSettings.getInstance();
            return !getAccessKeyIdText().equals(iqsSettings.getAccessKeyId())
                    || !getAccessKeySecretText().equals(iqsSettings.getAccessKeySecret());
        }
        if (selected == 2) {
            IqsSettings iqsSettings = IqsSettings.getInstance();
            return !getSerpApikeyText().equals(iqsSettings.getSerpApiKey());
        }
        return false;
    }

    @Override
    public void apply() throws ConfigurationException {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        int selected = searchEngineCombo.getSelectedIndex();

        if (selected == 1) {
            String akId = getAccessKeyIdText().trim();
            String akSecret = getAccessKeySecretText().trim();

            if (akId.isEmpty() && akSecret.isEmpty()) {
                IqsSettings iqsSettings = IqsSettings.getInstance();
                iqsSettings.setAccessKeyId("");
                iqsSettings.setAccessKeySecret("");
                settings.setSearchEngineType("ALIYUN_IQS");
                return;
            }

            if (akId.isEmpty()) {
                throw new ConfigurationException("AccessKey ID 不能为空");
            }
            if (akSecret.isEmpty()) {
                throw new ConfigurationException("AccessKey Secret 不能为空");
            }

            IqsSettings iqsSettings = IqsSettings.getInstance();
            iqsSettings.setAccessKeyId(akId);
            iqsSettings.setAccessKeySecret(akSecret);
            settings.setSearchEngineType("ALIYUN_IQS");
        } else if (selected == 2) {
            String serpKey = getSerpApikeyText().trim();
            if (serpKey.isEmpty()) {
                throw new ConfigurationException("SerpAPI Key 不能为空");
            }
            IqsSettings iqsSettings = IqsSettings.getInstance();
            iqsSettings.setSerpApiKey(serpKey);
            settings.setSearchEngineType("SERPAPI");
        } else {
            settings.setSearchEngineType("LOW_COST");
        }
    }

    @Override
    public void reset() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        String engineType = settings.getSearchEngineType();
        int selectedIndex = "ALIYUN_IQS".equals(engineType) ? 1 : ("SERPAPI".equals(engineType) ? 2 : 0);
        searchEngineCombo.setSelectedIndex(selectedIndex);
        iqsConfigPanel.setVisible(selectedIndex == 1);
        serpApiConfigPanel.setVisible(selectedIndex == 2);

        IqsSettings iqsSettings = IqsSettings.getInstance();
        accessKeyIdField.setText(iqsSettings.getAccessKeyId());
        accessKeySecretField.setText(iqsSettings.getAccessKeySecret());
        serpApiKeyField.setText(iqsSettings.getSerpApiKey());
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        searchEngineCombo = null;
        iqsConfigPanel = null;
        serpApiConfigPanel = null;
        accessKeyIdField = null;
        accessKeySecretField = null;
        serpApiKeyField = null;
    }

    private String getAccessKeyIdText() {
        char[] password = accessKeyIdField.getPassword();
        return password != null ? new String(password) : "";
    }

    private String getAccessKeySecretText() {
        char[] password = accessKeySecretField.getPassword();
        return password != null ? new String(password) : "";
    }

    private String getSerpApikeyText() {
        char[] password = serpApiKeyField.getPassword();
        return password != null ? new String(password) : "";
    }
}
