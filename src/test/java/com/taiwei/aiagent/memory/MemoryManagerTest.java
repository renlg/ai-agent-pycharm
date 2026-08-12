package com.taiwei.aiagent.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    private MemoryStore store;
    private MemoryManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.dispose();
        }
    }

    private void newManager() {
        store = new MemoryStore();
        manager = new MemoryManager(store);
    }

    @Test
    void remember_storesEntry_andClampsImportanceToValidRange() {
        newManager();

        MemoryEntry low = manager.remember("prefers dark theme", MemoryCategory.PREFERENCE, List.of("ui"), -5);
        MemoryEntry high = manager.remember("uses IntelliJ IDEA", MemoryCategory.FACT, List.of("tooling"), 99);

        assertEquals(1, low.getImportance());
        assertEquals(10, high.getImportance());
        assertEquals(2, manager.getMemoryCount());
    }

    @Test
    void remember_rejectsBlankContent() {
        newManager();
        assertThrows(IllegalArgumentException.class, () -> manager.remember("   ", MemoryCategory.FACT, List.of(), 5));
    }

    @Test
    void remember_defaultsToFactCategory_whenCategoryIsNull() {
        newManager();
        MemoryEntry entry = manager.remember("some fact", null, List.of(), 5);
        assertEquals(MemoryCategory.FACT, entry.getCategory());
    }

    @Test
    void recall_matchesByContentKeyword_andBumpsAccessCount() {
        newManager();
        manager.remember("user prefers tabs over spaces for indentation", MemoryCategory.PREFERENCE, List.of(), 5);
        manager.remember("project uses Gradle as the build tool", MemoryCategory.FACT, List.of(), 5);

        List<MemoryEntry> results = manager.recall("indentation preference", 10);

        assertEquals(1, results.size());
        assertTrue(results.get(0).getContent().contains("tabs"));
        assertEquals(1, results.get(0).getAccessCount(), "recall should bump access stats");
    }

    @Test
    void recall_matchesByTag() {
        newManager();
        manager.remember("commits should follow conventional commits format", MemoryCategory.PREFERENCE, List.of("git", "commit-style"), 5);
        manager.remember("unrelated memory about deployment", MemoryCategory.CONTEXT, List.of("deploy"), 5);

        List<MemoryEntry> results = manager.recall("git", 10);

        assertEquals(1, results.size());
        assertTrue(results.get(0).getTags().contains("git"));
    }

    @Test
    void recall_noMatch_returnsEmptyList() {
        newManager();
        manager.remember("some memory", MemoryCategory.FACT, List.of(), 5);

        assertTrue(manager.recall("completely unrelated query text", 10).isEmpty());
    }

    @Test
    void recall_blankQuery_returnsEmptyList() {
        newManager();
        manager.remember("some memory", MemoryCategory.FACT, List.of(), 5);

        assertTrue(manager.recall("   ", 10).isEmpty());
    }

    @Test
    void forget_deletesById() {
        newManager();
        MemoryEntry entry = manager.remember("temporary note", MemoryCategory.CONTEXT, List.of(), 5);

        boolean removed = manager.forget(entry.getId());

        assertTrue(removed);
        assertEquals(0, manager.getMemoryCount());
    }

    @Test
    void forgetByQuery_deletesMatchingEntries_returnsCount() {
        newManager();
        manager.remember("user's favorite editor is IntelliJ", MemoryCategory.PREFERENCE, List.of("editor"), 5);
        manager.remember("user's favorite language is Java", MemoryCategory.PREFERENCE, List.of("editor"), 5);
        manager.remember("unrelated fact about deployment", MemoryCategory.FACT, List.of("deploy"), 5);

        int deleted = manager.forgetByQuery("editor");

        assertEquals(2, deleted);
        assertEquals(1, manager.getMemoryCount());
    }

    @Test
    void list_filtersByCategory_sortedByLastAccessedDesc() {
        newManager();
        MemoryEntry fact = manager.remember("fact entry", MemoryCategory.FACT, List.of(), 5);
        manager.remember("preference entry", MemoryCategory.PREFERENCE, List.of(), 5);

        List<MemoryEntry> facts = manager.list(MemoryCategory.FACT, 10);

        assertEquals(1, facts.size());
        assertEquals(fact.getId(), facts.get(0).getId());
    }

    @Test
    void list_nullCategory_returnsAllSortedByRecency() {
        newManager();
        store.insert(MemoryEntry.create("id-old", "older", MemoryCategory.FACT, List.of(), 5, 1000L));
        store.insert(MemoryEntry.create("id-new", "newer", MemoryCategory.FACT, List.of(), 5, 2000L));

        List<MemoryEntry> all = manager.list(null, 10);

        assertEquals(2, all.size());
        assertEquals("newer", all.get(0).getContent(), "most recently accessed should be first");
    }

    @Test
    void getAllTags_returnsDistinctSortedTags() {
        newManager();
        manager.remember("entry 1", MemoryCategory.FACT, List.of("git", "vcs"), 5);
        manager.remember("entry 2", MemoryCategory.FACT, List.of("git", "build"), 5);

        assertEquals(List.of("build", "git", "vcs"), manager.getAllTags());
    }

    @Test
    void getRelevantMemories_returnsMatchesForPromptInjection() {
        newManager();
        manager.remember("user wants commit messages in English", MemoryCategory.PREFERENCE, List.of("git", "commit"), 8);
        manager.remember("unrelated deployment note", MemoryCategory.CONTEXT, List.of("deploy"), 5);

        List<MemoryEntry> relevant = manager.getRelevantMemories("please write a commit message for this change", 5);

        assertEquals(1, relevant.size());
        assertTrue(relevant.get(0).getContent().contains("commit messages"));
    }

    @Test
    void buildPromptContext_formatsMatchesAsBulletList() {
        newManager();
        manager.remember("user prefers English commit messages", MemoryCategory.PREFERENCE, List.of("git"), 8);

        String context = manager.buildPromptContext("write a commit message", 5);

        assertTrue(context.startsWith("- "));
        assertTrue(context.contains("English commit messages"));
    }

    @Test
    void buildPromptContext_noMatches_returnsEmptyString() {
        newManager();
        manager.remember("some unrelated memory", MemoryCategory.FACT, List.of(), 5);

        assertEquals("", manager.buildPromptContext("completely different topic entirely", 5));
    }

    @Test
    void autoConsolidate_mergesDuplicateContent_keepsHighestImportanceAndUnionsTags() {
        newManager();
        long now = System.currentTimeMillis();
        store.insert(MemoryEntry.create("id-1", "Uses tabs for indentation", MemoryCategory.PREFERENCE, List.of("style"), 3, now));
        store.insert(MemoryEntry.create("id-2", "uses tabs for indentation", MemoryCategory.PREFERENCE, List.of("editor"), 7, now));

        manager.autoConsolidate();

        List<MemoryEntry> all = manager.list(null, 10);
        assertEquals(1, all.size(), "duplicate (case/whitespace-insensitive) content should be merged");
        MemoryEntry merged = all.get(0);
        assertEquals(7, merged.getImportance(), "should keep the higher importance");
        assertTrue(merged.getTags().containsAll(List.of("style", "editor")), "tags should be unioned");
    }

    @Test
    void autoConsolidate_decaysImportance_forStaleUnaccessedMemories() {
        newManager();
        long farPast = System.currentTimeMillis() - (40 * DAY_MILLIS);
        store.insert(new MemoryEntry("id-1", "old forgotten preference", MemoryCategory.PREFERENCE,
                List.of(), 5, 0, farPast, farPast, farPast));

        manager.autoConsolidate();

        MemoryEntry decayed = manager.list(null, 10).get(0);
        assertEquals(4, decayed.getImportance(), "importance should decay by 1 after 30+ stale days");
    }

    @Test
    void autoConsolidate_doesNotDecayRecentlyAccessedMemories() {
        newManager();
        long now = System.currentTimeMillis();
        store.insert(MemoryEntry.create("id-1", "recently used preference", MemoryCategory.PREFERENCE, List.of(), 5, now));

        manager.autoConsolidate();

        MemoryEntry unchanged = manager.list(null, 10).get(0);
        assertEquals(5, unchanged.getImportance());
    }

    @Test
    void autoConsolidate_neverDecaysBelowImportanceOne() {
        newManager();
        long farPast = System.currentTimeMillis() - (400 * DAY_MILLIS);
        store.insert(new MemoryEntry("id-1", "ancient preference", MemoryCategory.PREFERENCE,
                List.of(), 1, 0, farPast, farPast, farPast));

        manager.autoConsolidate();

        assertEquals(1, manager.list(null, 10).get(0).getImportance());
    }
}
