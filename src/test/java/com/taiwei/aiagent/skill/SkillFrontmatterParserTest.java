package com.taiwei.aiagent.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillFrontmatterParserTest {

    @TempDir
    Path tempDir;

    private Path writeFile(String fileName, String content) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void parseMetadata_extractsNameDescriptionAndTags_withoutLoadingContent() throws IOException {
        Path file = writeFile("git-commit.md", """
                ---
                name: git-commit
                description: Create well-formed git commits
                tags: [git, vcs, workflow]
                ---
                # Git Commit
                ## Usage
                Body content that should not be read during metadata scan.
                """);

        Skill skill = SkillFrontmatterParser.parseMetadata(file);
        assertEquals("git-commit", skill.getName());
        assertEquals("Create well-formed git commits", skill.getDescription());
        assertEquals(List.of("git", "vcs", "workflow"), skill.getTags());
        assertFalse(skill.isContentLoaded());
        assertNull(skill.getContent());
    }

    @Test
    void parseFull_loadsBodyContent() throws IOException {
        Path file = writeFile("git-commit.md", """
                ---
                name: git-commit
                description: Create well-formed git commits
                tags: [git, vcs]
                ---
                # Git Commit
                Some instructions here.
                """);

        Skill skill = SkillFrontmatterParser.parseFull(file);

        assertEquals("git-commit", skill.getName());
        assertTrue(skill.isContentLoaded());
        assertTrue(skill.getContent().startsWith("# Git Commit"));
        assertTrue(skill.getContent().contains("Some instructions here."));
    }

    @Test
    void parseMetadata_fallsBackToFileNameWhenNameMissing() throws IOException {
        Path file = writeFile("no-name-skill.md", """
                ---
                description: no name field here
                ---
                content
                """);

        Skill skill = SkillFrontmatterParser.parseMetadata(file);

        assertEquals("no-name-skill", skill.getName());
        assertEquals("no name field here", skill.getDescription());
    }

    @Test
    void parseMetadata_handlesMissingFrontmatterGracefully() throws IOException {
        Path file = writeFile("plain.md", "# Just a heading\nNo frontmatter here.\n");

        Skill skill = SkillFrontmatterParser.parseMetadata(file);

        assertEquals("plain", skill.getName());
        assertEquals("", skill.getDescription());
        assertTrue(skill.getTags().isEmpty());
        assertFalse(skill.isContentLoaded());
    }

    @Test
    void parseFull_handlesMissingFrontmatterAsPlainBody() throws IOException {
        Path file = writeFile("plain.md", "# Just a heading\nNo frontmatter here.\n");

        Skill skill = SkillFrontmatterParser.parseFull(file);

        assertEquals("plain", skill.getName());
        assertTrue(skill.isContentLoaded());
        assertTrue(skill.getContent().contains("Just a heading"));
    }

    @Test
    void parseMetadata_handlesUnclosedFrontmatterAsNoFrontmatter() throws IOException {
        Path file = writeFile("broken.md", "---\nname: broken\ndescription: no closing delimiter\n");

        Skill skill = SkillFrontmatterParser.parseMetadata(file);

        assertEquals("broken", skill.getName());
        assertEquals("", skill.getDescription());
    }

    @Test
    void parseMetadata_stripsQuotesFromValues() throws IOException {
        Path file = writeFile("quoted.md", """
                ---
                name: "quoted-skill"
                description: 'single quoted description'
                tags: ["a", 'b']
                ---
                body
                """);

        Skill skill = SkillFrontmatterParser.parseMetadata(file);

        assertEquals("quoted-skill", skill.getName());
        assertEquals("single quoted description", skill.getDescription());
        assertEquals(List.of("a", "b"), skill.getTags());
    }

    @Test
    void parseMetadata_emptyTagsList() throws IOException {
        Path file = writeFile("no-tags.md", """
                ---
                name: no-tags
                description: has no tags
                tags: []
                ---
                body
                """);

        Skill skill = SkillFrontmatterParser.parseMetadata(file);

        assertTrue(skill.getTags().isEmpty());
    }
}
