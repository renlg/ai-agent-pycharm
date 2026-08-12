package com.taiwei.aiagent.skill;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.settings.AiAgentSettings;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-based skill registry with progressive (lazy) loading:
 * <ul>
 *     <li>Nothing is scanned until the first list/search/view call.</li>
 *     <li>Scanning a directory only parses frontmatter (name/description/tags), never the body.</li>
 *     <li>Full markdown content is read and cached only when a specific skill is viewed.</li>
 * </ul>
 * Thread-safe: the registry is a {@link ConcurrentHashMap}; the initial scan is guarded by a lock
 * so concurrent first callers don't scan the directory twice.
 */
public class SkillManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(SkillManager.class);

    private final Path skillsDir;
    private final ConcurrentHashMap<String, Skill> registry = new ConcurrentHashMap<>();
    private final ReentrantLock scanLock = new ReentrantLock();
    private volatile boolean scanned = false;

    /** Constructor used by the IntelliJ project-service container. */
    public SkillManager(@NotNull Project project) {
        this(defaultSkillsDir(project));
    }

    public SkillManager(Path skillsDir) {
        this.skillsDir = skillsDir;
    }

    public static SkillManager getInstance(@NotNull Project project) {
        return project.getService(SkillManager.class);
    }

    private static Path defaultSkillsDir(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            // Default-project / light-edit contexts have no base path; Paths.get(null, ...) would NPE
            // and fail the whole service instantiation.
            basePath = System.getProperty("user.home");
        }
        return Paths.get(basePath, ".taiwei", "skills");
    }

    public Path getSkillsDir() {
        return skillsDir;
    }

    // ========== List / View / Search ==========

    /** Metadata only (name, description, tags) — never triggers a content load. */
    public List<Skill> listSkills() {
        ensureScanned();
        List<Skill> skills = new ArrayList<>(registry.values());
        skills.sort(Comparator.comparing(Skill::getName, String.CASE_INSENSITIVE_ORDER));
        return skills;
    }

    public int getSkillCount() {
        ensureScanned();
        return registry.size();
    }

    /** Loads (and caches) the full content of a skill by exact name. */
    public Optional<Skill> getSkill(String name) {
        ensureScanned();
        Skill meta = registry.get(name);
        if (meta == null) return Optional.empty();
        if (meta.isContentLoaded()) return Optional.of(meta);

        Skill loaded = registry.compute(name, (key, current) -> {
            if (current == null) return null;
            if (current.isContentLoaded()) return current;
            try {
                return SkillFrontmatterParser.parseFull(current.getFilePath());
            } catch (IOException e) {
                LOG.warn("Failed to load skill content: " + current.getFilePath(), e);
                return current;
            }
        });
        return Optional.ofNullable(loaded);
    }

    /** Matches query against skill name or tags (case-insensitive, substring). */
    public List<Skill> searchSkills(String query) {
        ensureScanned();
        if (query == null || query.isBlank()) return listSkills();

        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Skill> result = new ArrayList<>();
        for (Skill skill : registry.values()) {
            boolean nameMatch = skill.getName().toLowerCase(Locale.ROOT).contains(q);
            boolean tagMatch = skill.getTags().stream()
                    .anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(q));
            if (nameMatch || tagMatch) {
                result.add(skill);
            }
        }
        result.sort(Comparator.comparing(Skill::getName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    // ========== Add / Remove ==========

    /** Writes a new skill markdown file (frontmatter + body) into the skills directory. */
    public Skill addSkill(String fileName, String markdownContent) throws IOException {
        ensureScanned();
        ensureDirExists();
        String safeName = sanitizeFileName(fileName);
        if (!safeName.endsWith(".md")) safeName = safeName + ".md";

        Path target = skillsDir.resolve(safeName);
        Files.write(target, markdownContent.getBytes(StandardCharsets.UTF_8));

        Skill meta = SkillFrontmatterParser.parseMetadata(target);
        registry.put(meta.getName(), meta);
        return meta;
    }

    /** Deletes the skill's backing file and removes it from the registry. */
    public boolean removeSkill(String name) throws IOException {
        ensureScanned();
        Skill skill = registry.get(name);
        if (skill == null) return false;
        Files.deleteIfExists(skill.getFilePath());
        registry.remove(name);
        return true;
    }

    public void refresh() {
        scanLock.lock();
        try {
            registry.clear();
            scanDirectory();
            scanned = true;
        } finally {
            scanLock.unlock();
        }
    }

    // ========== Prompt injection summary (metadata only, progressive disclosure) ==========

    public String buildSummaryContext() {
        ensureScanned();
        if (registry.isEmpty()) return "";
        AiAgentSettings settings = AiAgentSettings.getInstance();
        StringBuilder sb = new StringBuilder();
        for (Skill skill : listSkills()) {
            if (!settings.isSkillEnabled(skill.getName())) continue;
            sb.append("- ").append(skill.getName());
            if (!skill.getDescription().isEmpty()) {
                sb.append(": ").append(skill.getDescription());
            }
            if (!skill.getTags().isEmpty()) {
                sb.append(" [").append(String.join(", ", skill.getTags())).append("]");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // ========== Lazy scan ==========

    private void ensureScanned() {
        if (scanned) return;
        scanLock.lock();
        try {
            if (scanned) return;
            scanDirectory();
            scanned = true;
        } finally {
            scanLock.unlock();
        }
    }

    private void scanDirectory() {
        try {
            if (!Files.exists(skillsDir)) {
                Files.createDirectories(skillsDir);
                return;
            }
            try (var paths = Files.walk(skillsDir)) {
                for (Path file : (Iterable<Path>) paths::iterator) {
                    if (!Files.isRegularFile(file)) continue;
                    if (!file.getFileName().toString().endsWith(".md")) continue;
                    try {
                        Skill meta = SkillFrontmatterParser.parseMetadata(file);
                        registry.put(meta.getName(), meta);
                    } catch (IOException e) {
                        LOG.warn("Failed to parse skill metadata: " + file, e);
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to scan skills directory: " + skillsDir, e);
        }
    }

    private void ensureDirExists() throws IOException {
        if (!Files.exists(skillsDir)) {
            Files.createDirectories(skillsDir);
        }
    }

    private String sanitizeFileName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("Skill file name must not be blank");
        }
        String base = Paths.get(rawName).getFileName().toString();
        if (base.isBlank() || base.equals(".") || base.equals("..")) {
            throw new IllegalArgumentException("Invalid skill file name: " + rawName);
        }
        return base;
    }

    @Override
    public void dispose() {
        // No background resources to release; skills are loaded via manual refresh().
    }
}
