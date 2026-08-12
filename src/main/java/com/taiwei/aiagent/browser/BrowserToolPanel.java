package com.taiwei.aiagent.browser;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Chrome 风格的浏览器面板：扁平导航按钮 + 圆角 Omnibox 地址栏，回车导航。
 */
public class BrowserToolPanel extends JPanel implements Disposable {

    private static final JBColor TOOLBAR_BG = new JBColor(new Color(0xF1F3F4), new Color(0x2B2D30));
    private static final JBColor OMNIBOX_BG = new JBColor(new Color(0xFFFFFF), new Color(0x1E1F22));
    private static final JBColor OMNIBOX_BORDER = new JBColor(new Color(0xDADCE0), new Color(0x43454A));
    private static final JBColor OMNIBOX_FOCUS = new JBColor(new Color(0x1A73E8), new Color(0x5BA3F5));
    private static final JBColor ICON_COLOR = new JBColor(new Color(0x5F6368), new Color(0xB4B8BF));
    private static final JBColor HOVER_BG = new JBColor(new Color(0, 0, 0, 20), new Color(255, 255, 255, 24));
    private static final JBColor PRESSED_BG = new JBColor(new Color(0, 0, 0, 34), new Color(255, 255, 255, 42));

    private static final int BUTTON_SIZE = 28;
    private static final int OMNIBOX_ARC = 12;
    private static final int LOCK_AREA_WIDTH = 26;

    private final BrowserToolService service;
    private final OmniboxField urlField;
    private final FlatIconButton backButton;
    private final FlatIconButton forwardButton;
    private final FlatIconButton refreshButton;

