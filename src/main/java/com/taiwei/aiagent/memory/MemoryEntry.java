package com.taiwei.aiagent.memory;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable long-term memory record. Mutating fields (access stats, importance, content)
 * are updated by producing a new instance via the {@code withXxx} methods.
 */
public final class MemoryEntry {

    private final String id;
    private final String content;
    private final MemoryCategory category;
    private final List<String> tags;
    private final int importance;
    private final int accessCount;
    private final long createdAt;
    private final long updatedAt;
    private final long lastAccessedAt;

    public MemoryEntry(String id, String content, MemoryCategory category, List<String> tags,
                        int importance, int accessCount, long createdAt, long updatedAt, long lastAccessedAt) {
        this.id = id;
        this.content = content;
        this.category = category;
        this.tags = tags == null ? Collections.emptyList() : Collections.unmodifiableList(tags);
        this.importance = importance;
        this.accessCount = accessCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastAccessedAt = lastAccessedAt;
    }

    public static MemoryEntry create(String id, String content, MemoryCategory category,
                                      List<String> tags, int importance, long now) {
        return new MemoryEntry(id, content, category, tags, importance, 0, now, now, now);
    }

    public MemoryEntry withAccessed(long now) {
        return new MemoryEntry(id, content, category, tags, importance, accessCount + 1, createdAt, updatedAt, now);
    }

    public MemoryEntry withImportance(int newImportance, long now) {
        return new MemoryEntry(id, content, category, tags, newImportance, accessCount, createdAt, now, lastAccessedAt);
    }

    public MemoryEntry withContentAndTags(String newContent, List<String> newTags, int newImportance,
                                           int newAccessCount, long now) {
        return new MemoryEntry(id, newContent, category, newTags, newImportance, newAccessCount, createdAt, now, lastAccessedAt);
    }

    public String getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public MemoryCategory getCategory() {
        return category;
    }

    public List<String> getTags() {
        return tags;
    }

    public int getImportance() {
        return importance;
    }

    public int getAccessCount() {
        return accessCount;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public long getLastAccessedAt() {
        return lastAccessedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryEntry)) return false;
        MemoryEntry that = (MemoryEntry) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MemoryEntry{id='" + id + "', content='" + content + "', category=" + category
                + ", tags=" + tags + ", importance=" + importance + ", accessCount=" + accessCount + "}";
    }
}
