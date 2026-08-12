package com.taiwei.aiagent.memory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQLite-backed persistence for {@link MemoryEntry}. All access goes through a single JDBC
 * connection guarded by {@code lock}, since SQLite connections aren't safe for concurrent use.
 */
public class MemoryStore implements AutoCloseable {

    private static final Logger LOG = Logger.getInstance(MemoryStore.class);
    private static final Gson GSON = new Gson();

    private final Connection connection;
    private final Object lock = new Object();
    /** Path of the backing DB file on disk, or {@code null} for the in-memory store used by tests. */
    private final Path dbFilePath;

    /** Opens (or creates) a SQLite database file on disk, creating parent directories as needed. */
    public MemoryStore(Path dbFile) {
        this(jdbcUrlForFile(dbFile), dbFile);
    }

    /** Opens a private in-memory SQLite database. Used by tests to avoid touching disk. */
    public MemoryStore() {
        this("jdbc:sqlite::memory:", null);
    }

    private MemoryStore(String jdbcUrl, Path dbFilePath) {
        this.dbFilePath = dbFilePath;
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection(jdbcUrl);
            createSchema();
        } catch (ClassNotFoundException | SQLException e) {
            throw new IllegalStateException("Failed to open memory store: " + jdbcUrl, e);
        }
    }

    private static String jdbcUrlForFile(Path dbFile) {
        try {
            Path parent = dbFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create memory store directory: " + dbFile.getParent(), e);
        }
        return "jdbc:sqlite:" + dbFile.toAbsolutePath();
    }

    private void createSchema() throws SQLException {
        synchronized (lock) {
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS memories (" +
                        "id TEXT PRIMARY KEY," +
                        "content TEXT NOT NULL," +
                        "category TEXT NOT NULL," +
                        "tags TEXT NOT NULL," +
                        "importance INTEGER NOT NULL," +
                        "access_count INTEGER NOT NULL," +
                        "created_at INTEGER NOT NULL," +
                        "updated_at INTEGER NOT NULL," +
                        "last_accessed_at INTEGER NOT NULL" +
                        ")");
                st.execute("CREATE INDEX IF NOT EXISTS idx_memories_category ON memories(category)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_memories_importance ON memories(importance)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_memories_last_accessed ON memories(last_accessed_at)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_memories_tags ON memories(tags)");
                st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(" +
                        "id UNINDEXED, content, tags)");
            }
            ensureEmbeddingColumn();
        }
    }

    /** Migration for pre-embedding databases: adds the {@code embedding BLOB} column if missing. */
    private void ensureEmbeddingColumn() throws SQLException {
        boolean hasColumn = false;
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(memories)")) {
            while (rs.next()) {
                if ("embedding".equalsIgnoreCase(rs.getString("name"))) {
                    hasColumn = true;
                    break;
                }
            }
        }
        if (!hasColumn) {
            try (Statement st = connection.createStatement()) {
                st.execute("ALTER TABLE memories ADD COLUMN embedding BLOB");
            }
        }
    }

    public void insert(MemoryEntry entry) {
        synchronized (lock) {
            String sql = "INSERT INTO memories " +
                    "(id, content, category, tags, importance, access_count, created_at, updated_at, last_accessed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, entry.getId());
                bindMutableFields(ps, entry, 2);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to insert memory: " + entry.getId(), e);
            }
            insertFts(entry.getId(), entry.getContent(), GSON.toJson(entry.getTags()));
        }
    }

    public void update(MemoryEntry entry) {
        synchronized (lock) {
            String sql = "UPDATE memories SET content=?, category=?, tags=?, importance=?, " +
                    "access_count=?, created_at=?, updated_at=?, last_accessed_at=? WHERE id=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bindMutableFields(ps, entry, 1);
                ps.setString(9, entry.getId());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to update memory: " + entry.getId(), e);
            }
            deleteFtsById(entry.getId());
            insertFts(entry.getId(), entry.getContent(), GSON.toJson(entry.getTags()));
        }
    }

    public boolean deleteById(String id) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM memories WHERE id=?")) {
                ps.setString(1, id);
                boolean deleted = ps.executeUpdate() > 0;
                if (deleted) {
                    deleteFtsById(id);
                }
                return deleted;
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to delete memory: " + id, e);
            }
        }
    }

    public List<MemoryEntry> findAll() {
        synchronized (lock) {
            List<MemoryEntry> result = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM memories")) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to query memories", e);
            }
            return result;
        }
    }

    public List<MemoryEntry> findAll(int limit) {
        synchronized (lock) {
            List<MemoryEntry> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM memories ORDER BY importance DESC, last_accessed_at DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to query memories", e);
            }
            return result;
        }
    }

    public List<MemoryEntry> findByCategory(String category) {
        synchronized (lock) {
            List<MemoryEntry> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT * FROM memories WHERE category = ? ORDER BY updated_at DESC")) {
                ps.setString(1, category);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to query memories by category: " + category, e);
            }
            return result;
        }
    }

    public List<MemoryEntry> searchByKeyword(String keyword) {
        synchronized (lock) {
            List<String> tokens = new ArrayList<>();
            for (String token : keyword.split("[^\\p{IsHan}\\p{L}\\p{N}]+")) {
                if (token.length() >= 2) {
                    tokens.add("\"" + token.replace("\"", "") + "\"");
                }
            }
            if (tokens.isEmpty()) return List.of();
            String ftsQuery = String.join(" OR ", tokens);

            String sql = "SELECT m.* FROM memories m " +
                    "JOIN memories_fts fts ON m.id = fts.id " +
                    "WHERE memories_fts MATCH ? ORDER BY m.updated_at DESC";
            List<MemoryEntry> result = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, ftsQuery);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(mapRow(rs));
                }
            } catch (SQLException e) {
                LOG.warn("FTS5 search failed; falling back to LIKE: " + e.getMessage());
                return searchByKeywordFallback(keyword);
            }
            return result;
        }
    }

    private List<MemoryEntry> searchByKeywordFallback(String keyword) {
        List<MemoryEntry> result = new ArrayList<>();
        String pattern = "%" + keyword + "%";
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM memories WHERE content LIKE ? OR tags LIKE ? ORDER BY updated_at DESC")) {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to search memories by keyword: " + keyword, e);
        }
        return result;
    }

    private void insertFts(String id, String content, String tags) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO memories_fts(id, content, tags) VALUES (?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, content);
            ps.setString(3, tags);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to insert FTS entry for " + id + "; base memory was still saved: " + e.getMessage());
        }
    }

    private void deleteFtsById(String id) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM memories_fts WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Failed to delete FTS entry for " + id + ": " + e.getMessage());
        }
    }

    /** Stores (or replaces) the embedding vector for an existing memory row. */
    public void saveEmbedding(String id, float[] vector) {
        if (vector == null || vector.length == 0) return;
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE memories SET embedding=? WHERE id=?")) {
                ps.setBytes(1, vectorToBytes(vector));
                ps.setString(2, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to save embedding for memory: " + id, e);
            }
        }
    }

    /** Loads every stored embedding as {@code memory id -> vector}. Undecodable blobs are skipped. */
    public Map<String, float[]> loadAllEmbeddings() {
        synchronized (lock) {
            Map<String, float[]> result = new HashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, embedding FROM memories WHERE embedding IS NOT NULL")) {
                while (rs.next()) {
                    float[] vector = bytesToVector(rs.getBytes(2));
                    if (vector != null) {
                        result.put(rs.getString(1), vector);
                    }
                }
            } catch (SQLException e) {
                LOG.warn("Failed to load stored embeddings; semantic search will be unavailable: " + e.getMessage());
            }
            return result;
        }
    }

    private static byte[] vectorToBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    private static float[] bytesToVector(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length % Float.BYTES != 0) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    public Optional<MemoryEntry> findById(String id) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM memories WHERE id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return Optional.of(mapRow(rs));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to query memory: " + id, e);
            }
            return Optional.empty();
        }
    }

    public int count() {
        synchronized (lock) {
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM memories")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to count memories", e);
            }
        }
    }

    /** Size in bytes of the backing DB file on disk, or 0 for the in-memory store. */
    public long getFileSizeBytes() {
        if (dbFilePath == null) return 0L;
        try {
            return Files.size(dbFilePath);
        } catch (IOException e) {
            return 0L;
        }
    }

    private void bindMutableFields(PreparedStatement ps, MemoryEntry entry, int startIndex) throws SQLException {
        int i = startIndex;
        ps.setString(i++, entry.getContent());
        ps.setString(i++, entry.getCategory().name());
        ps.setString(i++, GSON.toJson(entry.getTags()));
        ps.setInt(i++, entry.getImportance());
        ps.setInt(i++, entry.getAccessCount());
        ps.setLong(i++, entry.getCreatedAt());
        ps.setLong(i++, entry.getUpdatedAt());
        ps.setLong(i, entry.getLastAccessedAt());
    }

    private MemoryEntry mapRow(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String content = rs.getString("content");
        MemoryCategory category = MemoryCategory.valueOf(rs.getString("category"));
        List<String> tags = GSON.fromJson(rs.getString("tags"), new TypeToken<List<String>>() {
        }.getType());
        int importance = rs.getInt("importance");
        int accessCount = rs.getInt("access_count");
        long createdAt = rs.getLong("created_at");
        long updatedAt = rs.getLong("updated_at");
        long lastAccessedAt = rs.getLong("last_accessed_at");
        return new MemoryEntry(id, content, category, tags, importance, accessCount, createdAt, updatedAt, lastAccessedAt);
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOG.warn("Failed to close memory store connection: " + e.getMessage());
            }
        }
    }
}