    public BrowserToolPanel(Project project) {
        this.service = BrowserToolService.getInstance(project);
        setLayout(new BorderLayout());

        JPanel toolbar = new JPanel(new BorderLayout(6, 0));
        toolbar.setBackground(TOOLBAR_BG);
        toolbar.setOpaque(true);
        toolbar.setBorder(JBUI.Borders.empty(6, 8, 6, 8));

        JPanel navButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        navButtons.setOpaque(false);
        backButton = new FlatIconButton(FlatIconButton.IconType.BACK, "后退");
        forwardButton = new FlatIconButton(FlatIconButton.IconType.FORWARD, "前进");
        refreshButton = new FlatIconButton(FlatIconButton.IconType.REFRESH, "刷新");
        navButtons.add(backButton);
        navButtons.add(forwardButton);
        navButtons.add(refreshButton);

        urlField = new OmniboxField();
        urlField.setText("about:blank");

        toolbar.add(navButtons, BorderLayout.WEST);
        toolbar.add(urlField, BorderLayout.CENTER);

        add(toolbar, BorderLayout.NORTH);

        JComponent browserComponent = service.getBrowserComponent();
        if (browserComponent.getParent() != null) {
            browserComponent.getParent().remove(browserComponent);
        }
        add(browserComponent, BorderLayout.CENTER);

        service.setUrlChangeListener(url -> SwingUtilities.invokeLater(() -> urlField.setText(url)));

        backButton.addActionListener(e -> service.getOrCreateBrowser().getCefBrowser().goBack());
        forwardButton.addActionListener(e -> service.getOrCreateBrowser().getCefBrowser().goForward());
        refreshButton.addActionListener(e -> service.getOrCreateBrowser().getCefBrowser().reload());
        urlField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    navigateFromUrlField();
                }
            }
        });
    }

    private void navigateFromUrlField() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) return;
        if (!url.contains("://")) {
            url = "https://" + url;
        }
        service.openUrl(url);
    }

    @Override
    public void dispose() {
    }

    /**
     * Chrome Omnibox 风格地址栏：圆角边框、左侧锁形图标、聚焦时蓝色描边。
     */
    private static final class OmniboxField extends JBTextField {

        OmniboxField() {
            setOpaque(false);
            setBorder(JBUI.Borders.empty(5, LOCK_AREA_WIDTH + 4, 5, 10));
            setFont(getFont().deriveFont(Font.PLAIN, JBUI.scaleFontSize(13f)));
            getEmptyText().setText("搜索或输入网址");
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    repaint();
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            size.height = Math.max(size.height, JBUI.scale(BUTTON_SIZE));
            return size;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

                int w = getWidth();
                int h = getHeight();
                float arc = JBUI.scale(OMNIBOX_ARC);

                g2.setColor(OMNIBOX_BG);
                g2.fill(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f, arc, arc));

                boolean focused = isFocusOwner();
                g2.setColor(focused ? OMNIBOX_FOCUS : OMNIBOX_BORDER);
                g2.setStroke(new BasicStroke(focused ? 2f : 1f));
                float inset = focused ? 1f : 0.5f;
                g2.draw(new RoundRectangle2D.Float(inset, inset, w - inset * 2, h - inset * 2, arc, arc));

                paintLockIcon(g2, h);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }

        /** 左侧简易挂锁图标(Java2D 绘制，表示 HTTPS)。 */
        private void paintLockIcon(Graphics2D g2, int fieldHeight) {
            int size = JBUI.scale(10);
            int x = JBUI.scale(9);
            int y = (fieldHeight - size) / 2;

            g2.setColor(ICON_COLOR);
            g2.setStroke(new BasicStroke(JBUI.scale(1.4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            // 锁体
            int bodyTop = y + size / 2 - 1;
            int bodyHeight = size / 2 + 2;
            g2.fillRoundRect(x, bodyTop, size, bodyHeight, 3, 3);
            // 锁梁
            int shackleWidth = size - 4;
            g2.drawArc(x + 2, y, shackleWidth, size / 2 + 2, 0, 180);
        }
    }

    /**
     * Chrome 风格扁平图标按钮：默认无背景，悬停显示圆形浅灰背景，图标用 Java2D 绘制。
     */
    private static final class FlatIconButton extends JButton {

        enum IconType { BACK, FORWARD, REFRESH }

        private final IconType iconType;
        private boolean hovered;
        private boolean pressed;

        FlatIconButton(IconType iconType, String tooltip) {
            this.iconType = iconType;
            setToolTipText(tooltip);
            setFocusable(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            Dimension size = new Dimension(JBUI.scale(BUTTON_SIZE), JBUI.scale(BUTTON_SIZE));
            setPreferredSize(size);
            setMinimumSize(size);
            setMaximumSize(size);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    pressed = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    pressed = true;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    pressed = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

                int w = getWidth();
                int h = getHeight();

                if (pressed) {
                    g2.setColor(PRESSED_BG);
                    g2.fillOval(0, 0, w, h);
                } else if (hovered) {
                    g2.setColor(HOVER_BG);
                    g2.fillOval(0, 0, w, h);
                }

                g2.setColor(ICON_COLOR);
                g2.setStroke(new BasicStroke(JBUI.scale(1.8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                int cx = w / 2;
                int cy = h / 2;
                int r = JBUI.scale(5);

                switch (iconType) {
                    case BACK:
                        paintChevron(g2, cx, cy, r, true);
                        break;
                    case FORWARD:
                        paintChevron(g2, cx, cy, r, false);
                        break;
                    case REFRESH:
                        paintRefresh(g2, cx, cy, r);
                        break;
                }
            } finally {
                g2.dispose();
            }
        }

        /** 箭头：水平横线 + 指向左/右的箭头头部(Chrome 前进/后退样式)。 */
        private static void paintChevron(Graphics2D g2, int cx, int cy, int r, boolean left) {
            int dir = left ? -1 : 1;
            int head = r - 1;
            int apexX = cx + dir * r;
            Path2D path = new Path2D.Float();
            path.moveTo(apexX - dir * head, cy - head);
            path.lineTo(apexX, cy);
            path.lineTo(apexX - dir * head, cy + head);
            g2.draw(path);
            g2.drawLine(apexX, cy, cx - dir * r, cy);
        }

        /** 刷新：缺口圆弧 + 端点处沿切线方向的小箭头(Chrome 刷新样式)。 */
        private static void paintRefresh(Graphics2D g2, int cx, int cy, int r) {
            g2.drawArc(cx - r, cy - r, r * 2, r * 2, 30, 300);

            // 圆弧终点在 30° 处，箭头沿顺时针切线方向
            double theta = Math.toRadians(30);
            double tipX = cx + r * Math.cos(theta);
            double tipY = cy - r * Math.sin(theta);
            double dx = Math.sin(theta);   // 顺时针切线方向(屏幕坐标)
            double dy = Math.cos(theta);
            double px = Math.cos(theta);   // 垂直于切线方向
            double py = -Math.sin(theta);
            double a = JBUI.scale(4);

            Path2D arrow = new Path2D.Double();
            arrow.moveTo(tipX + dx * a, tipY + dy * a);
            arrow.lineTo(tipX + px * a * 0.9, tipY + py * a * 0.9);
            arrow.lineTo(tipX - px * a * 0.9, tipY - py * a * 0.9);
            arrow.closePath();
            g2.fill(arrow);
        }
    }
}
