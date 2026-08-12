package com.taiwei.aiagent.memory;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.util.I18nUtil;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Local long-term memory service with hybrid retrieval: SQLite FTS keyword search fused (RRF)
 * with cosine similarity over API-generated embeddings (see {@link EmbeddingService} — no vector
 * DB dependency). Project-level service backed by a per-project SQLite file under
 * {@code .taiwei/memories/memory.db}.
 */
public class MemoryManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(MemoryManager.class);

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsHan}\\p{L}\\p{N}]+");
    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;
    private static final long DECAY_PERIOD_MILLIS = 30 * DAY_MILLIS;

    /** RRF constant: fused score = Σ 1/(K + rank) over each ranking a memory appears in. */
    private static final int RRF_K = 60;

    /** Max {@link #matchScore} contribution per query token: 1.0 content hit + 2.0 for a tag hit. */
    private static final double MAX_SCORE_PER_TOKEN = 3.0;

    /** Default floor on normalized keyword relevance (matchScore / max attainable), 0..1 scale. */
    public static final double DEFAULT_MIN_KEYWORD_RELEVANCE = 0.15;

    /** Floor on cosine similarity for a vector match to count as relevant. */
    private static final double MIN_VECTOR_SIMILARITY = 0.3;

    /** How long a search may block waiting for its query embedding before going keyword-only. */
    private static final long QUERY_EMBED_TIMEOUT_MS = 5_000L;

    /** Default cap on the on-disk size of the memory database. */
    public static final long MAX_STORAGE_BYTES = 50 * 1024 * 1024;

    /** Count fraction of maxMemoryCount at which auto-consolidation (semantic merge) kicks in. */
    private static final double CONSOLIDATE_THRESHOLD = 0.80;

    /** Count fraction of maxMemoryCount at which forgetting (batch deletion) kicks in. */
    private static final double FORGET_THRESHOLD = 0.95;

    /** Minimum interval between two count-triggered auto-consolidations. */
    private static final long CONSOLIDATE_DEBOUNCE_MS = 600_000L;

    private final MemoryStore store;
    private final MemoryConfig memoryConfig;
    private volatile long maxStorageBytes = MAX_STORAGE_BYTES;
    /** When the last count-triggered semantic merge ran; guards the debounce window. */
    private volatile long lastConsolidateAtMillis = 0L;

    /** Query text -> embedding, so repeated searches for the same query skip the API call. */
    private final Map<String, float[]> embeddingCache = new ConcurrentHashMap<>();
    /** Memory id -> stored embedding, loaded from SQLite on init and kept in sync with the DB. */
    private final Map<String, float[]> storedEmbeddings;
    /** Query -> in-flight embedding generation, so concurrent searches share one API call. */
    private final Map<String, CompletableFuture<float[]>> pendingQueryEmbeddings = new ConcurrentHashMap<>();

    /**
     * Runs embedding generation off the caller's thread: API-backed embedding can take seconds,
     * so {@link #remember} never waits on it and {@link #hybridSearch} waits at most
     * {@link #QUERY_EMBED_TIMEOUT_MS}. Single daemon thread — embeddings are best-effort and
     * keyword search covers the gap until they land.
     */
    private final ScheduledExecutorService embeddingExecutor = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "memory-embedder");
        t.setDaemon(true);
        return t;
    });

    /** Constructor used by the IntelliJ project-service container. */
    public MemoryManager(@NotNull Project project) {
        this(new MemoryStore(defaultMemoryDbPath(project)), MemoryConfig.getInstance(project));
    }

    public MemoryManager(MemoryStore store) {
        this(store, MemoryConfig.defaults());
    }

    public MemoryManager(MemoryStore store, MemoryConfig memoryConfig) {
        this.store = store;
        this.memoryConfig = memoryConfig;
        this.storedEmbeddings = new ConcurrentHashMap<>(store.loadAllEmbeddings());
    }

    public static MemoryManager getInstance(@NotNull Project project) {
        return project.getService(MemoryManager.class);
    }

    private static Path defaultMemoryDbPath(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            // Default-project / light-edit contexts have no base path; Paths.get(null, ...) would NPE
            // and fail the whole service instantiation.
            basePath = System.getProperty("user.home");
        }
        return Paths.get(basePath, ".taiwei", "memories", "memory.db");
    }

    // ========== Core CRUD ==========

    public MemoryEntry remember(String content, MemoryCategory category, List<String> tags, int importance) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Memory content must not be blank");
        }
        enforceMemoryCountLimit();
        // Secondary limit: on-disk size of the database file.
        if (maxStorageBytes > 0 && getStorageUsageBytes() >= maxStorageBytes) {
            autoConsolidate();
            if (getStorageUsageBytes() >= maxStorageBytes) {
                LOG.warn("Rejecting new memory: storage usage " + getStorageUsageBytes()
                        + " bytes has reached the limit of " + maxStorageBytes
                        + " bytes even after auto-consolidation");
                throw new IllegalStateException(
                        "Memory storage limit reached (" + maxStorageBytes + " bytes); new memory was not saved");
            }
        }
        long now = System.currentTimeMillis();
        MemoryEntry entry = MemoryEntry.create(
                UUID.randomUUID().toString(),
                content.trim(),
                category == null ? MemoryCategory.FACT : category,
                tags == null ? List.of() : tags,
                clampImportance(importance),
                now);
        store.insert(entry);
        // Best-effort: a memory without an embedding is still searchable by keyword.
        embeddingExecutor.execute(() -> generateAndStoreEmbedding(entry.getId(), entry.getContent()));
        return entry;
    }

    /**
     * Background task: embeds {@code content} and persists the vector. No retry on failure —
     * the memory stays keyword-searchable without an embedding.
     */
    private void generateAndStoreEmbedding(String id, String content) {
        try {
            float[] vector = EmbeddingService.getInstance().embed(content);
            if (vector != null) {
                store.saveEmbedding(id, vector);
                storedEmbeddings.put(id, vector);
            }
        } catch (Exception e) {
            LOG.warn("Failed to store embedding for memory " + id + ": " + e.getMessage());
        }
    }

    public boolean forget(String id) {
        boolean deleted = store.deleteById(id);
        if (deleted) {
            storedEmbeddings.remove(id);
        }
        return deleted;
    }

    /** Deletes every memory whose content or tags match the given keyword(s). Returns the number deleted. */
    public int forgetByQuery(String query) {
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) return 0;
        int deleted = 0;
        for (MemoryEntry entry : store.findAll()) {
            if (matchScore(tokens, entry) > 0) {
                store.deleteById(entry.getId());
                storedEmbeddings.remove(entry.getId());
                deleted++;
            }
        }
        return deleted;
    }

    public List<MemoryEntry> list(MemoryCategory category, int limit) {
        List<MemoryEntry> entries = (category == null)
                ? store.findAll()
                : store.findByCategory(category.name());
        entries.sort(Comparator.comparingLong(MemoryEntry::getLastAccessedAt).reversed());
        return limit > 0 && entries.size() > limit ? entries.subList(0, limit) : entries;
    }

    public List<String> getAllTags() {
        Set<String> tags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (MemoryEntry entry : store.findAll()) {
            tags.addAll(entry.getTags());
        }
        return new ArrayList<>(tags);
    }

    public int getMemoryCount() {
        return store.count();
    }

    // ========== Search / Retrieval ==========

    /** Explicit keyword search across content + tags, sorted by relevance. Bumps access stats on returned entries. */
    public List<MemoryEntry> recall(String query, int limit) {
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) return List.of();
        return scoreAndRank(tokens, store.searchByKeyword(query), limit, true);
    }

    /**
     * Hybrid retrieval: keyword search and embedding cosine similarity are ranked separately,
     * fused with Reciprocal Rank Fusion (score = Σ 1/(60 + rank)), then re-ranked with an
     * importance/recency/access-count boost. An uncached query embedding is generated
     * synchronously with a bounded wait ({@link #QUERY_EMBED_TIMEOUT_MS}) so even the first
     * search gets semantic matches; on timeout or error this degrades gracefully to
     * keyword-only ranking while the embedding finishes in the background and is cached for
     * later identical searches.
     *
     * <p>A relevance floor is applied at fusion time: an entry is dropped only when it clears
     * neither the keyword threshold ({@code minRelevance}, on the normalized 0..1 matchScore
     * scale) nor the vector threshold ({@link #MIN_VECTOR_SIMILARITY}). Passing either one is
     * enough, so keyword-only matches survive the async window before embeddings exist. If
     * every candidate fails both, the result is empty.
     *
     * @param query    search text
     * @param category optional category filter (case-insensitive name like "preference"), null/blank = all
     * @param limit    max results
     * @return numbered result list (content, category, importance, relative age), or "" if nothing matched
     */
    public String hybridSearch(String query, String category, int limit) {
        return hybridSearch(query, category, limit, DEFAULT_MIN_KEYWORD_RELEVANCE);
    }

    /**
     * Same as {@link #hybridSearch(String, String, int)} with an explicit keyword relevance floor.
     *
     * @param minRelevance minimum normalized keyword relevance (0..1); negative values fall back
     *                     to {@link #DEFAULT_MIN_KEYWORD_RELEVANCE}
     */
    public String hybridSearch(String query, String category, int limit, double minRelevance) {
        if (query == null || query.isBlank()) return "";
        if (limit <= 0) limit = 5;
        if (minRelevance < 0) minRelevance = DEFAULT_MIN_KEYWORD_RELEVANCE;
        MemoryCategory categoryFilter = parseCategory(category);

        Map<String, MemoryEntry> byId = new HashMap<>();
        for (MemoryEntry entry : store.findAll()) {
            byId.put(entry.getId(), entry);
        }
        if (byId.isEmpty()) return "";

        // Fuse the two rankings with RRF, then drop entries that clear neither relevance floor.
        Ranking keyword = keywordRanking(query, minRelevance);
        Ranking vector = vectorRanking(query, byId.keySet());
        Map<String, Double> fused = new HashMap<>();
        addRrfScores(fused, keyword.rankedIds);
        addRrfScores(fused, vector.rankedIds);
        fused.keySet().removeIf(id -> !keyword.passingIds.contains(id) && !vector.passingIds.contains(id));
        if (fused.isEmpty()) return "";

        // Post-fusion re-rank: boost by importance, recency and access count.
        long now = System.currentTimeMillis();
        List<ScoredEntry> scored = new ArrayList<>();
        for (Map.Entry<String, Double> fusedEntry : fused.entrySet()) {
            MemoryEntry entry = byId.get(fusedEntry.getKey());
            if (entry == null) continue;
            if (categoryFilter != null && entry.getCategory() != categoryFilter) continue;
            double boost = 1.0
                    + entry.getImportance() * 0.05
                    + recencyScore(entry.getLastAccessedAt(), now) * 0.10
                    + Math.log(1 + entry.getAccessCount()) * 0.02;
            scored.add(new ScoredEntry(entry, fusedEntry.getValue() * boost));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < scored.size() && i < limit; i++) {
            MemoryEntry entry = scored.get(i).entry;
            store.update(entry.withAccessed(now));
            sb.append(i + 1)
                    .append(". [").append(entry.getCategory().name().toLowerCase(Locale.ROOT)).append("] ")
                    .append(entry.getContent())
                    .append(I18nUtil.getMessage("memory.search.metadata", entry.getImportance(), relativeAge(entry.getCreatedAt(), now))).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Memory ids ordered by keyword relevance (best first); empty when nothing matches.
     * {@code passingIds} holds the subset whose matchScore, normalized by the maximum
     * attainable for the query ({@link #MAX_SCORE_PER_TOKEN} × token count), reaches
     * {@code minRelevance} — raw matchScore is an unbounded hit count, so the threshold
     * operates on the normalized 0..1 scale.
     */
    private Ranking keywordRanking(String query, double minRelevance) {
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) return Ranking.EMPTY;
        List<ScoredEntry> scored = new ArrayList<>();
        for (MemoryEntry entry : store.searchByKeyword(query)) {
            double relevance = matchScore(tokens, entry);
            if (relevance > 0) {
                scored.add(new ScoredEntry(entry, relevance));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        List<String> ids = new ArrayList<>(scored.size());
        Set<String> passing = new HashSet<>();
        double maxScore = MAX_SCORE_PER_TOKEN * tokens.size();
        for (ScoredEntry s : scored) {
            ids.add(s.entry.getId());
            if (s.score / maxScore >= minRelevance) {
                passing.add(s.entry.getId());
            }
        }
        return new Ranking(ids, passing);
    }

    /**
     * Memory ids ordered by cosine similarity to the query embedding; empty when unavailable.
     * {@code passingIds} holds the subset at or above {@link #MIN_VECTOR_SIMILARITY}.
     * Blocks at most {@link #QUERY_EMBED_TIMEOUT_MS} for an uncached query embedding; on
     * timeout the generation keeps running in the background (cached for later searches)
     * while this search degrades to keyword-only.
     */
    private Ranking vectorRanking(String query, Set<String> candidateIds) {
        if (storedEmbeddings.isEmpty()) return Ranking.EMPTY;
        float[] queryVector = embeddingCache.get(query);
        if (queryVector == null) {
            queryVector = awaitQueryEmbedding(query);
            if (queryVector == null) return Ranking.EMPTY;
        }

        List<Map.Entry<String, Double>> similarities = new ArrayList<>();
        for (Map.Entry<String, float[]> stored : storedEmbeddings.entrySet()) {
            if (!candidateIds.contains(stored.getKey())) continue;
            double similarity = EmbeddingService.cosineSimilarity(queryVector, stored.getValue());
            if (similarity > 0) {
                similarities.add(Map.entry(stored.getKey(), similarity));
            }
        }
        similarities.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> ids = new ArrayList<>(similarities.size());
        Set<String> passing = new HashSet<>();
        for (Map.Entry<String, Double> s : similarities) {
            ids.add(s.getKey());
            if (s.getValue() >= MIN_VECTOR_SIMILARITY) {
                passing.add(s.getKey());
            }
        }
        return new Ranking(ids, passing);
    }

    /**
     * Embeds a search query on the embedding executor and waits up to
     * {@link #QUERY_EMBED_TIMEOUT_MS} for the result. Concurrent searches for the same query
     * share one in-flight generation. Returns {@code null} on timeout or failure — the
     * generation itself is not cancelled, so a late result still lands in
     * {@link #embeddingCache} for the next identical search.
     */
    private float[] awaitQueryEmbedding(String query) {
        CompletableFuture<float[]> future = pendingQueryEmbeddings.computeIfAbsent(query, q -> {
            CompletableFuture<float[]> pending = new CompletableFuture<>();
            embeddingExecutor.execute(() -> {
                try {
                    float[] vector = EmbeddingService.getInstance().embed(q);
                    if (vector != null) {
                        embeddingCache.put(q, vector);
                    }
                    pending.complete(vector);
                } catch (Exception e) {
                    LOG.warn("Query embedding failed, keyword-only search will be used: " + e.getMessage());
                    pending.complete(null);
                } finally {
                    pendingQueryEmbeddings.remove(q);
                }
            });
            return pending;
        });
        try {
            return future.get(QUERY_EMBED_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.debug("Query embedding not ready within " + QUERY_EMBED_TIMEOUT_MS
                    + "ms, falling back to keyword-only search");
            return null;
        } catch (Exception e) {
            LOG.warn("Query embedding wait failed, keyword-only search will be used: " + e.getMessage());
            return null;
        }
    }

    private static void addRrfScores(Map<String, Double> fused, List<String> rankedIds) {
        for (int rank = 0; rank < rankedIds.size(); rank++) {
            fused.merge(rankedIds.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
    }

    private static MemoryCategory parseCategory(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return MemoryCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String relativeAge(long timestamp, long now) {
        long minutes = Math.max(0, now - timestamp) / (60 * 1000);
        if (minutes < 1) return I18nUtil.getMessage("memory.age.justNow");
        if (minutes < 60) return I18nUtil.getMessage("memory.age.minutes", minutes);
        long hours = minutes / 60;
        if (hours < 24) return I18nUtil.getMessage("memory.age.hours", hours);
        long days = hours / 24;
        if (days < 30) return I18nUtil.getMessage("memory.age.days", days);
        if (days < 365) return I18nUtil.getMessage("memory.age.months", days / 30);
        return I18nUtil.getMessage("memory.age.years", days / 365);
    }

    /** Used for automatic prompt injection: finds memories relevant to the current chat message. */
    public List<MemoryEntry> getRelevantMemories(String context, int limit) {
        return search(context, limit, true);
    }

    /** Formats memories relevant to {@code userMessage} as a "## Memory" style bullet list for prompt injection. */
    public String buildPromptContext(String userMessage, int limit) {
        List<MemoryEntry> memories = getRelevantMemories(userMessage, limit);
        if (memories.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry entry : memories) {
            sb.append("- ").append(entry.getContent());
            if (!entry.getTags().isEmpty()) {
                sb.append(" [").append(String.join(", ", entry.getTags())).append("]");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private List<MemoryEntry> search(String text, int limit, boolean touch) {
        Set<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return List.of();
        List<MemoryEntry> ftsResults = store.searchByKeyword(text);
        if (!ftsResults.isEmpty()) {
            return scoreAndRank(tokens, ftsResults, limit, touch);
        }
        return scoreAndRank(tokens, store.findAll(50), limit, touch);
    }

    private List<MemoryEntry> scoreAndRank(Set<String> tokens, List<MemoryEntry> candidates, int limit, boolean touch) {
        long now = System.currentTimeMillis();
        List<ScoredEntry> scored = new ArrayList<>();
        for (MemoryEntry entry : candidates) {
            double relevance = matchScore(tokens, entry);
            if (relevance <= 0) continue;
            double finalScore = relevance * 3.0
                    + entry.getImportance()
                    + recencyScore(entry.getLastAccessedAt(), now) * 2.0
                    + Math.log(1 + entry.getAccessCount()) * 0.5;
            scored.add(new ScoredEntry(entry, finalScore));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        List<MemoryEntry> result = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < limit; i++) {
            MemoryEntry entry = scored.get(i).entry;
            if (touch) {
                entry = entry.withAccessed(now);
                store.update(entry);
            }
            result.add(entry);
        }
        return result;
    }

    private double matchScore(Set<String> tokens, MemoryEntry entry) {
        String content = entry.getContent().toLowerCase(Locale.ROOT);
        double score = 0;
        for (String token : tokens) {
            if (content.contains(token)) {
                score += 1.0;
            }
            for (String tag : entry.getTags()) {
                if (tag.toLowerCase(Locale.ROOT).contains(token)) {
                    score += 2.0;
                }
            }
        }
        return score;
    }

    private double recencyScore(long lastAccessedAt, long now) {
        double daysSince = Math.max(0, (now - lastAccessedAt) / (double) DAY_MILLIS);
        return 1.0 / (1.0 + daysSince * 0.1);
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) return tokens;
        for (String token : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    // ========== Consolidation ==========

    /**
     * Primary (count-based) limit, checked on every {@link #remember}. At
     * {@link #FORGET_THRESHOLD} of the configured maxMemoryCount the least valuable memories
     * are deleted until the count drops below {@link #CONSOLIDATE_THRESHOLD} — forgetting
     * already frees enough room, so no merge follows. Between the two thresholds a semantic
     * merge ({@link #autoConsolidate}) runs instead, debounced to once per
     * {@link #CONSOLIDATE_DEBOUNCE_MS}.
     */
    private void enforceMemoryCountLimit() {
        int maxCount = memoryConfig.getMaxMemoryCount();
        if (maxCount <= 0) return;
        int count = getMemoryCount();
        if (count >= maxCount * FORGET_THRESHOLD) {
            forgetLeastValuable(maxCount);
        } else if (count >= maxCount * CONSOLIDATE_THRESHOLD) {
            consolidateWithDebounce();
        }
    }

    private void consolidateWithDebounce() {
        long now = System.currentTimeMillis();
        if (now - lastConsolidateAtMillis < CONSOLIDATE_DEBOUNCE_MS) {
            return;
        }
        lastConsolidateAtMillis = now;
        autoConsolidate();
    }

    /**
     * Batch-deletes the least valuable memories — lowest importance first, ties broken by
     * oldest last access — until the count drops below {@link #CONSOLIDATE_THRESHOLD} of
     * {@code maxCount}.
     */
    private void forgetLeastValuable(int maxCount) {
        int target = (int) (maxCount * CONSOLIDATE_THRESHOLD);
        List<MemoryEntry> all = store.findAll();
        all.sort(Comparator.comparingInt(MemoryEntry::getImportance)
                .thenComparingLong(MemoryEntry::getLastAccessedAt));
        int remaining = all.size();
        for (MemoryEntry entry : all) {
            if (remaining < target) break;
            if (store.deleteById(entry.getId())) {
                storedEmbeddings.remove(entry.getId());
                remaining--;
                LOG.debug("Forgot memory " + entry.getId()
                        + " (importance=" + entry.getImportance()
                        + ", lastAccessedAt=" + entry.getLastAccessedAt()
                        + "): " + entry.getContent());
            }
        }
    }

    /** Merges duplicate memories (same normalized content) and decays importance of stale, unaccessed entries. */
    public void autoConsolidate() {
        mergeDuplicates();
        decayStaleImportance();
    }

    private void mergeDuplicates() {
        Map<String, List<MemoryEntry>> byNormalizedContent = new HashMap<>();
        for (MemoryEntry entry : store.findAll()) {
            byNormalizedContent.computeIfAbsent(normalize(entry.getContent()), k -> new ArrayList<>()).add(entry);
        }

        long now = System.currentTimeMillis();
        for (List<MemoryEntry> group : byNormalizedContent.values()) {
            if (group.size() <= 1) continue;

            MemoryEntry keeper = group.get(0);
            Set<String> mergedTags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            int totalAccessCount = 0;
            int maxImportance = keeper.getImportance();
            for (MemoryEntry candidate : group) {
                mergedTags.addAll(candidate.getTags());
                totalAccessCount += candidate.getAccessCount();
                maxImportance = Math.max(maxImportance, candidate.getImportance());
                if (candidate.getImportance() > keeper.getImportance()) {
                    keeper = candidate;
                }
            }

            MemoryEntry merged = keeper.withContentAndTags(
                    keeper.getContent(), new ArrayList<>(mergedTags), maxImportance, totalAccessCount, now);
            store.update(merged);

            for (MemoryEntry candidate : group) {
                if (!candidate.getId().equals(keeper.getId())) {
                    store.deleteById(candidate.getId());
                    storedEmbeddings.remove(candidate.getId());
                }
            }
        }
    }

    /**
     * Reduces importance by 1 for memories not accessed in 30+ days. Uses updatedAt (bumped on
     * each decay) rather than lastAccessedAt as the decay clock, so a memory only decays once
     * per elapsed 30-day window instead of every time this method runs.
     */
    private void decayStaleImportance() {
        long now = System.currentTimeMillis();
        for (MemoryEntry entry : store.findAll()) {
            boolean unaccessedForAWhile = now - entry.getLastAccessedAt() >= DECAY_PERIOD_MILLIS;
            boolean dueForDecayCheck = now - entry.getUpdatedAt() >= DECAY_PERIOD_MILLIS;
            if (unaccessedForAWhile && dueForDecayCheck && entry.getImportance() > 1) {
                store.update(entry.withImportance(entry.getImportance() - 1, now));
            }
        }
    }

    private String normalize(String content) {
        return content == null ? "" : content.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private int clampImportance(int importance) {
        if (importance < 1) return 1;
        if (importance > 10) return 10;
        return importance;
    }

    /** Returns the current size of the memory database file on disk, in bytes. */
    public long getStorageUsageBytes() {
        return store.getFileSizeBytes();
    }

    /** Returns the percentage of the configured storage limit currently in use (0.0 – 100.0). */
    public double getStorageUsagePercent() {
        return (double) getStorageUsageBytes() / maxStorageBytes * 100.0;
    }

    /** Dynamically changes the storage cap. Pass 0 or negative to disable the limit. */
    public void setMaxStorageBytes(long bytes) {
        this.maxStorageBytes = bytes;
    }

    /** One retrieval ranking: full RRF order plus the subset that clears its relevance threshold. */
    private static class Ranking {
        static final Ranking EMPTY = new Ranking(List.of(), Set.of());

        final List<String> rankedIds;
        final Set<String> passingIds;

        Ranking(List<String> rankedIds, Set<String> passingIds) {
            this.rankedIds = rankedIds;
            this.passingIds = passingIds;
        }
    }

    private static class ScoredEntry {
        final MemoryEntry entry;
        final double score;

        ScoredEntry(MemoryEntry entry, double score) {
            this.entry = entry;
            this.score = score;
        }
    }

    @Override
    public void dispose() {
        embeddingExecutor.shutdownNow();
        store.close();
    }
}
