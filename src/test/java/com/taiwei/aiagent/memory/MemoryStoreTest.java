package com.taiwei.aiagent.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link MemoryStore} against a private in-memory SQLite database (no disk access),
 * mirroring the file-backed implementation used in production.
 */
class MemoryStoreTest {

    private MemoryStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private MemoryEntry sample(String id, String content) {
        return MemoryEntry.create(id, content, MemoryCategory.FACT, List.of("git", "vcs"), 5, 1_000L);
    }

    @Test
    void insert_and_findById_returnsStoredEntry() {
        store = new MemoryStore();
        MemoryEntry entry = sample("id-1", "user prefers tabs over spaces");
        store.insert(entry);

        Optional<MemoryEntry> found = store.findById("id-1");

        assertTrue(found.isPresent());
        assertEquals("user prefers tabs over spaces", found.get().getContent());
        assertEquals(List.of("git", "vcs"), found.get().getTags());
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        store = new MemoryStore();
        assertTrue(store.findById("does-not-exist").isEmpty());
    }

    @Test
    void findAll_returnsEveryInsertedEntry() {
        store = new MemoryStore();
        store.insert(sample("id-1", "content one"));
        store.insert(sample("id-2", "content two"));

        List<MemoryEntry> all = store.findAll();

        assertEquals(2, all.size());
    }

    @Test
    void update_overwritesMutableFields() {
        store = new MemoryStore();
        MemoryEntry entry = sample("id-1", "original content");
        store.insert(entry);

        MemoryEntry updated = entry.withContentAndTags("original content", List.of("new-tag"), 9, 3, 5000L);
        store.update(updated);

        MemoryEntry reloaded = store.findById("id-1").orElseThrow();
        assertEquals(List.of("new-tag"), reloaded.getTags());
        assertEquals(9, reloaded.getImportance());
        assertEquals(3, reloaded.getAccessCount());
        assertEquals(5000L, reloaded.getUpdatedAt());
    }

    @Test
    void deleteById_removesEntry_returnsTrue() {
        store = new MemoryStore();
        store.insert(sample("id-1", "content"));

        boolean deleted = store.deleteById("id-1");

        assertTrue(deleted);
        assertTrue(store.findById("id-1").isEmpty());
    }

    @Test
    void deleteById_unknownId_returnsFalse() {
        store = new MemoryStore();
        assertFalse(store.deleteById("does-not-exist"));
    }

    @Test
    void count_reflectsInsertsAndDeletes() {
        store = new MemoryStore();
        assertEquals(0, store.count());

        store.insert(sample("id-1", "content one"));
        store.insert(sample("id-2", "content two"));
        assertEquals(2, store.count());

        store.deleteById("id-1");
        assertEquals(1, store.count());
    }
}
