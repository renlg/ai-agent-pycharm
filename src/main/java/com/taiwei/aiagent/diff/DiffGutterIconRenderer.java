package com.taiwei.aiagent.diff;

import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import com.taiwei.aiagent.util.I18nUtil;

import javax.swing.*;

/**
 * Gutter icon renderer for added/modified lines.
 * Shows a green '+' icon for additions and an orange '~' icon for modifications.
 */
public class DiffGutterIconRenderer extends GutterIconRenderer {

    private static final Icon ADDED_ICON = IconLoader.getIcon("/icons/diff_added.svg", DiffGutterIconRenderer.class);
    private static final Icon MODIFIED_ICON = IconLoader.getIcon("/icons/diff_modified.svg", DiffGutterIconRenderer.class);

    private final boolean isAdded;

    public DiffGutterIconRenderer(boolean isAdded) {
        this.isAdded = isAdded;
    }

    @Override
    public @NotNull Icon getIcon() {
        return isAdded ? ADDED_ICON : MODIFIED_ICON;
    }

    @Override
    public @Nullable String getTooltipText() {
        return I18nUtil.getMessage(isAdded ? "diff.addedLine" : "diff.modifiedLine");
    }

    @Override
    public @NotNull Alignment getAlignment() {
        return Alignment.LEFT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DiffGutterIconRenderer)) return false;
        DiffGutterIconRenderer that = (DiffGutterIconRenderer) o;
        return isAdded == that.isAdded;
    }

    @Override
    public int hashCode() {
        return Boolean.hashCode(isAdded);
    }
}
