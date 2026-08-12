package com.taiwei.aiagent.diff;

import com.intellij.openapi.editor.EditorCustomElementRenderer;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * Renders a block inlay showing deleted content with a gray background,
 * red left border, and strikethrough text.
 */
public class DeletedBlockRenderer implements EditorCustomElementRenderer {

    private static final int LEFT_BORDER_WIDTH = 3;
    private static final int PADDING_X = 8;
    private static final int PADDING_Y = 2;

    private final String deletedContent;

    // Theme-aware colors
    private static final Color BG_COLOR = JBColor.namedColor(
            "Editor.NotificationRow.background",
            new JBColor(new Color(0x2d, 0x2d, 0x2d, 0x80), new Color(0xcc, 0xcc, 0xcc, 0x15))
    );
    private static final Color LEFT_BORDER_COLOR = JBColor.namedColor(
            "Diff.deletedLineBorder",
            new JBColor(new Color(0xe8, 0x6b, 0x6b), new Color(0xf8, 0x51, 0x49))
    );
    private static final Color TEXT_COLOR = JBColor.namedColor(
            "Editor.inlayForeground",
            new JBColor(new Color(0x99, 0x99, 0x99), new Color(0x88, 0x88, 0x88))
    );

    public DeletedBlockRenderer(String deletedContent) {
        this.deletedContent = deletedContent;
    }

    @Override
    public int calcWidthInPixels(@NotNull Inlay inlay) {
        FontMetrics fm = inlay.getEditor().getContentComponent().getFontMetrics(
                inlay.getEditor().getColorsScheme().getFont(EditorFontType.PLAIN)
        );
        String[] lines = deletedContent.split("\n", -1);
        int maxWidth = 0;
        for (String line : lines) {
            int w = fm.stringWidth(line);
            if (w > maxWidth) maxWidth = w;
        }
        return maxWidth + LEFT_BORDER_WIDTH + PADDING_X * 2;
    }

    @Override
    public void paint(@NotNull Inlay inlay, @NotNull Graphics g, @NotNull Rectangle targetRegion, @NotNull TextAttributes textAttributes) {
        Graphics2D g2d = (Graphics2D) g.create();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int x = targetRegion.x;
            int y = targetRegion.y;
            int width = targetRegion.width;
            int height = targetRegion.height;

            // Draw background
            g2d.setColor(BG_COLOR);
            g2d.fillRect(x, y, width, height);

            // Draw left border (red line)
            g2d.setColor(LEFT_BORDER_COLOR);
            g2d.fillRect(x, y, LEFT_BORDER_WIDTH, height);

            // Draw text with strikethrough
            Font font = inlay.getEditor().getColorsScheme().getFont(EditorFontType.PLAIN);
            g2d.setFont(font);
            FontMetrics fm = g2d.getFontMetrics();
            int textX = x + LEFT_BORDER_WIDTH + PADDING_X;
            int textY = y + (height - fm.getHeight()) / 2 + fm.getAscent();

            g2d.setColor(TEXT_COLOR);
            // Draw each line of deleted content
            String[] lines = deletedContent.split("\n", -1);
            int lineY = textY;
            for (String line : lines) {
                if (lineY > y && lineY < y + height) {
                    g2d.drawString(line, textX, lineY);
                    // Draw strikethrough
                    int lineWidth = fm.stringWidth(line);
                    g2d.drawLine(textX, lineY - fm.getHeight() / 4, textX + lineWidth, lineY - fm.getHeight() / 4);
                }
                lineY += fm.getHeight();
            }

        } finally {
            g2d.dispose();
        }
    }
}
