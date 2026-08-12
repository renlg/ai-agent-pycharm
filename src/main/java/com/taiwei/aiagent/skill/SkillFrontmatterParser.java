package com.taiwei.aiagent.skill;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses skill markdown files with a YAML-ish frontmatter block:
 *
 * <pre>
 * ---
 * name: skill-name
 * description: brief description
 * tags: [tag1, tag2]
 * ---
 * # Title
 * ...
 * </pre>
 *
 * Deliberately simple (line-based, no YAML library): only scalar {@code key: value} fields
 * and an inline {@code tags: [a, b]} array are supported, matching the skill file convention.
 */
public final class SkillFrontmatterParser {

    private static final String DELIMITER = "---";

    private SkillFrontmatterParser() {
    }

    /**
     * Reads only the frontmatter block (stops before the file body), so listing many skills
     * never pulls full file content into memory.
     */
    public static Skill parseMetadata(Path file) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.mark(8192);
            String firstLine = reader.readLine();
            String fallbackName = fallbackName(file);

            if (firstLine == null || !DELIMITER.equals(firstLine.trim())) {
                return Skill.metadataOnly(fallbackName, "", Collections.emptyList(), file);
            }

            StringBuilder block = new StringBuilder();
            String line;
            boolean closed = false;
            while ((line = reader.readLine()) != null) {
                if (DELIMITER.equals(line.trim())) {
                    closed = true;
                    break;
                }
                block.append(line).append('\n');
            }
            if (!closed) {
                return Skill.metadataOnly(fallbackName, "", Collections.emptyList(), file);
            }

            Map<String, String> fields = parseFields(block.toString());
            List<String> tags = parseTags(block.toString());
            String name = fields.getOrDefault("name", "");
            String description = fields.getOrDefault("description", "");
            return Skill.metadataOnly(name.isEmpty() ? fallbackName : name, description, tags, file);
        }
    }

    /** Reads the whole file and returns a skill with content (frontmatter body) populated. */
    public static Skill parseFull(Path file) throws IOException {
        String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String normalized = raw.replace("\r\n", "\n");
        String fallbackName = fallbackName(file);

        if (!normalized.startsWith(DELIMITER + "\n")) {
            return new Skill(fallbackName, "", Collections.emptyList(), file, normalized.trim());
        }

        int end = normalized.indexOf("\n" + DELIMITER, DELIMITER.length() + 1);
        if (end == -1) {
            return new Skill(fallbackName, "", Collections.emptyList(), file, normalized.trim());
        }

        String block = normalized.substring(DELIMITER.length() + 1, end);
        int bodyStart = normalized.indexOf('\n', end + 1);
        String body = bodyStart != -1 ? normalized.substring(bodyStart + 1) : "";

        Map<String, String> fields = parseFields(block);
        List<String> tags = parseTags(block);
        String name = fields.getOrDefault("name", "");
        String description = fields.getOrDefault("description", "");
        return new Skill(name.isEmpty() ? fallbackName : name, description, tags, file, body.trim());
    }

    private static Map<String, String> parseFields(String block) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : block.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            if ("tags".equalsIgnoreCase(key)) continue;
            String value = stripQuotes(line.substring(colon + 1).trim());
            fields.put(key, value);
        }
        return fields;
    }

    private static List<String> parseTags(String block) {
        for (String line : block.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            if (!"tags".equalsIgnoreCase(key)) continue;

            String value = line.substring(colon + 1).trim();
            if (value.startsWith("[") && value.endsWith("]")) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.isEmpty()) return Collections.emptyList();

            List<String> tags = new ArrayList<>();
            for (String part : value.split(",")) {
                String tag = stripQuotes(part.trim());
                if (!tag.isEmpty()) tags.add(tag);
            }
            return tags;
        }
        return Collections.emptyList();
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String fallbackName(Path file) {
        String fileName = file.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
