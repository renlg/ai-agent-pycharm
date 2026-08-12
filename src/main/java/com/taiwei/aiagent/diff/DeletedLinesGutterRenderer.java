package com.taiwei.aiagent.diff;

import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.taiwei.aiagent.util.I18nUtil;

import javax.swing.*;

/**
 * Gutter icon renderer for deleted lines.
 * Shows a red '▾' icon. Tooltip displays the deleted content on hover.
 */
public class DeletedLinesGutterRenderer extends GutterIconRenderer {

    private static final Icon DELETED_ICON = IconLoader.getIcon("/icons/diff_deleted.svg", DeletedLinesGutterRenderer.class);

    private final int deletedCount;
    private final String[] oldLines;
    private final int startLine;
    private final int endLine;

    public DeletedLinesGutterRenderer(int deletedCount, String[] oldLines, int startLine, int endLine) {
        this.deletedCount = deletedCount;
        this.oldLines = oldLines;
        this.startLine = startLine;
        this.endLine = endLine;
    }

    @Override
    public @NotNull Icon getIcon() {
        return DELETED_ICON;
    }

    @Override
    public @Nullable String getTooltipText() {
        if (oldLines == null || oldLines.length == 0) {
            return I18nUtil.getMessage("diff.deletedLine");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(I18nUtil.getMessage("diff.deletedLines", deletedCount)).append("\n");
        for (int i = startLine; i <= endLine && i < oldLines.length; i++) {
            sb.append("- ").append(oldLines[i]).append("\n");
            if (i - startLine > 10) {
                sb.append(I18nUtil.getMessage("diff.deletedLinesTotal", deletedCount));
                break;
            }
        }
        return sb.toString();
    }

    @Override
    public @NotNull Alignment getAlignment() {
        return Alignment.LEFT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeletedLinesGutterRenderer)) return false;
        DeletedLinesGutterRenderer that = (DeletedLinesGutterRenderer) o;
        return deletedCount == that.deletedCount && startLine == that.startLine && endLine == that.endLine;
    }

    @Override
    public int hashCode() {
        int result = deletedCount;
        result = 31 * result + startLine;
        result = 31 * result + endLine;
        return result;
    }
}
