package com.taiwei.aiagent.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 会话磁盘持久化存储
 * 每个会话单独存储为 {sessionId}.json，索引文件记录会话元数据
 * 写入操作经过 debounce 合并，避免频繁磁盘 IO
 */
public class SessionStore {

    private static final Logger LOG = Logger.getInstance(SessionStore.class);
    private static final int MAX_SESSIONS = 5;
    private static final String STORE_DIR = ".taiwei";
    private static final String SESSIONS_DIR = "sessions";
    private static final String INDEX_FILE = "sessions_index.json";
    private static final String OLD_STORE_FILE = "sessions.json";
    private static final String LEGACY_STORE_FILE = ".ai-agent-sessions.json";
    private static final long DEBOUNCE_DELAY_MS = 2000;

    private final Path storeDir;
    private final Path sessionsDir;
    private final Path indexPath;
    private final Gson gson;
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> pendingSave;
    private volatile List<SessionData> pendingData;

    public SessionStore(String basePath) {
        this.storeDir = Paths.get(basePath, STORE_DIR);
        this.sessionsDir = storeDir.resolve(SESSIONS_DIR);
        this.indexPath = storeDir.resolve(INDEX_FILE);
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "taiwei-session-save");
            t.setDaemon(true);
            return t;
        });
        migrateIfNeeded(basePath);
    }

    private void migrateIfNeeded(String basePath) {
        try {
            if (Files.exists(indexPath)) return;

            Path oldFile = Paths.get(basePath, STORE_DIR, OLD_STORE_FILE);
            Path legacyFile = Paths.get(basePath, LEGACY_STORE_FILE);
            Path oldDotTaiwei = Paths.get(basePath, ".taiwei");

            Path source = null;
            if (Files.exists(oldFile)) {
                source = oldFile;
            } else if (Files.exists(legacyFile)) {
                source = legacyFile;
            } else if (Files.exists(oldDotTaiwei) && Files.isRegularFile(oldDotTaiwei)) {
                source = oldDotTaiwei;
            }

            if (source != null) {
                String json = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
                Type type = new TypeToken<List<SessionData>>() {}.getType();
                List<SessionData> sessions = gson.fromJson(json, type);
                if (sessions != null && !sessions.isEmpty()) {
                    // If the legacy store IS the `.taiwei` regular file, it must be removed first —
                    // otherwise createDirectories(".taiwei/sessions") fails because the parent is a file.
                    if (source.equals(storeDir)) {
                        Files.delete(source);
                    }
                    Files.createDirectories(sessionsDir);
                    List<IndexEntry> indexEntries = new ArrayList<>();
                    for (SessionData sd : sessions) {
                        writeSessionFile(sd);
                        indexEntries.add(new IndexEntry(sd.id, sd.title, sd.createdAt,
                                sd.mode, sd.modelIndex));
                    }
                    writeIndex(indexEntries);
                }
                Files.deleteIfExists(source);
                LOG.debug("Migrated legacy session store " + source.getFileName());
            }
        } catch (IOException e) {
            LOG.error("Failed to migrate legacy session store", e);
        }
    }

    public void save(List<SessionData> sessions) {
        List<SessionData> toSave;
        if (sessions.size() > MAX_SESSIONS) {
            toSave = new ArrayList<>(sessions.subList(sessions.size() - MAX_SESSIONS, sessions.size()));
        } else {
            toSave = new ArrayList<>(sessions);
        }

        pendingData = toSave;
        ScheduledFuture<?> prev = pendingSave;
        if (prev != null && !prev.isDone()) {
            prev.cancel(false);
        }
        pendingSave = scheduler.schedule(this::flushToDisk, DEBOUNCE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void flushToDisk() {
        List<SessionData> data = pendingData;
        if (data == null) return;
        try {
            Files.createDirectories(sessionsDir);

            List<String> existingIds = new ArrayList<>();
            if (Files.exists(sessionsDir)) {
                File[] files = sessionsDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
                if (files != null) {
                    for (File f : files) {
                        existingIds.add(f.getName().replace(".json", ""));
                    }
                }
            }

            List<String> newIds = new ArrayList<>();
            List<IndexEntry> indexEntries = new ArrayList<>();
            for (SessionData sd : data) {
                writeSessionFile(sd);
                newIds.add(sd.id);
                indexEntries.add(new IndexEntry(sd.id, sd.title, sd.createdAt,
                        sd.mode, sd.modelIndex));
            }

            for (String oldId : existingIds) {
                if (!newIds.contains(oldId)) {
                    Files.deleteIfExists(sessionsDir.resolve(oldId + ".json"));
                }
            }

            writeIndex(indexEntries);
            LOG.debug("Saved " + data.size() + " sessions");
        } catch (IOException e) {
            LOG.error("Failed to save sessions", e);
        }
    }

    private void writeSessionFile(SessionData sd) throws IOException {
        String json = gson.toJson(sd);
        Files.write(sessionsDir.resolve(sd.id + ".json"), json.getBytes(StandardCharsets.UTF_8));
    }

    private void writeIndex(List<IndexEntry> entries) throws IOException {
        String json = gson.toJson(entries);
        Files.write(indexPath, json.getBytes(StandardCharsets.UTF_8));
    }

    public List<SessionData> load() {
        if (!Files.exists(indexPath)) {
            LOG.debug("Session index does not exist; returning an empty list");
            return Collections.emptyList();
        }

        try {
            String indexJson = new String(Files.readAllBytes(indexPath), StandardCharsets.UTF_8);
            Type indexType = new TypeToken<List<IndexEntry>>() {}.getType();
            List<IndexEntry> indexEntries = gson.fromJson(indexJson, indexType);
            if (indexEntries == null || indexEntries.isEmpty()) {
                return Collections.emptyList();
            }

            List<SessionData> sessions = new ArrayList<>();
            for (IndexEntry entry : indexEntries) {
                Path sessionFile = sessionsDir.resolve(entry.id + ".json");
                if (!Files.exists(sessionFile)) continue;

                String sessionJson = new String(Files.readAllBytes(sessionFile), StandardCharsets.UTF_8);
                SessionData sd = gson.fromJson(sessionJson, SessionData.class);
                if (sd != null) {
                    sessions.add(sd);
                }
            }
            LOG.debug("Loaded " + sessions.size() + " sessions");
            return sessions;
        } catch (IOException e) {
            LOG.error("Failed to load sessions", e);
            return Collections.emptyList();
        }
    }

    public void delete(String sessionId) {
        try {
            Path sessionFile = sessionsDir.resolve(sessionId + ".json");
            Files.deleteIfExists(sessionFile);

            if (Files.exists(indexPath)) {
                String indexJson = new String(Files.readAllBytes(indexPath), StandardCharsets.UTF_8);
                Type indexType = new TypeToken<List<IndexEntry>>() {}.getType();
                List<IndexEntry> entries = gson.fromJson(indexJson, indexType);
                if (entries != null) {
                    entries.removeIf(e -> sessionId.equals(e.id));
                    writeIndex(entries);
                }
            }
        } catch (IOException e) {
            LOG.error("Failed to delete session", e);
        }
    }

    /**
     * Flushes any pending debounced save synchronously and stops the scheduler thread.
     * Called on project close; without it the last (debounced) save is silently dropped
     * and the scheduler thread outlives the project.
     */
    public void close() {
        ScheduledFuture<?> prev = pendingSave;
        if (prev != null) {
            prev.cancel(false);
        }
        scheduler.shutdown();
        flushToDisk();
    }

    public void deleteStoreFile() {
        try {
            Files.deleteIfExists(indexPath);
            if (Files.exists(sessionsDir)) {
                File[] files = sessionsDir.toFile().listFiles();
                if (files != null) {
                    for (File f : files) {
                        Files.deleteIfExists(f.toPath());
                    }
                }
                Files.deleteIfExists(sessionsDir);
            }
            LOG.debug("Deleted session store: " + storeDir);
        } catch (IOException e) {
            LOG.error("Failed to delete session store", e);
        }
    }

    // ========== 数据模型 ==========

    private static class IndexEntry {
        String id;
        String title;
        long createdAt;
        String mode;
        int modelIndex;

        IndexEntry() {}

        IndexEntry(String id, String title, long createdAt, String mode, int modelIndex) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.mode = mode;
            this.modelIndex = modelIndex;
        }
    }

    public static class SessionData {
        public String id;
        public String title;
        public long createdAt;
        public List<MessageData> messages;
        public String mode;
        public int modelIndex = -1;

        public SessionData() {}

        public SessionData(String id, String title, long createdAt, List<MessageData> messages) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.messages = messages;
        }

        public SessionData(String id, String title, long createdAt, List<MessageData> messages,
                           String mode, int modelIndex) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.messages = messages;
            this.mode = mode;
            this.modelIndex = modelIndex;
        }
    }

    public static class MessageData {
        public String role;
        public String content;
        public String toolName;
        public String toolArgs;
        public String toolResult;
        public String toolCallId;
        public List<ToolCallData> toolCalls;
        public List<ImageData> images;

        public MessageData() {}

        public MessageData(String role, String content, String toolName, String toolArgs,
                           String toolResult, String toolCallId, List<ToolCallData> toolCalls) {
            this.role = role;
            this.content = content;
            this.toolName = toolName;
            this.toolArgs = toolArgs;
            this.toolResult = toolResult;
            this.toolCallId = toolCallId;
            this.toolCalls = toolCalls;
        }
    }

    public static class ImageData {
        public String base64Data;
        public String mimeType;

        public ImageData() {}

        public ImageData(String base64Data, String mimeType) {
            this.base64Data = base64Data;
            this.mimeType = mimeType;
        }
    }

    public static class ToolCallData {
        public String id;
        public String type;
        public FunctionCallData function;

        public ToolCallData() {}

        public ToolCallData(String id, String type, FunctionCallData function) {
            this.id = id;
            this.type = type;
            this.function = function;
        }
    }

    public static class FunctionCallData {
        public String name;
        public String arguments;

        public FunctionCallData() {}

        public FunctionCallData(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }
    }
}
