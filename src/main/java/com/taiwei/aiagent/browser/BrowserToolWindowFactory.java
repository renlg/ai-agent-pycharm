package com.taiwei.aiagent.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

public class BrowserToolWindowFactory implements ToolWindowFactory {

    private static final Logger LOG = Logger.getInstance(BrowserToolWindowFactory.class);

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        JComponent component;

        if (isJcefAvailable()) {
            component = new BrowserToolPanel(project);
        } else {
            LOG.warn("JCEF is not available, browser tool requires JCEF");
            component = createFallbackPanel();
        }

        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(component, "", false);
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
                "<html><center><h3>JCEF 不可用</h3>" +
                "<p>浏览器工具需要 JCEF 支持。</p>" +
                "<p>请确保使用的是 JetBrains Runtime (JBR)。</p>" +
                "</center></html>",
                SwingConstants.CENTER
        );
        label.setBorder(BorderFactory.createEmptyBorder(40, 20, 40, 20));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }
}
