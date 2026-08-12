package com.taiwei.aiagent.memory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.settings.AiAgentSettings;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Embedding provider used for hybrid memory retrieval, backed by the OpenAI-compatible
 * {@code POST /embeddings} endpoint of the configured gateway (OpenAI, DeepSeek, Tongyi and most
 * gateways expose it). The embedding model is auto-detected on first use from a short candidate
 * list; the working model is remembered per configuration. Results are unit-normalized and cached
 * in memory (LRU) so repeated lookups don't burn API calls.
 *
 * <p>Application-level singleton; all public methods are safe for concurrent use. Every method
 * is best-effort: on any failure (no API config, network error, endpoint not supported) it logs
 * a warning and returns {@code null} for the affected text so callers can degrade to
 * keyword-only search. When no candidate model works, the endpoint is considered unsupported
 * and further attempts are skipped for a cooldown period.
 *
 * <p>Embedding calls block on the API; invoke them only from background threads (see
 * MemoryManager's embedding executor) or with an explicit timeout, never unboundedly from a
 * chat/tool-execution thread.
 */
public final class EmbeddingService {

    private static final Logger LOG = Logger.getInstance(EmbeddingService.class);
    private static final Gson GSON = new Gson();

    /**
     * Embedding models probed in order until one is accepted by the gateway. Covers OpenAI
     * (current + legacy) and Tongyi/DashScope compatible-mode names.
     */
    private static final String[] CANDIDATE_MODELS = {
            "text-embedding-3-small",
            "text-embedding-ada-002",
            "text-embedding-v4",
            "text-embedding-v3"
    };

    /** After every candidate model is rejected, skip /embeddings calls for this long. */
    private static final long UNSUPPORTED_COOLDOWN_MS = 5 * 60_000L;

    private static final int MAX_CACHE_ENTRIES = 2000;
    /** Long memories are truncated before embedding to keep the request cheap. */
    private static final int MAX_TEXT_CHARS = 2000;
    private static final int MAX_LOG_BODY_CHARS = 300;

    private static volatile EmbeddingService instance;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    /** text -> unit vector, LRU-evicted. */
    private final Map<String, float[]> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    /** baseUrl + apiKey of the config the fields below were resolved against. */
    private volatile String configKey;
    /** Embedding model accepted by the gateway, or null until successfully probed. */
    private volatile String workingModel;
    /** Epoch millis until which /embeddings calls are skipped after a failed probe. */
    private volatile long disabledUntil;

    private EmbeddingService() {
    }

    public static EmbeddingService getInstance() {
        if (instance == null) {
            synchronized (EmbeddingService.class) {
                if (instance == null) {
                    instance = new EmbeddingService();
                }
            }
        }
        return instance;
    }

    /** Returns a unit-length embedding of {@code text}, or {@code null} if one can't be generated. */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return null;
        String key = truncate(text.trim());
        float[] cached = cache.get(key);
        if (cached != null) return cached;
        List<float[]> vectors = requestEmbeddings(List.of(key));
        float[] vector = (vectors != null && !vectors.isEmpty()) ? vectors.get(0) : null;
        if (vector != null) {
            cache.put(key, vector);
        }
        return vector;
    }

    /**
     * Batch variant of {@link #embed(String)}: uncached texts are sent in a single API call.
     * The returned list is index-aligned with the input; failed items are {@code null}.
     */
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>();
        if (texts == null || texts.isEmpty()) return result;

        List<Integer> missIndices = new ArrayList<>();
        List<String> missTexts = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                result.add(null);
                continue;
            }
            String key = truncate(text.trim());
            float[] cached = cache.get(key);
            result.add(cached);
            if (cached == null) {
                missIndices.add(result.size() - 1);
                missTexts.add(key);
            }
        }
        if (missTexts.isEmpty()) return result;

        List<float[]> batch = requestEmbeddings(missTexts);
        if (batch == null) return result;
        for (int i = 0; i < missIndices.size(); i++) {
            float[] vector = i < batch.size() ? batch.get(i) : null;
            if (vector != null) {
                cache.put(missTexts.get(i), vector);
                result.set(missIndices.get(i), vector);
            }
        }
        return result;
    }

    /**
     * Cosine similarity of two vectors; 0 when either is null/empty or the dimensions differ.
     * The dimension check keeps stale vectors from an older embedding scheme (or a different
     * model) from producing meaningless similarities against fresh query vectors.
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ========== /embeddings plumbing ==========

    /**
     * Embeds {@code texts} via {@code POST /embeddings}, resolving the embedding model on first
     * use. Returns an input-aligned list, or {@code null} when the endpoint is unavailable.
     */
    private List<float[]> requestEmbeddings(List<String> texts) {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        String baseUrl = settings.getBaseUrl();
        String apiKey = settings.getApiKey();
        if (baseUrl == null || baseUrl.isBlank()) return null;

        String key = baseUrl + "\0" + (apiKey == null ? "" : apiKey);
        if (!key.equals(configKey)) {
            // Config changed: forget the resolved model and any unsupported-endpoint cooldown.
            configKey = key;
            workingModel = null;
            disabledUntil = 0;
        }
        if (System.currentTimeMillis() < disabledUntil) return null;

        String url = (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + "embeddings";
        String model = workingModel;
        if (model != null) {
            return callEmbeddings(url, apiKey, model, texts);
        }
        for (String candidate : CANDIDATE_MODELS) {
            List<float[]> vectors = callEmbeddings(url, apiKey, candidate, texts);
            if (vectors != null) {
                LOG.debug("Resolved embedding model '" + candidate + "' at " + url);
                workingModel = candidate;
                return vectors;
            }
        }
        LOG.warn("/embeddings not supported at " + url + " (no candidate model accepted); "
                + "memory retrieval degrades to keyword-only for the next "
                + UNSUPPORTED_COOLDOWN_MS / 60_000 + " minutes");
        disabledUntil = System.currentTimeMillis() + UNSUPPORTED_COOLDOWN_MS;
        return null;
    }

    /** One {@code POST /embeddings} call; returns an input-aligned list, or {@code null} on failure. */
    private List<float[]> callEmbeddings(String url, String apiKey, String model, List<String> texts) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            JsonArray input = new JsonArray();
            for (String text : texts) {
                input.add(text);
            }
            body.add("input", input);

            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), MediaType.parse("application/json")));
            if (apiKey != null && !apiKey.isBlank()) {
                builder.addHeader("Authorization", "Bearer " + apiKey);
            }

            try (Response response = httpClient.newCall(builder.build()).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    LOG.warn("/embeddings HTTP " + response.code() + " (model=" + model + "): "
                            + truncateForLog(responseBody));
                    return null;
                }
                return parseEmbeddings(responseBody, texts.size(), model);
            }
        } catch (Exception e) {
            LOG.warn("/embeddings request failed (model=" + model + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * Extracts {@code data[*].embedding} from an OpenAI-style response, ordered by the
     * {@code index} field. Returns {@code null} when the payload isn't a usable embedding
     * response; individual malformed items are {@code null} in the returned list.
     */
    private static List<float[]> parseEmbeddings(String responseBody, int expectedCount, String model) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            if (json.has("error") && !json.get("error").isJsonNull()) {
                JsonObject error = json.getAsJsonObject("error");
                String message = error.has("message") ? error.get("message").getAsString() : "unknown error";
                LOG.warn("/embeddings error (model=" + model + "): " + message);
                return null;
            }
            if (!json.has("data") || !json.get("data").isJsonArray()) return null;
            JsonArray data = json.getAsJsonArray("data");

            float[][] vectors = new float[expectedCount][];
            int position = 0;
            for (JsonElement element : data) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                int index = item.has("index") && !item.get("index").isJsonNull()
                        ? item.get("index").getAsInt() : position;
                position++;
                if (index < 0 || index >= expectedCount || !item.has("embedding")) continue;
                vectors[index] = normalize(GSON.fromJson(item.get("embedding"), float[].class));
            }
            List<float[]> result = new ArrayList<>(expectedCount);
            Collections.addAll(result, vectors);
            return result;
        } catch (Exception e) {
            LOG.warn("Failed to parse /embeddings response (model=" + model + "): " + e.getMessage());
            return null;
        }
    }

    /** Scales the vector to unit length. Null if empty, non-finite or zero-norm. */
    private static float[] normalize(float[] raw) {
        if (raw == null || raw.length == 0) return null;
        double norm = 0;
        for (float v : raw) {
            if (!Float.isFinite(v)) return null;
            norm += v * v;
        }
        if (norm == 0) return null;
        double scale = 1.0 / Math.sqrt(norm);
        float[] vector = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            vector[i] = (float) (raw[i] * scale);
        }
        return vector;
    }

    private static String truncate(String text) {
        return text.length() <= MAX_TEXT_CHARS ? text : text.substring(0, MAX_TEXT_CHARS);
    }

    private static String truncateForLog(String text) {
        if (text == null) return "";
        return text.length() <= MAX_LOG_BODY_CHARS ? text : text.substring(0, MAX_LOG_BODY_CHARS) + "...";
    }
}
