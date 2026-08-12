package com.taiwei.aiagent.diff;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.Inlay;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LCS-based line-level diff algorithm with editor highlighting.
 * Computes diff between old and new content, then renders highlights and gutter icons.
 * Instance-based: each project gets its own DiffHighlighter to avoid static Map memory leaks.
 */
public class DiffHighlighter {

    private static final int LARGE_FILE_THRESHOLD = 2000;

    private final Project project;
    private final Map<String, List<RangeHighlighter>> highlightersByFile = new ConcurrentHashMap<>();
    private final Map<String, List<Inlay<?>>> inlaysByFile = new ConcurrentHashMap<>();

    // Color constants for themes
    private static final Color ADDED_BG = JBColor.namedColor(
            "Editor.ModifiedBar.background.added",
            new JBColor(new Color(0x1a, 0x7f, 0x37, 0x30), new Color(0x1a, 0x7f, 0x37, 0x40))
    );
    private static final Color MODIFIED_BG = JBColor.namedColor(
            "Editor.ModifiedBar.background.modified",
            new JBColor(new Color(0x7a, 0x88, 0x00, 0x30), new Color(0x7a, 0x88, 0x00, 0x40))
    );
    private static final Color DELETED_LINE_BG = JBColor.namedColor(
            "Editor.ModifiedBar.background.deleted",
            new JBColor(new Color(0x8b, 0x00, 0x00, 0x20), new Color(0x8b, 0x00, 0x00, 0x30))
    );

    public DiffHighlighter(Project project) {
        this.project = project;
    }

    /**
     * Represents a line-level diff operation.
     */
    public static class DiffLine {
        public enum Type { EQUAL, ADDED, DELETED, MODIFIED }

        public final Type type;
        public final String oldLine;
        public final String newLine;
        public final int oldLineNum;
        public final int newLineNum;

        public DiffLine(Type type, String oldLine, String newLine, int oldLineNum, int newLineNum) {
            this.type = type;
            this.oldLine = oldLine;
            this.newLine = newLine;
            this.oldLineNum = oldLineNum;
            this.newLineNum = newLineNum;
        }
    }

    // ---- Public API ----

