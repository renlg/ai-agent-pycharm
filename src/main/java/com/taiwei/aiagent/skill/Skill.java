package com.taiwei.aiagent.skill;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable skill model. Content is {@code null} until the skill has been fully loaded
 * (progressive loading: metadata is scanned eagerly-per-request, content lazily on view).
 */
public final class Skill {

    private final String name;
    private final String description;
    private final List<String> tags;
    private final Path filePath;
    private final String content;

    public Skill(String name, String description, List<String> tags, Path filePath, String content) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.tags = tags == null ? Collections.emptyList() : Collections.unmodifiableList(tags);
        this.filePath = filePath;
        this.content = content;
    }

    public static Skill metadataOnly(String name, String description, List<String> tags, Path filePath) {
        return new Skill(name, description, tags, filePath, null);
    }

    public Skill withContent(String content) {
        return new Skill(name, description, tags, filePath, content);
    }

    public boolean isContentLoaded() {
        return content != null;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getTags() {
        return tags;
    }

    public Path getFilePath() {
        return filePath;
    }

    /** Full markdown body (without frontmatter). Null if not yet loaded. */
    public String getContent() {
        return content;
    }

    public boolean hasTag(String tag) {
        if (tag == null) return false;
        for (String t : tags) {
            if (t.equalsIgnoreCase(tag)) return true;
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Skill)) return false;
        Skill skill = (Skill) o;
        return Objects.equals(name, skill.name) && Objects.equals(filePath, skill.filePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, filePath);
    }

    @Override
    public String toString() {
        return "Skill{name='" + name + "', description='" + description + "', tags=" + tags
                + ", filePath=" + filePath + ", contentLoaded=" + isContentLoaded() + "}";
    }
}
