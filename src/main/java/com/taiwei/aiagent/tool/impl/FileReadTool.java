package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

/**
 * 读取文件工具
 * Agent 可以通过此工具读取项目中的文件内容
 */
public class FileReadTool implements Tool {

    private static final Logger LOG = Logger.getInstance(FileReadTool.class);

    private final Project project;

    public FileReadTool(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return I18nUtil.getMessage("tool.description.readFile");
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "path": {
                      "type": "string",
                      "description": "要读取的文件路径（绝对路径或相对项目根目录的路径）"
                    },
                    "start_line": {
                      "type": "integer",
                      "description": "起始行号（1-based，可选，默认从第1行开始）"
                    },
                    "end_line": {
                      "type": "integer",
                      "description": "结束行号（1-based，可选，默认到文件末尾）"
                    }
                  },
                  "required": ["path"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            if (!args.has("path") || args.get("path").getAsString().isBlank()) {
                return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.error.pathRequired"),
                        I18nUtil.getMessage("tool.hint.provideValidArguments"));
            }
            String filePath = args.get("path").getAsString();

            Path resolved = resolvePath(filePath);
            if (!Files.exists(resolved)) {
                return buildFileNotFoundError(resolved);
            }
            if (Files.isDirectory(resolved)) {
                return ToolError.of("PATH_IS_DIRECTORY",
                        I18nUtil.getMessage("tool.read.pathIsDirectory", resolved),
                        I18nUtil.getMessage("tool.hint.provideFilePath"));
            }

            // 读取全部内容
            String content = Files.readString(resolved, StandardCharsets.UTF_8);

            // 如果指定了行范围，截取
            if (args.has("start_line") || args.has("end_line")) {
                String[] lines = content.split("\n", -1);
                int start = args.has("start_line") ? args.get("start_line").getAsInt() : 1;
                int end = args.has("end_line") ? args.get("end_line").getAsInt() : lines.length;

                start = Math.max(1, start);
                end = Math.min(lines.length, end);

                StringBuilder sb = new StringBuilder();
                for (int i = start - 1; i < end; i++) {
                    sb.append(String.format("%4d | %s\n", i + 1, lines[i]));
                }
                return sb.toString();
            }

            // 大文件截断提示
            if (content.length() > 50000) {
                return content.substring(0, 50000) + I18nUtil.getMessage("tool.read.truncated");
            }

            return content;

        } catch (Exception e) {
            return ToolError.unexpected(LOG, "Failed to read file", e,
                    I18nUtil.getMessage("tool.read.failed", e.getMessage()),
                    I18nUtil.getMessage("tool.hint.verifyPathAndRetry"));
        }
    }

    private String buildFileNotFoundError(Path resolved) {
        StringBuilder msg = new StringBuilder();
        msg.append(I18nUtil.getMessage("tool.read.notFound", resolved)).append("\n");

        Path parent = resolved.getParent();
        if (parent != null && Files.isDirectory(parent)) {
            try {
                String targetName = resolved.getFileName() != null ? resolved.getFileName().toString().toLowerCase() : "";
                java.util.List<String> similar = Files.list(parent)
                        .filter(p -> !Files.isDirectory(p))
                        .map(p -> p.getFileName().toString())
                        .filter(name -> {
                            String lower = name.toLowerCase();
                            // Show files with similar name (share ≥4 chars with target)
                            if (targetName.length() >= 4 && lower.contains(targetName.substring(0, Math.min(4, targetName.length())))) return true;
                            if (targetName.length() >= 4 && targetName.contains(lower.substring(0, Math.min(4, lower.length())))) return true;
                            return false;
                        })
                        .sorted()
                        .limit(8)
                        .collect(Collectors.toList());

                if (!similar.isEmpty()) {
                    msg.append(I18nUtil.getMessage("tool.read.similarFiles")).append("\n");
                    for (String name : similar) {
                        msg.append("  ").append(parent).append("/").append(name).append("\n");
                    }
                } else {
                    // List all files in parent dir as fallback
                    java.util.List<String> all = Files.list(parent)
                            .map(p -> (Files.isDirectory(p) ? "[dir] " : "      ") + p.getFileName())
                            .sorted()
                            .limit(20)
                            .collect(Collectors.toList());
                    if (!all.isEmpty()) {
                        msg.append(I18nUtil.getMessage("tool.read.parentFiles", parent)).append("\n");
                        all.forEach(f -> msg.append("  ").append(f).append("\n"));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return ToolError.of("FILE_NOT_FOUND", I18nUtil.getMessage("tool.read.notFound", resolved),
                msg.toString().trim());
    }

    private Path resolvePath(String filePath) {
        Path path = Paths.get(filePath);
        if (path.isAbsolute()) {
            return path;
        }
        // 相对路径基于项目根目录
        String basePath = project.getBasePath();
        if (basePath != null) {
            return Paths.get(basePath, filePath);
        }
        return path;
    }
}
