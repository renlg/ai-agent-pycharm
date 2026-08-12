package com.taiwei.aiagent.skill;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManagerTest {

    @TempDir
    Path tempDir;

    private SkillManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.dispose();
        }
    }

    private void writeSkill(String fileName, String name, String description, String tags) throws IOException {
        String content = "---\n"
                + "name: " + name + "\n"
                + "description: " + description + "\n"
                + "tags: " + tags + "\n"
                + "---\n"
                + "# " + name + "\n"
                + "Body for " + name + "\n";
        Files.write(tempDir.resolve(fileName), content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void listSkills_isEmptyForFreshDirectory_andCreatesItLazily() {
        Path skillsDir = tempDir.resolve("skills");
        manager = new SkillManager(skillsDir);

        assertFalse(Files.exists(skillsDir), "directory must not be touched before first use");

        List<Skill> skills = manager.listSkills();

        assertTrue(skills.isEmpty());
        assertTrue(Files.exists(skillsDir), "directory should be created on first (lazy) scan");
    }

    @Test
    void listSkills_returnsMetadataOnly_contentNotLoaded() throws IOException {
        writeSkill("git-commit.md", "git-commit", "Create commits", "[git, vcs]");
        manager = new SkillManager(tempDir);

        List<Skill> skills = manager.listSkills();

        assertEquals(1, skills.size());
        Skill skill = skills.get(0);
        assertEquals("git-commit", skill.getName());
        assertEquals("Create commits", skill.getDescription());
        assertEquals(List.of("git", "vcs"), skill.getTags());
        assertFalse(skill.isContentLoaded());
    }

    @Test
    void getSkill_loadsAndCachesFullContent() throws IOException {
        writeSkill("git-commit.md", "git-commit", "Create commits", "[git]");
        manager = new SkillManager(tempDir);

        Optional<Skill> first = manager.getSkill("git-commit");
        assertTrue(first.isPresent());
        assertTrue(first.get().isContentLoaded());
        assertTrue(first.get().getContent().contains("Body for git-commit"));

        // Second call should hit the cache and still return full content.
        Optional<Skill> second = manager.getSkill("git-commit");
        assertTrue(second.get().isContentLoaded());
    }

    @Test
    void getSkill_unknownName_returnsEmpty() {
        manager = new SkillManager(tempDir);
        assertTrue(manager.getSkill("does-not-exist").isEmpty());
    }

    @Test
    void searchSkills_matchesByNameSubstring() throws IOException {
        writeSkill("git-commit.md", "git-commit", "Create commits", "[git]");
        writeSkill("code-review.md", "code-review", "Review code", "[quality]");
        manager = new SkillManager(tempDir);

        List<Skill> results = manager.searchSkills("git");

        assertEquals(1, results.size());
        assertEquals("git-commit", results.get(0).getName());
    }

    @Test
    void searchSkills_matchesByTag() throws IOException {
        writeSkill("git-commit.md", "git-commit", "Create commits", "[git, vcs]");
        writeSkill("code-review.md", "code-review", "Review code", "[quality]");
        manager = new SkillManager(tempDir);

        List<Skill> results = manager.searchSkills("quality");

        assertEquals(1, results.size());
        assertEquals("code-review", results.get(0).getName());
    }

    @Test
    void searchSkills_blankQuery_returnsAllSkills() throws IOException {
        writeSkill("git-commit.md", "git-commit", "Create commits", "[git]");
        writeSkill("code-review.md", "code-review", "Review code", "[quality]");
        manager = new SkillManager(tempDir);

        assertEquals(2, manager.searchSkills("  ").size());
    }

    @Test
    void addSkill_writesFileAndRegistersImmediately() throws IOException {
        manager = new SkillManager(tempDir);

        Skill added = manager.addSkill("new-skill", "---\nname: new-skill\ndescription: desc\ntags: [x]\n---\nBody\n");

        assertEquals("new-skill", added.getName());
        assertTrue(Files.exists(tempDir.resolve("new-skill.md")));
        assertEquals(1, manager.listSkills().size());
        assertTrue(manager.getSkill("new-skill").get().getContent().contains("Body"));
    }

    @Test
    void addSkill_stripsDirectoryTraversalAndStaysWithinSkillsDir() throws IOException {
        manager = new SkillManager(tempDir);

        manager.addSkill("../../etc/passwd", "---\nname: passwd\n---\ncontent");

        assertTrue(Files.exists(tempDir.resolve("passwd.md")), "file must land inside the skills dir");
        assertFalse(Files.exists(tempDir.resolve("../../etc/passwd.md")));
    }

    @Test
    void addSkill_rejectsBlankOrDotOnlyFileName() {
        manager = new SkillManager(tempDir);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> manager.addSkill("..", "content"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> manager.addSkill("   ", "content"));
    }

    @Test
    void removeSkill_deletesFileAndRemovesFromRegistry() throws IOException {
        writeSkill("git-commit.md", "git-commit", "Create commits", "[git]");
        manager = new SkillManager(tempDir);
        manager.listSkills();

        boolean removed = manager.removeSkill("git-commit");

        assertTrue(removed);
        assertFalse(Files.exists(tempDir.resolve("git-commit.md")));
        assertTrue(manager.listSkills().isEmpty());
    }

    @Test
    void removeSkill_unknownName_returnsFalse() throws IOException {
        manager = new SkillManager(tempDir);
        assertFalse(manager.removeSkill("nope"));
    }

    @Test
    void refresh_picksUpFilesystemChangesMadeOutsideTheManager() throws IOException {
        manager = new SkillManager(tempDir);
        assertTrue(manager.listSkills().isEmpty());

        writeSkill("git-commit.md", "git-commit", "Create commits", "[git]");
        manager.refresh();

        assertEquals(1, manager.listSkills().size());
    }

    @Test
    void concurrentFirstAccess_isThreadSafeAndScansOnce() throws Exception {
        writeSkill("git-commit.md", "git-commit", "Create commits", "[git]");
        manager = new SkillManager(tempDir);

        int threadCount = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<List<Skill>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return manager.listSkills();
                }));
            }
            ready.await();
            go.countDown();
            for (java.util.concurrent.Future<List<Skill>> future : futures) {
                assertEquals(1, future.get(5, TimeUnit.SECONDS).size());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
