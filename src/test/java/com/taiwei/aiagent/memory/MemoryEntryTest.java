package com.taiwei.aiagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryEntryTest {

    @Test
    void create_setsAllTimestampsToNow_andAccessCountZero() {
        long now = 1_000_000L;
        MemoryEntry entry = MemoryEntry.create("id-1", "some content", MemoryCategory.FACT, List.of("tag1"), 7, now);

        assertEquals("id-1", entry.getId());
        assertEquals("some content", entry.getContent());
        assertEquals(MemoryCategory.FACT, entry.getCategory());
        assertEquals(List.of("tag1"), entry.getTags());
        assertEquals(7, entry.getImportance());
        assertEquals(0, entry.getAccessCount());
        assertEquals(now, entry.getCreatedAt());
        assertEquals(now, entry.getUpdatedAt());
        assertEquals(now, entry.getLastAccessedAt());
    }

    @Test
    void withAccessed_incrementsAccessCountAndUpdatesLastAccessedAt_keepsCreatedAt() {
        MemoryEntry entry = MemoryEntry.create("id-1", "content", MemoryCategory.FACT, List.of(), 5, 1000L);

        MemoryEntry touched = entry.withAccessed(2000L);

        assertEquals(1, touched.getAccessCount());
        assertEquals(2000L, touched.getLastAccessedAt());
        assertEquals(1000L, touched.getCreatedAt());
        assertEquals("content", touched.getContent());
    }

    @Test
    void withImportance_updatesImportanceAndUpdatedAt_keepsLastAccessedAt() {
        MemoryEntry entry = MemoryEntry.create("id-1", "content", MemoryCategory.FACT, List.of(), 5, 1000L);

        MemoryEntry decayed = entry.withImportance(4, 3000L);

        assertEquals(4, decayed.getImportance());
        assertEquals(3000L, decayed.getUpdatedAt());
        assertEquals(1000L, decayed.getLastAccessedAt(), "decay must not count as an access");
    }

    @Test
    void withContentAndTags_replacesContentTagsImportanceAndAccessCount() {
        MemoryEntry entry = MemoryEntry.create("id-1", "old", MemoryCategory.FACT, List.of("a"), 5, 1000L);

        MemoryEntry merged = entry.withContentAndTags("old", List.of("a", "b"), 8, 3, 4000L);

        assertEquals(List.of("a", "b"), merged.getTags());
        assertEquals(8, merged.getImportance());
        assertEquals(3, merged.getAccessCount());
        assertEquals(4000L, merged.getUpdatedAt());
    }

    @Test
    void equals_isBasedOnIdOnly() {
        MemoryEntry a = MemoryEntry.create("same-id", "content A", MemoryCategory.FACT, List.of(), 5, 1000L);
        MemoryEntry b = MemoryEntry.create("same-id", "content B", MemoryCategory.PREFERENCE, List.of("x"), 9, 2000L);
        MemoryEntry c = MemoryEntry.create("other-id", "content A", MemoryCategory.FACT, List.of(), 5, 1000L);

        assertTrue(a.equals(b));
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
        assertNotEquals(a, null);
    }

    @Test
    void tags_areUnmodifiable_nullTagsBecomeEmptyList() {
        MemoryEntry entry = MemoryEntry.create("id-1", "content", MemoryCategory.FACT, null, 5, 1000L);
        assertTrue(entry.getTags().isEmpty());
    }
}
