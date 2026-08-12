package com.taiwei.aiagent.memory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User-editable memory configuration backed by {@code .taiwei/memory-config.json} under the
 * project base path. The file is re-read lazily with a short TTL so edits made while the IDE
 * is running take effect within ~30 seconds, without a disk read on every call.
 *
 * <pre>{"maxMemoryCount": 200}</pre>
 *
 * Missing file, unreadable JSON or non-positive values all fall back to
 * {@link #DEFAULT_MAX_MEMORY_COUNT}. A default file is written on first access so users can
 * discover and edit it.
 */
public final class MemoryConfig {

    private static final Logger LOG = Logger.getInstance(MemoryConfig.class);

    public static final int DEFAULT_MAX_MEMORY_COUNT = 200;

    /** How long a value read from disk stays cached before the file is consulted again. */
    private static final long CACHE_TTL_MS = 30_000L;

    /** One instance per project base path, so all services in a project share the TTL cache. */
    private static final Map<String, MemoryConfig> INSTANCES = new ConcurrentHashMap<>();

    /** Backing JSON file, or {@code null} for a purely in-memory config that always returns defaults. */
    private final Path configFile;

    private volatile int cachedMaxMemoryCount = DEFAULT_MAX_MEMORY_COUNT;
    private volatile long lastReadAtMillis = 0L;

    private MemoryConfig(Path configFile) {
        this.configFile = configFile;
        if (configFile != null && !Files.exists(configFile)) {
            writeDefaultFile();
        }
    }

    public static MemoryConfig getInstance(Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            basePath = System.getProperty("user.home");
        }
        return INSTANCES.computeIfAbsent(basePath,
                bp -> new MemoryConfig(Paths.get(bp, ".taiwei", "memory-config.json")));
    }

    /** Config with no backing file; always returns defaults. Used when no project context exists. */
    static MemoryConfig defaults() {
        return new MemoryConfig(null);
    }

    /**
     * Maximum number of memories to retain before consolidation/forgetting kicks in.
     * Re-reads the JSON file at most once per {@link #CACHE_TTL_MS}.
     */
    public int getMaxMemoryCount() {
        refreshIfStale();
        return cachedMaxMemoryCount;
    }

    private void refreshIfStale() {
        if (configFile == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastReadAtMillis < CACHE_TTL_MS) {
            return;
        }
        lastReadAtMillis = now;
        int value = DEFAULT_MAX_MEMORY_COUNT;
        try {
            if (Files.exists(configFile)) {
                String json = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                if (obj.has("maxMemoryCount")) {
                    int parsed = obj.get("maxMemoryCount").getAsInt();
                    if (parsed > 0) {
                        value = parsed;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to read " + configFile + ", using default maxMemoryCount="
                    + DEFAULT_MAX_MEMORY_COUNT + ": " + e.getMessage());
        }
        cachedMaxMemoryCount = value;
    }

    /** Best-effort: seed the config file with defaults so the user has something to edit. */
    private void writeDefaultFile() {
        try {
            Path parent = configFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(configFile,
                    ("{\n  \"maxMemoryCount\": " + DEFAULT_MAX_MEMORY_COUNT + "\n}\n")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOG.warn("Failed to create default memory config at " + configFile + ": " + e.getMessage());
        }
    }
}
