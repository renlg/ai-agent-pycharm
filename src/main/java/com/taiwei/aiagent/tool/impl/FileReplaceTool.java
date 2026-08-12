package com.taiwei.aiagent.tool.impl;

import com.google.gson.Gson;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.taiwei.aiagent.diff.DiffEntry;
import com.taiwei.aiagent.diff.DiffReviewService;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 精确替换文件内容工具
 * 支持三种模式：str_replace（文本替换）、line_replace（行范围替换）、insert_after（行后插入）
 */
public class FileReplaceTool implements Tool {

    private static final Logger LOG = Logger.getInstance(FileReplaceTool.class);

    private final Project project;
    private final Gson gson = new Gson();

    public FileReplaceTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "file_replace";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.replaceFile");
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "file_path": {
                      "type": "string",
                      "description": "文件路径（绝对路径或相对项目根目录的路径）"
                    },
                    "mode": {
                      "type": "string",
                      "enum": ["str_replace", "line_replace", "insert_after"],
                      "description": "操作模式，默认 str_replace"
                    },
                    "old_string": {
                      "type": "string",
                      "description": "[str_replace] 要被替换的旧文本"
                    },
                    "new_string": {
                      "type": "string",
                      "description": "替换后的新文本（传空字符串表示删除匹配内容）"
                    },
                    "replace_all": {
                      "type": "boolean",
                      "description": "[str_replace] 是否替换所有匹配项，默认 false"
                    },
                    "start_line": {
                      "type": "integer",
                      "description": "[line_replace] 起始行号（1-based）"
                    },
                    "end_line": {
                      "type": "integer",
                      "description": "[line_replace] 结束行号（1-based，包含该行）"
                    },
                    "insert_line": {
                      "type": "integer",
                      "description": "[insert_after] 在此行之后插入内容"
                    }
                  },
                  "required": ["file_path"]
                }
                """;
    }

    @Override
    public boolean isMutating() {
        return true;
    }

    @Override
    public String execute(String arguments) {
        try {
            ReplaceArgs args = gson.fromJson(arguments, ReplaceArgs.class);
            if (args.file_path == null) {
                return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.replace.pathRequired"),
                        I18nUtil.getMessage("tool.hint.provideValidArguments"));
            }

            ExecuteContext ctx = prepareExecute(args.file_path);
            String mode = args.mode != null ? args.mode : "str_replace";

            return switch (mode) {
                case "line_replace" -> executeLineReplace(ctx.resolved, ctx.oldContent, args);
                case "insert_after" -> executeInsertAfter(ctx.resolved, ctx.oldContent, args);
                default -> executeStrReplace(ctx.resolved, ctx.oldContent, args);
            };

        } catch (IllegalArgumentException e) {
            return ToolError.of("INVALID_PATH", e.getMessage(), I18nUtil.getMessage("tool.hint.verifyPathAndRetry"));
        } catch (Exception e) {
            return ToolError.unexpected(LOG, "Failed to replace file content", e,
                    I18nUtil.getMessage("tool.replace.failed", e.getMessage()), I18nUtil.getMessage("tool.hint.retry"));
        }
    }

    private String executeStrReplace(Path resolved, String oldContent, ReplaceArgs args) throws Exception {
        if (args.old_string == null || args.old_string.isEmpty()) {
            return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.replace.oldStringRequired"), I18nUtil.getMessage("tool.hint.provideValidArguments"));
        }

        int count = countOccurrences(oldContent, args.old_string);

        if (count == 0) {
            return buildStrReplaceNotFoundError(oldContent, args.old_string, resolved.toString());
        }

        String newContent;
        String replacement = args.new_string == null ? "" : args.new_string;
        if (count == 1) {
            newContent = replaceFirst(oldContent, args.old_string, replacement);
        } else if (!args.replace_all) {
            return ToolError.of("AMBIGUOUS_MATCH", I18nUtil.getMessage("tool.replace.multipleMatches", count), I18nUtil.getMessage("tool.replace.multipleMatchesHint"));
        } else {
            newContent = oldContent.replace(args.old_string, replacement);
        }

        writeAndRecordDiff(resolved, oldContent, newContent);

        int replacedCount = args.replace_all ? count : 1;
        return I18nUtil.getMessage("tool.replace.successCount", replacedCount, resolved);
    }

    private String executeLineReplace(Path resolved, String oldContent, ReplaceArgs args) throws Exception {
        if (args.start_line == null || args.end_line == null) {
            return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.replace.lineRangeRequired"), I18nUtil.getMessage("tool.hint.provideValidArguments"));
        }

        String[] lines = oldContent.split("\n", -1);
        int totalLines = lines.length;

        if (args.start_line < 1 || args.start_line > totalLines) {
            return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.replace.startOutOfRange", totalLines, args.start_line), I18nUtil.getMessage("tool.hint.provideValidArguments"));
        }
        if (args.end_line < 1 || args.end_line > totalLines) {
            return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.replace.endOutOfRange", totalLines, args.end_line), I18nUtil.getMessage("tool.hint.provideValidArguments"));
        }
        if (args.start_line > args.end_line) {
            return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.replace.invalidRange", args.start_line, args.end_line), I18nUtil.getMessage("tool.hint.provideValidArguments"));
        }

        // Bug 3: stripTrailing new_string to avoid trailing newlines causing blank lines
        String newString = args.new_string == null ? "" : args.new_string.stripTrailing();

        // Bug 1: Three-stage construction — no in-loop conditionals on replacement logic
        StringBuilder sb = new StringBuilder();

        // 范围前的行
        for (int i = 0; i < args.start_line - 1; i++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(lines[i]);
        }

        // 替换内容（之前补换行）
        if (sb.length() > 0) sb.append("\n");
        sb.append(newString);

        // 范围后的行
        for (int i = args.end_line; i < lines.length; i++) {
            sb.append("\n");
            sb.append(lines[i]);
        }

        String newContent = sb.toString();
        writeAndRecordDiff(resolved, oldContent, newContent);

        return I18nUtil.getMessage("tool.replace.successLines", args.start_line, args.end_line, resolved);
    }

    private String executeInsertAfter(Path resolved, String oldContent, ReplaceArgs args) throws Exception {
        if (args.insert_line == null) {
            return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.replace.insertLineRequired"), I18nUtil.getMessage("tool.hint.provideValidArguments"));
        }

        String[] lines = oldContent.split("\n", -1);
        int totalLines = lines.length;

        if (args.insert_line < 0 || args.insert_line > totalLines) {
            return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.replace.insertOutOfRange", totalLines, args.insert_line), I18nUtil.getMessage("tool.hint.provideValidArguments"));
        }

        String newString = args.new_string == null ? "" : args.new_string;

        String newContent;
        // Bug 2: insert_line == 0 时插在文件最前面
        if (args.insert_line == 0) {
            newContent = newString + "\n" + oldContent;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.insert_line; i++) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(lines[i]);
            }
            sb.append("\n").append(newString);
            for (int i = args.insert_line; i < lines.length; i++) {
                sb.append("\n");
                sb.append(lines[i]);
            }
            newContent = sb.toString();
        }

        writeAndRecordDiff(resolved, oldContent, newContent);

        return I18nUtil.getMessage("tool.replace.insertSuccess", args.insert_line, resolved);
    }

    private ExecuteContext prepareExecute(String filePath) throws Exception {
        Path resolved = resolvePath(filePath);
        if (!isPathAllowed(resolved)) {
            throw new IllegalArgumentException(I18nUtil.getMessage("tool.write.outsideDenied", resolved));
        }
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException(I18nUtil.getMessage("tool.read.notFound", resolved));
        }
        if (Files.isDirectory(resolved)) {
            throw new IllegalArgumentException(I18nUtil.getMessage("tool.read.pathIsDirectory", resolved));
        }
        // Prefer the in-editor Document text: the replacement result is written back via
        // document.setText(), so basing it on stale on-disk content would silently discard
        // any unsaved edits the user has in the editor.
        String docText = com.intellij.openapi.application.ReadAction.compute(() -> {
            VirtualFile vFile = LocalFileSystem.getInstance()
                    .findFileByPath(resolved.toString().replace('\\', '/'));
            if (vFile == null) return null;
            Document document = FileDocumentManager.getInstance().getCachedDocument(vFile);
            return document != null ? document.getText() : null;
        });
        String oldContent = docText != null ? docText : Files.readString(resolved, StandardCharsets.UTF_8);
        return new ExecuteContext(resolved, oldContent);
    }

    private void writeAndRecordDiff(Path resolved, String oldContent, String newContent) throws Exception {
        DiffEntry diffEntry = new DiffEntry(resolved.toString(), oldContent, newContent);
        DiffReviewService.getInstance(project).addDiff(diffEntry);

        // 通过 Document + WriteCommandAction 写入，保留 Undo 栈，且不丢弃编辑器中未保存的修改
        // 必须在 EDT 线程执行，因为 VFS/Document API 要求 EDT 访问
        final String[] error = new String[1];
        ApplicationManager.getApplication().invokeAndWait(() -> {
            VirtualFile vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(resolved.toFile());
            if (vFile == null) {
                error[0] = I18nUtil.getMessage("tool.write.virtualFileMissing", resolved);
                return;
            }
            Document document = FileDocumentManager.getInstance().getDocument(vFile);
            if (document == null) {
                error[0] = I18nUtil.getMessage("tool.write.documentMissing", resolved);
                return;
            }
            WriteCommandAction.runWriteCommandAction(project, () -> document.setText(newContent));
            vFile.refresh(false, false);
        });

        if (error[0] != null) {
            throw new Exception(error[0]);
        }
    }

    /**
     * Build a helpful error message when str_replace finds no match.
     * Searches for the longest matching prefix of old_string's first non-blank line
     * and shows surrounding context to help the caller identify what changed.
     */
    private String buildStrReplaceNotFoundError(String content, String oldString, String filePath) {
        StringBuilder msg = new StringBuilder();
        msg.append(I18nUtil.getMessage("tool.replace.notFound")).append("\n");
        msg.append(I18nUtil.getMessage("tool.replace.fileLabel", filePath)).append("\n");
        msg.append(I18nUtil.getMessage("tool.replace.searchPreview", truncate(oldString, 120))).append("\n");
        msg.append(I18nUtil.getMessage("tool.replace.notFoundHint")).append("\n");

        // Find the first non-blank line of old_string and search for a partial match
        String[] targetLines = oldString.split("\n");
        String anchorLine = null;
        for (String l : targetLines) {
            if (!l.trim().isEmpty()) {
                anchorLine = l.trim();
                break;
            }
        }

        if (anchorLine != null && anchorLine.length() >= 6) {
            String[] contentLines = content.split("\n");
            // Try progressively shorter prefixes of the anchor line to find closest match
            int bestLine = -1;
            int bestPrefixLen = 0;
            for (int minLen = Math.min(anchorLine.length(), 40); minLen >= 6; minLen -= 4) {
                String prefix = anchorLine.substring(0, minLen);
                for (int i = 0; i < contentLines.length; i++) {
                    if (contentLines[i].contains(prefix)) {
                        if (minLen > bestPrefixLen) {
                            bestPrefixLen = minLen;
                            bestLine = i;
                        }
                    }
                }
                if (bestLine >= 0) break;
            }

            if (bestLine >= 0) {
                msg.append("\n").append(I18nUtil.getMessage("tool.replace.closestMatch", bestLine + 1)).append("\n");
                int start = Math.max(0, bestLine - 4);
                int end = Math.min(contentLines.length, bestLine + 6);
                for (int i = start; i < end; i++) {
                    msg.append(String.format("%4d | %s\n", i + 1, contentLines[i]));
                }
            } else {
                msg.append(I18nUtil.getMessage("tool.replace.noSimilarContent")).append("\n");
            }
        }

        return ToolError.of("CONTENT_NOT_FOUND", I18nUtil.getMessage("tool.replace.notFound"), msg.toString().trim());
    }

    private int countOccurrences(String content, String target) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(target, idx)) != -1) {
            count++;
            idx += target.length();
        }
        return count;
    }

    private String replaceFirst(String content, String target, String replacement) {
        int idx = content.indexOf(target);
        if (idx == -1) {
            return content;
        }
        return content.substring(0, idx) + replacement + content.substring(idx + target.length());
    }

    private String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    private Path resolvePath(String filePath) {
        Path path = Paths.get(filePath);
        if (path.isAbsolute()) {
            return path;
        }
        String basePath = project.getBasePath();
        if (basePath != null) {
            return Paths.get(basePath, filePath);
        }
        return path;
    }

    /**
     * 校验路径是否在项目目录内；若在外部，则弹窗询问用户是否允许写入
     */
    private boolean isPathAllowed(Path resolved) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return true;
        }
        Path projectBasePath = Paths.get(basePath).normalize();
        Path normalizedResolved = resolved.normalize();
        if (normalizedResolved.startsWith(projectBasePath)) {
            return true;
        }

        final boolean[] allowed = new boolean[1];
        ApplicationManager.getApplication().invokeAndWait(() -> {
            int result = Messages.showYesNoDialog(
                    project,
                    I18nUtil.getMessage("tool.write.outsidePrompt", normalizedResolved),
                    I18nUtil.getMessage("tool.write.outsideTitle"),
                    Messages.getWarningIcon()
            );
            allowed[0] = result == Messages.YES;
        });
        return allowed[0];
    }

    private static class ExecuteContext {
        final Path resolved;
        final String oldContent;

        ExecuteContext(Path resolved, String oldContent) {
            this.resolved = resolved;
            this.oldContent = oldContent;
        }
    }

    private static class ReplaceArgs {
        String file_path;
        String old_string;
        String new_string;
        boolean replace_all;
        String mode;
        Integer start_line;
        Integer end_line;
        Integer insert_line;
    }
}