    /**
     * Apply diff highlights to the given document.
     */
    public void applyHighlight(@NotNull Document document,
                               @NotNull String oldContent, @NotNull String newContent) {
        // Clear existing highlights immediately: cheap, and callers expect stale highlights
        // gone right away (they're already on the EDT when calling this method).
        clearHighlight(document);

        String fileKey = getFileKey(document);

        // computeDiff() (LCS) is O(m*n) and can noticeably freeze the UI for large files.
        // Run it off the EDT, then post the (fast) markup-model updates back to the EDT.
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<DiffLine> diffLines = computeDiff(oldContent, newContent);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) return;
                renderDiffLines(document, fileKey, diffLines);
            });
        });
    }

    /**
     * Applies pre-computed diff lines as range highlighters / inlays on the document.
     * Must run on the EDT (MarkupModel and Inlay APIs are not thread-safe).
     */
    private void renderDiffLines(Document document, String fileKey, List<DiffLine> diffLines) {
        // Two applyHighlight() calls can overlap (each clears, then computes off-EDT, then renders):
        // the later put() would orphan the earlier render's highlighters, leaving them in the
        // editor forever. Dispose whatever is currently tracked for this file before rendering.
        clearHighlightsForFile(fileKey);

        MarkupModel markupModel = DocumentMarkupModel.forDocument(document, project, true);

        List<RangeHighlighter> highlighters = new ArrayList<>();

        int newLineIndex = 0;
        int oldLineIndex = 0;

        for (DiffLine diffLine : diffLines) {
            switch (diffLine.type) {
                case EQUAL:
                    newLineIndex++;
                    oldLineIndex++;
                    break;

                case ADDED:
                    highlightLine(markupModel, document, newLineIndex, DiffLine.Type.ADDED, highlighters);
                    newLineIndex++;
                    break;

                case DELETED:
                    highlightDeletedLine(markupModel, document, newLineIndex, oldLineIndex, diffLine.oldLine,
                            highlighters);
                    // Create block inlay at current position showing deleted content
                    Inlay<?> inlay = createDeletedInlay(document, newLineIndex, diffLine.oldLine);
                    if (inlay != null) {
                        addInlay(fileKey, inlay);
                    }
                    oldLineIndex++;
                    break;

                case MODIFIED:
                    highlightLine(markupModel, document, newLineIndex, DiffLine.Type.MODIFIED, highlighters);
                    // Create block inlay at current position showing old (pre-modified) content
                    {
                        Inlay<?> modifiedInlay = createDeletedInlay(document, newLineIndex, diffLine.oldLine);
                        if (modifiedInlay != null) {
                            addInlay(fileKey, modifiedInlay);
                        }
                    }
                    newLineIndex++;
                    oldLineIndex++;
                    break;
            }
        }

        highlightersByFile.put(fileKey, highlighters);
    }

    /**
     * Apply diff highlights from a DiffEntry using filePath-based key.
     * Clears existing highlights first, then applies new ones.
     */
    public void highlight(@NotNull DiffEntry entry) {
        clearHighlightsForFile(entry.getFilePath());
        VirtualFile vf = LocalFileSystem.getInstance().findFileByPath(entry.getFilePath());
        if (vf == null) return;
        Document document = FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) return;
        applyHighlight(document, entry.getOldContent(), entry.getNewContent());
    }

    /**
     * Clear all diff highlights for the given document.
     */
    public void clearHighlight(@NotNull Document document) {
        String fileKey = getFileKey(document);

        // Remove range highlighters via dispose()
        List<RangeHighlighter> highlighters = highlightersByFile.remove(fileKey);
        if (highlighters != null) {
            for (RangeHighlighter h : highlighters) {
                if (h.isValid()) h.dispose();
            }
        }

        // Remove inlays via Disposer
        List<Inlay<?>> inlays = inlaysByFile.remove(fileKey);
        if (inlays != null) {
            for (Inlay<?> inlay : inlays) {
                if (inlay.isValid()) Disposer.dispose(inlay);
            }
        }
    }

    /**
     * Clear diff highlights for a specific file path using filePath-based key.
     */
    public void clearHighlightsForFile(@NotNull String filePath) {
        // Remove range highlighters via dispose()
        List<RangeHighlighter> highlighters = highlightersByFile.remove(filePath);
        if (highlighters != null) {
            for (RangeHighlighter h : highlighters) {
                if (h.isValid()) h.dispose();
            }
        }

        // Remove inlays via Disposer
        List<Inlay<?>> inlays = inlaysByFile.remove(filePath);
        if (inlays != null) {
            for (Inlay<?> inlay : inlays) {
                if (inlay.isValid()) Disposer.dispose(inlay);
            }
        }
    }

    // ---- LCS Diff Algorithm ----

    /**
     * Compute line-level diff using LCS algorithm.
     * For files with >2000 lines (after prefix/suffix trimming), falls back to O(n)
     * line-by-line comparison instead of the O(m*n) LCS table.
     */
    public static List<DiffLine> computeDiff(@NotNull String oldContent, @NotNull String newContent) {
        String[] oldLines = oldContent.isEmpty() ? new String[0] : oldContent.split("\n", -1);
        String[] newLines = newContent.isEmpty() ? new String[0] : newContent.split("\n", -1);

        int m = oldLines.length;
        int n = newLines.length;
        int maxCommon = Math.min(m, n);

        // Trim common prefix/suffix first: unchanged head/tail lines never need to enter the
        // LCS table (or the simple diff), shrinking the diff region to just what actually changed.
        int prefixLen = 0;
        while (prefixLen < maxCommon && oldLines[prefixLen].equals(newLines[prefixLen])) {
            prefixLen++;
        }

        int suffixLen = 0;
        while (suffixLen < maxCommon - prefixLen
                && oldLines[m - 1 - suffixLen].equals(newLines[n - 1 - suffixLen])) {
            suffixLen++;
        }

        List<DiffLine> result = new ArrayList<>();
        for (int i = 0; i < prefixLen; i++) {
            result.add(new DiffLine(DiffLine.Type.EQUAL, oldLines[i], newLines[i], i, i));
        }

        String[] oldMiddle = Arrays.copyOfRange(oldLines, prefixLen, m - suffixLen);
        String[] newMiddle = Arrays.copyOfRange(newLines, prefixLen, n - suffixLen);

        // Large file optimization: use simple line-by-line comparison
        List<DiffLine> middleDiff = (oldMiddle.length > LARGE_FILE_THRESHOLD || newMiddle.length > LARGE_FILE_THRESHOLD)
                ? computeSimpleDiff(oldMiddle, newMiddle)
                : computeLcsDiff(oldMiddle, newMiddle);

        for (DiffLine dl : middleDiff) {
            result.add(new DiffLine(dl.type, dl.oldLine, dl.newLine, dl.oldLineNum + prefixLen, dl.newLineNum + prefixLen));
        }

        for (int i = 0; i < suffixLen; i++) {
            int oldIdx = m - suffixLen + i;
            int newIdx = n - suffixLen + i;
            result.add(new DiffLine(DiffLine.Type.EQUAL, oldLines[oldIdx], newLines[newIdx], oldIdx, newIdx));
        }

        return result;
    }

    /**
     * Full LCS-based diff for files under the threshold.
     */
    private static List<DiffLine> computeLcsDiff(String[] oldLines, String[] newLines) {
        int m = oldLines.length;
        int n = newLines.length;

        // Build LCS table
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (oldLines[i - 1].equals(newLines[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // Backtrack to build diff
        List<DiffLine> reversed = new ArrayList<>();

        int i = m, j = n;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldLines[i - 1].equals(newLines[j - 1])) {
                reversed.add(new DiffLine(DiffLine.Type.EQUAL, oldLines[i - 1], newLines[j - 1], i - 1, j - 1));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                reversed.add(new DiffLine(DiffLine.Type.ADDED, "", newLines[j - 1], i, j - 1));
                j--;
            } else if (i > 0) {
                reversed.add(new DiffLine(DiffLine.Type.DELETED, oldLines[i - 1], "", i - 1, j));
                i--;
            }
        }

        Collections.reverse(reversed);

        // Merge adjacent ADDED+DELETED into MODIFIED where possible
        return mergeToModified(reversed);
    }

    /**
     * Merge adjacent DELETED+ADDED pairs into MODIFIED entries.
     * LCS produces separate DELETED then ADDED for modified lines.
     */
    private static List<DiffLine> mergeToModified(List<DiffLine> diffLines) {
        List<DiffLine> result = new ArrayList<>();
        int idx = 0;
        while (idx < diffLines.size()) {
            DiffLine current = diffLines.get(idx);
            if (current.type == DiffLine.Type.EQUAL) {
                result.add(current);
                idx++;
                continue;
            }
            // 收集连续的 DELETED
            List<DiffLine> deletedGroup = new ArrayList<>();
            while (idx < diffLines.size() && diffLines.get(idx).type == DiffLine.Type.DELETED) {
                deletedGroup.add(diffLines.get(idx));
                idx++;
            }
            // 收集紧跟的连续 ADDED
            List<DiffLine> addedGroup = new ArrayList<>();
            while (idx < diffLines.size() && diffLines.get(idx).type == DiffLine.Type.ADDED) {
                addedGroup.add(diffLines.get(idx));
                idx++;
            }
            // 如果同时有删除和新增 → 合并为 MODIFIED
            if (!deletedGroup.isEmpty() && !addedGroup.isEmpty()) {
                int count = Math.min(deletedGroup.size(), addedGroup.size());
                for (int i = 0; i < count; i++) {
                    DiffLine del = deletedGroup.get(i);
                    DiffLine add = addedGroup.get(i);
                    result.add(new DiffLine(DiffLine.Type.MODIFIED, del.oldLine, add.newLine,
                            del.oldLineNum, add.newLineNum));
                }
                // 多余的 DELETED
                for (int i = count; i < deletedGroup.size(); i++) {
                    result.add(deletedGroup.get(i));
                }
                // 多余的 ADDED
                for (int i = count; i < addedGroup.size(); i++) {
                    result.add(addedGroup.get(i));
                }
            } else {
                result.addAll(deletedGroup);
                result.addAll(addedGroup);
            }
        }
        return result;
    }

    /**
     * Simple O(n) line-by-line diff for large files (>5000 lines).
     */
    private static List<DiffLine> computeSimpleDiff(String[] oldLines, String[] newLines) {
        List<DiffLine> result = new ArrayList<>();
        int maxLen = Math.max(oldLines.length, newLines.length);

        for (int i = 0; i < maxLen; i++) {
            boolean hasOld = i < oldLines.length;
            boolean hasNew = i < newLines.length;

            if (hasOld && hasNew) {
                if (oldLines[i].equals(newLines[i])) {
                    result.add(new DiffLine(DiffLine.Type.EQUAL, oldLines[i], newLines[i], i, i));
                } else {
                    result.add(new DiffLine(DiffLine.Type.MODIFIED, oldLines[i], newLines[i], i, i));
                }
            } else if (hasOld) {
                result.add(new DiffLine(DiffLine.Type.DELETED, oldLines[i], "", i, i));
            } else {
                result.add(new DiffLine(DiffLine.Type.ADDED, "", newLines[i], i, i));
            }
        }
        return result;
    }

    // ---- Highlighting Helpers ----

    private void highlightLine(MarkupModel markupModel, Document document, int lineIndex,
                               DiffLine.Type type, List<RangeHighlighter> highlighters) {
        if (lineIndex >= document.getLineCount()) return;

        int lineStart = document.getLineStartOffset(lineIndex);
        int lineEnd = document.getLineEndOffset(lineIndex);

        Color bgColor;
        switch (type) {
            case ADDED:
                bgColor = ADDED_BG;
                break;
            case MODIFIED:
                bgColor = MODIFIED_BG;
                break;
            default:
                return;
        }

        TextAttributes attrs = new TextAttributes();
        attrs.setBackgroundColor(bgColor);

        RangeHighlighter highlighter = markupModel.addRangeHighlighter(
                lineStart, lineEnd,
                HighlighterLayer.CARET_ROW - 1,
                attrs,
                HighlighterTargetArea.EXACT_RANGE
        );

        if (highlighter != null) {
            highlighters.add(highlighter);
        }

        // Add gutter icon for added/modified lines
        boolean isAddedLine = type == DiffLine.Type.ADDED;
        RangeHighlighter gutterHighlighter = markupModel.addRangeHighlighter(
                lineStart, lineEnd,
                HighlighterLayer.FIRST - 1,
                null,
                HighlighterTargetArea.EXACT_RANGE
        );
        if (gutterHighlighter != null) {
            gutterHighlighter.setGutterIconRenderer(new DiffGutterIconRenderer(isAddedLine));
            highlighters.add(gutterHighlighter);
        }
    }

    private void highlightDeletedLine(MarkupModel markupModel, Document document,
                                      int newLineIndex, int oldLineIndex, String deletedContent,
                                      List<RangeHighlighter> highlighters) {
        // Place a marker at the position where content was deleted
        if (newLineIndex < document.getLineCount()) {
            int lineStart = document.getLineStartOffset(newLineIndex);
            int lineEnd = document.getLineEndOffset(newLineIndex);

            TextAttributes attrs = new TextAttributes();
            attrs.setBackgroundColor(DELETED_LINE_BG);

            RangeHighlighter highlighter = markupModel.addRangeHighlighter(
                    lineStart, lineEnd,
                    HighlighterLayer.CARET_ROW - 1,
                    attrs,
                    HighlighterTargetArea.EXACT_RANGE
            );
            if (highlighter != null) {
                highlighters.add(highlighter);
            }
        }

        // Add gutter icon for deleted lines
        int targetLine = Math.min(newLineIndex, document.getLineCount() - 1);
        if (targetLine >= 0 && targetLine < document.getLineCount()) {
            int lineStart = Math.max(0, document.getLineStartOffset(targetLine));
            int lineEnd = document.getLineEndOffset(targetLine);

            RangeHighlighter gutterHighlighter = markupModel.addRangeHighlighter(
                    lineStart, lineEnd,
                    HighlighterLayer.FIRST - 1,
                    null,
                    HighlighterTargetArea.EXACT_RANGE
            );
            if (gutterHighlighter != null) {
                gutterHighlighter.setGutterIconRenderer(
                        new DeletedLinesGutterRenderer(1, new String[]{deletedContent}, 0, 0));
                highlighters.add(gutterHighlighter);
            }
        }
    }

    private Inlay<?> createDeletedInlay(@NotNull Document document, int lineIndex, String deletedContent) {
        if (lineIndex >= document.getLineCount()) {
            // If content was at the end, attach to the last line
            lineIndex = Math.max(0, document.getLineCount() - 1);
        }

        Editor[] editors = EditorFactory.getInstance().getEditors(document, project);
        if (editors.length == 0) return null;

        Editor editor = editors[0];
        int offset = document.getLineStartOffset(lineIndex);

        DeletedBlockRenderer renderer = new DeletedBlockRenderer(deletedContent);
        return editor.getInlayModel().addBlockElement(offset, false, false, 0, renderer);
    }

    private void addInlay(String fileKey, Inlay<?> inlay) {
        inlaysByFile.computeIfAbsent(fileKey, k -> new ArrayList<>()).add(inlay);
    }

    private String getFileKey(Document document) {
        VirtualFile vf = FileDocumentManager.getInstance().getFile(document);
        if (vf != null) {
            return vf.getPath();
        }
        // Fallback: use document hash if no VirtualFile available
        return Integer.toHexString(System.identityHashCode(document));
    }
}
