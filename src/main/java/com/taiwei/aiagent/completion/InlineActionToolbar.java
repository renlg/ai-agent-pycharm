package com.taiwei.aiagent.completion;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.taiwei.aiagent.ui.EditorChatBridge;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class InlineActionToolbar {

    private static final String TOOL_WINDOW_ID = "\u592a\u5fae";
    private static final int MAX_CODE_CHARS = 15000;

    /** "\u6dfb\u52a0\u5230\u5f53\u524d\u5bf9\u8bdd" \u2014 \u628a\u9009\u4e2d\u4ee3\u7801\u63d2\u5165\u804a\u5929\u8f93\u5165\u6846\uff0c\u800c\u975e\u76f4\u63a5\u53d1\u9001\u3002 */
    private static final String ADD_TO_CHAT_LABEL = "\u6dfb\u52a0\u5230\u5f53\u524d\u5bf9\u8bdd";

    private final Editor editor;
    private final Project project;
    private volatile String selectedText;
    private final String filePath;
    private final String language;

    private JBPopup toolbarPopup;
    private Balloon resultBalloon;
    private volatile boolean processing = false;

    public InlineActionToolbar(Editor editor, Project project, String selectedText,
                               String filePath, String language) {
        this.editor = editor;
        this.project = project;
        this.selectedText = selectedText;
        this.filePath = filePath;
        this.language = language;
    }

    public void show() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (editor.isDisposed()) return;
            createAndShowToolbar();
        });
    }

    public void hide() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (toolbarPopup != null) {
                if (!toolbarPopup.isDisposed()) {
                    toolbarPopup.cancel();
                }
                toolbarPopup = null;
            }
            if (resultBalloon != null && !resultBalloon.isDisposed()) {
                resultBalloon.hide();
                resultBalloon = null;
            }
        });
    }

    public void updateSelection(String newText) {
        this.selectedText = newText;
    }

    private void createAndShowToolbar() {
        if (editor.isDisposed()) return;

        JPanel toolbarPanel = createToolbarPanel();

        // A lightweight JBPopup automatically gets native rounded corners and a drop
        // shadow (see AbstractPopup/WindowRoundedCornersManager), matching the same
        // modern look used by quick-doc and intention popups across the IDE.
        toolbarPopup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(toolbarPanel, null)
                .setRequestFocus(false)
                .setFocusable(false)
                .setResizable(false)
                .setMovable(false)
                .setCancelOnClickOutside(false)
                .setCancelOnOtherWindowOpen(false)
                .setCancelOnWindowDeactivation(false)
                .setCancelKeyEnabled(false)
                .setShowShadow(true)
                .setShowBorder(true)
                .createPopup();

        Point location = calculateScreenPosition(toolbarPanel.getPreferredSize());
        if (location != null) {
            toolbarPopup.showInScreenCoordinates(editor.getContentComponent(), location);
        } else {
            toolbarPopup.cancel();
            toolbarPopup = null;
        }
    }

    private JPanel createToolbarPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0));
        panel.setOpaque(true);
        panel.setBackground(JBUI.CurrentTheme.Popup.BACKGROUND);
        panel.setBorder(JBUI.Borders.empty(4, 6));

        JButton addToChatBtn = createAddToChatButton();
        panel.add(addToChatBtn);

        return panel;
    }

    private JButton createAddToChatButton() {
        JButton button = new RoundedHoverButton(ADD_TO_CHAT_LABEL);
        button.setFont(JBUI.Fonts.label(12f));
        button.setForeground(UIUtil.getLabelForeground());
        button.setMargin(JBUI.insets(4, 10));
        button.setFocusable(false);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        button.addActionListener(e -> {
            if (processing) return;
            addToCurrentChat();
        });

        return button;
    }

    /** Flat text button that paints a rounded, IDE-themed highlight on hover instead of a square background. */
    private static class RoundedHoverButton extends JButton {
        private boolean hovered = false;

        RoundedHoverButton(String text) {
            super(text);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (hovered) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(JBUI.CurrentTheme.ActionButton.hoverBackground());
                int arc = JBUI.scale(6);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    private Point calculateScreenPosition(Dimension popupSize) {
        int selectionEnd = editor.getCaretModel().getOffset();
        if (selectionEnd <= 0) return null;

        com.intellij.openapi.editor.LogicalPosition logPos =
                editor.offsetToLogicalPosition(selectionEnd - 1);
        Point point = editor.visualPositionToXY(
                new com.intellij.openapi.editor.VisualPosition(logPos.line, logPos.column + 1));

        JComponent contentComponent = editor.getContentComponent();
        Point editorScreenLoc = contentComponent.getLocationOnScreen();

        int x = editorScreenLoc.x + point.x + 20;
        int y = editorScreenLoc.y + point.y + editor.getLineHeight() + 4;

        Dimension windowSize = popupSize != null ? popupSize : new Dimension(300, 32);
        Rectangle visibleArea = editor.getScrollingModel().getVisibleArea();
        int visibleRight = editorScreenLoc.x + visibleArea.x + visibleArea.width;
        int visibleBottom = editorScreenLoc.y + visibleArea.y + visibleArea.height;

        if (x + windowSize.width > visibleRight) {
            x = visibleRight - windowSize.width - 10;
        }
        if (y + windowSize.height > visibleBottom) {
            y = editorScreenLoc.y + point.y - windowSize.height - 4;
        }

        return new Point(Math.max(editorScreenLoc.x + 4, x), Math.max(editorScreenLoc.y + 4, y));
    }

    /** 把选中代码插入聊天输入框（不发送），供用户补充说明后再提交。 */
    private void addToCurrentChat() {
        String code = selectedText;
        if (code == null || code.isEmpty()) return;
        if (code.length() > MAX_CODE_CHARS) {
            code = code.substring(0, MAX_CODE_CHARS) + "\n// ...(代码过长，已截断)";
        }
        String text = "```" + language + "\n" + code + "\n```";

        ToolWindow tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        Runnable deliver = () -> EditorChatBridge.getInstance(project).insertToInput(text);
        if (tw != null) {
            tw.activate(deliver, true);
        } else {
            deliver.run();
        }
        hide();
    }
}
