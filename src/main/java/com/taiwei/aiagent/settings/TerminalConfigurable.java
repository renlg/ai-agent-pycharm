package com.taiwei.aiagent.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.taiwei.aiagent.util.I18nUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 终端配置页面
 * Settings → Tools → 太微 → 终端
 */
public class TerminalConfigurable implements Configurable {

    private JPanel mainPanel;
    private JTextArea dangerousCommandsArea;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return I18nUtil.getMessage("terminal.title");
    }

    @Override
    public @Nullable JComponent createComponent() {
        mainPanel = new JPanel(new BorderLayout(0, 8));
        mainPanel.setBorder(JBUI.Borders.empty(10));

        // ===== 顶部：标题 + 提示 =====
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));

        JLabel dangerLabel = new JLabel(I18nUtil.getMessage("terminal.dangerousCommandsTitle"));
        dangerLabel.setFont(dangerLabel.getFont().deriveFont(Font.BOLD, 14f));
        dangerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(dangerLabel);
        topPanel.add(Box.createVerticalStrut(8));

        JTextArea hintArea = new JTextArea(I18nUtil.getMessage("terminal.dangerousCommandsHint"));
        hintArea.setEditable(false);
        hintArea.setFont(new Font("Dialog", Font.PLAIN, 12));
        hintArea.setBackground(JBColor.background());
        hintArea.setForeground(new JBColor(Color.GRAY, new Color(0x888888)));
        hintArea.setBorder(JBUI.Borders.empty(0, 0, 8, 0));
        hintArea.setLineWrap(true);
        hintArea.setWrapStyleWord(true);
        hintArea.setAlignmentX(Component.LEFT_ALIGNMENT);
        topPanel.add(hintArea);

        mainPanel.add(topPanel, BorderLayout.NORTH);

        // ===== 中间：危险命令编辑区域（5行） =====
        dangerousCommandsArea = new JTextArea(5, 60);
        Font monoFont = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        dangerousCommandsArea.setFont(monoFont);
        dangerousCommandsArea.setLineWrap(false);
        dangerousCommandsArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(8)
        ));
        // 固定高度为5行，防止被布局拉伸
        FontMetrics fm = mainPanel.getFontMetrics(monoFont);
        int fixedHeight = fm.getHeight() * 5 + 20;
        dangerousCommandsArea.setMaximumSize(new Dimension(Integer.MAX_VALUE, fixedHeight));
        dangerousCommandsArea.setPreferredSize(new Dimension(500, fixedHeight));
        mainPanel.add(dangerousCommandsArea, BorderLayout.CENTER);

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        return !getDangerousCommandsText().equals(
                String.join("\n", settings.getDangerousCommands()));
    }

    @Override
    public void apply() throws ConfigurationException {
        AiAgentSettings settings = AiAgentSettings.getInstance();
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
    }

    @Override
    public void reset() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        dangerousCommandsArea.setText(String.join("\n", settings.getDangerousCommands()));
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        dangerousCommandsArea = null;
    }

    private String getDangerousCommandsText() {
        String text = dangerousCommandsArea.getText();
        return text == null ? "" : text;
    }
}
