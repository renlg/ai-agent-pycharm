package com.taiwei.aiagent.memory;

import com.taiwei.aiagent.util.I18nUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes "remember this" / "forget that" / "what do you know about X" style chat messages
 * so they can be intercepted and handled locally, before the message is ever sent to the LLM.
 */
public class MemoryCommandHandler {

    private static final Pattern REMEMBER_PATTERN = Pattern.compile(
            "^(?:记住|记一下|remember\\s+this\\s*:?)\\s*[:：]?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern FORGET_PATTERN = Pattern.compile(
            "^(?:忘了|忘掉|forget\\s+about)\\s*[:：]?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern RECALL_PATTERN = Pattern.compile(
            "^(?:我上次说的|what\\s+do\\s+you\\s+know\\s+about)\\s*[:：]?\\s*(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsHan}\\p{L}\\p{N}]+");

    /** Maximum number of auto-generated tags for a memory entry. */
    private static final int MAX_AUTO_TAGS = 5;

    /** Minimum token length to qualify as a tag (filters out noise like single punctuation). */
    private static final int MIN_TAG_LENGTH = 2;

    private final MemoryManager memoryManager;

    public MemoryCommandHandler(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /** Returns the reply to show the user if {@code text} matched a memory command, else empty. */
    public Optional<String> tryHandle(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String trimmed = text.trim();

        Matcher rememberMatcher = REMEMBER_PATTERN.matcher(trimmed);
        if (rememberMatcher.matches()) {
            return Optional.of(handleRemember(rememberMatcher.group(1).trim()));
        }

        Matcher forgetMatcher = FORGET_PATTERN.matcher(trimmed);
        if (forgetMatcher.matches()) {
            return Optional.of(handleForget(forgetMatcher.group(1).trim()));
        }

        Matcher recallMatcher = RECALL_PATTERN.matcher(trimmed);
        if (recallMatcher.matches()) {
            return Optional.of(handleRecall(recallMatcher.group(1).trim()));
        }

        return Optional.empty();
    }

    private String handleRemember(String content) {
        if (content.isEmpty()) {
            return I18nUtil.getMessage("memory.command.rememberEmpty");
        }
        List<String> tags = generateTags(content);
        memoryManager.remember(content, MemoryCategory.FACT, tags, 5);
        return I18nUtil.getMessage("memory.command.remembered", content, String.join(", ", tags));
    }

    /**
     * 从记忆内容中自动提取标签。
     * 按文本分词后取前 N 个有意义的 token 作为标签，过滤掉过短的噪声。
     */
    private static List<String> generateTags(String content) {
        Set<String> seen = new HashSet<>();
        List<String> tags = new ArrayList<>();
        for (String token : TOKEN_SPLIT.split(content)) {
            String t = token.trim();
            if (t.length() >= MIN_TAG_LENGTH && seen.add(t)) {
                tags.add(t);
                if (tags.size() >= MAX_AUTO_TAGS) break;
            }
        }
        return tags;
    }

    private String handleForget(String query) {
        if (query.isEmpty()) {
            return I18nUtil.getMessage("memory.command.forgetEmpty");
        }
        int deleted = memoryManager.forgetByQuery(query);
        return deleted > 0
                ? I18nUtil.getMessage("memory.command.forgotten", deleted, query)
                : I18nUtil.getMessage("memory.command.forgetNotFound", query);
    }

    private String handleRecall(String query) {
        if (query.isEmpty()) {
            return I18nUtil.getMessage("memory.command.recallEmpty");
        }
        List<MemoryEntry> matches = memoryManager.recall(query, 5);
        if (matches.isEmpty()) {
            return I18nUtil.getMessage("memory.command.recallNotFound", query);
        }
        StringBuilder sb = new StringBuilder(I18nUtil.getMessage("memory.command.recallHeader", query));
        for (MemoryEntry entry : matches) {
            sb.append("- ").append(entry.getContent()).append("\n");
        }
        return sb.toString().trim();
    }
}
