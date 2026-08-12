package com.taiwei.aiagent.window;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.taiwei.aiagent.ui.ChatPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public class ChatWindowFactory implements ToolWindowFactory {

    private static final Logger LOG = Logger.getInstance(ChatWindowFactory.class);

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        JComponent component;

        if (isJcefAvailable()) {
            component = new ChatPanel(project);
        } else {
            LOG.warn("JCEF is not available, chat panel requires JCEF to function");
            component = createFallbackPanel();
        }

        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(component, "Chat", false);
        if (component instanceof ChatPanel) {
            // Without this, ChatPanel.dispose() is never called: the JCEF browser, JS query,
            // settings-change listener and flush executor would all leak on tool-window close.
            content.setDisposer((ChatPanel) component);
        }
        toolWindow.getContentManager().addContent(content);
    }

    private static boolean isJcefAvailable() {
        try {
            Class.forName("com.intellij.ui.jcef.JBCefBrowser");
            return com.intellij.ui.jcef.JBCefApp.isSupported();
        } catch (Exception e) {
            return false;
        }
    }

    private static JComponent createFallbackPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel(
                "<html><center><h3>JCEF \u4e0d\u53ef\u7528</h3>" +
                "<p>\u592a\u5fae\u804a\u5929\u529f\u80fd\u9700\u8981 JCEF \u652f\u6301\u3002</p>" +
                "<p>\u8bf7\u786e\u4fdd\u4f7f\u7528\u7684\u662f JetBrains Runtime (JBR)\u3002</p>" +
                "</center></html>",
                SwingConstants.CENTER
        );
        label.setBorder(BorderFactory.createEmptyBorder(40, 20, 40, 20));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }
}
