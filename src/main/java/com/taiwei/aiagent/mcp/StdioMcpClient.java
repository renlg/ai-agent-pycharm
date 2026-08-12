package com.taiwei.aiagent.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.settings.AiAgentSettings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 客户端：本地子进程 + stdin/stdout 上的 JSON-RPC 2.0
 */
public class StdioMcpClient implements McpClient {

    private static final Logger LOG = Logger.getInstance(StdioMcpClient.class);

    private final AiAgentSettings.McpConfig config;
    private final Gson gson = new Gson();
    private final AtomicLong idGenerator = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();

    private Process process;
    private BufferedWriter stdin;
    private Thread readerThread;
    private Thread stderrThread;
    private volatile boolean closed = false;
    private volatile boolean connected = false;

    public StdioMcpClient(AiAgentSettings.McpConfig config) {
        this.config = config;
    }

    @Override
    public synchronized McpInitResult initialize() {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(config.command);
            if (config.args != null) {
                cmd.addAll(config.args);
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            if (config.env != null) {
                for (AiAgentSettings.HeaderEntry entry : config.env) {
                    if (entry.key != null && !entry.key.isEmpty()) {
                        pb.environment().put(entry.key, entry.value == null ? "" : entry.value);
                    }
                }
            }

            process = pb.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            readerThread = new Thread(this::readLoop, "mcp-stdio-reader-" + config.name);
            readerThread.setDaemon(true);
            readerThread.start();

            stderrThread = new Thread(this::stderrLoop, "mcp-stdio-stderr-" + config.name);
            stderrThread.setDaemon(true);
            stderrThread.start();

            JsonObject params = McpJsonRpc.buildInitializeParams("taiwei-ai-agent", "1.0");
            JsonObject response = sendRequest("initialize", params);
            if (response == null) {
                // No response (e.g. timed out): the process and reader/stderr threads spawned
                // above would otherwise be orphaned since the caller never gets a live client to close().
                close();
                return McpInitResult.failure("MCP server '" + config.name + "' did not respond to initialize");
            }
            String err = McpJsonRpc.extractError(response);
            if (err != null) {
                close();
                return McpInitResult.failure(err);
            }

            McpInitResult result = parseInitResult(response);
            sendNotification("notifications/initialized", new JsonObject());
            connected = true;
            return result;
        } catch (Exception e) {
            LOG.warn("Failed to initialize stdio MCP server '" + config.name + "': " + e.getMessage());
            // Same reasoning as above: clean up any process/threads that were already started
            // before the exception was thrown, so a failed initialize() never leaks a subprocess.
            close();
            return McpInitResult.failure("Failed to start MCP server: " + e.getMessage());
        }
    }

    private McpInitResult parseInitResult(JsonObject response) {
        JsonObject result = response.getAsJsonObject("result");
        String serverName = config.name;
        String serverVersion = "";
        String protocolVersion = McpJsonRpc.PROTOCOL_VERSION;
        if (result != null) {
            if (result.has("protocolVersion") && !result.get("protocolVersion").isJsonNull()) {
                protocolVersion = result.get("protocolVersion").getAsString();
            }
            if (result.has("serverInfo") && result.get("serverInfo").isJsonObject()) {
                JsonObject info = result.getAsJsonObject("serverInfo");
                if (info.has("name") && !info.get("name").isJsonNull()) serverName = info.get("name").getAsString();
                if (info.has("version") && !info.get("version").isJsonNull()) serverVersion = info.get("version").getAsString();
            }
        }
        return McpInitResult.success(serverName, serverVersion, protocolVersion);
    }

    @Override
    public List<McpToolInfo> listTools() {
        if (!connected) return Collections.emptyList();
        try {
            JsonObject response = sendRequest("tools/list", new JsonObject());
            if (response == null) return Collections.emptyList();
            String err = McpJsonRpc.extractError(response);
            if (err != null) {
                LOG.warn("tools/list failed for '" + config.name + "': " + err);
                return Collections.emptyList();
            }
            return McpToolListParser.parse(response.getAsJsonObject("result"));
        } catch (Exception e) {
            LOG.warn("Failed to list tools for '" + config.name + "': " + e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public String callTool(String toolName, String argumentsJson) {
        if (!connected) return ToolError.of("MCP_NOT_CONNECTED", "MCP server '" + config.name + "' is not connected", "Reconnect or enable the MCP server, then retry.");
        try {
            JsonObject params = McpToolListParser.buildCallParams(gson, toolName, argumentsJson);
            if (params == null) return ToolError.of("INVALID_ARGUMENT", "Invalid MCP tool arguments JSON", "Provide arguments matching the MCP tool schema.");

            JsonObject response = sendRequest("tools/call", params);
            if (response == null) {
                return ToolError.of("MCP_TIMEOUT", "MCP server '" + config.name + "' timed out calling tool '" + toolName + "'", "Retry or increase the MCP server timeout.");
            }
            String err = McpJsonRpc.extractError(response);
            if (err != null) {
                return ToolError.of("MCP_ERROR", err, "Correct the arguments or inspect the MCP server, then retry.");
            }
            return McpJsonRpc.extractToolResultText(response.getAsJsonObject("result"));
        } catch (Exception e) {
            return ToolError.unexpected(LOG, "Failed to call MCP tool '" + toolName + "' on '" + config.name + "'", e,
                    "MCP tool call failed: " + e.getMessage(), "Inspect the MCP server connection and retry.");
        }
    }

    @Override
    public boolean ping() {
        if (!connected) return false;
        try {
            JsonObject response = sendRequest("ping", new JsonObject());
            return response != null && McpJsonRpc.extractError(response) == null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isConnected() {
        return connected && process != null && process.isAlive();
    }

    @Override
    public String getServerName() {
        return config.name;
    }

    @Override
    public synchronized void close() {
        closed = true;
        connected = false;
        try {
            if (stdin != null) stdin.close();
        } catch (IOException ignored) {
        }
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        // Destroying the process closes its stdout/stderr pipes, which unblocks the reader
        // threads' readLine() calls on their own; interrupt them too as a defensive fallback.
        if (readerThread != null) {
            readerThread.interrupt();
        }
        if (stderrThread != null) {
            stderrThread.interrupt();
        }
        failAllPending("MCP client closed");
    }

    // ========== internal ==========

    private JsonObject sendRequest(String method, JsonObject params) throws IOException {
        long id = idGenerator.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        writeLine(McpJsonRpc.buildRequest(id, method, params).toString());
        try {
            int timeout = config.timeoutSeconds > 0 ? config.timeoutSeconds : 30;
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOG.warn("MCP request timed out: method=" + method + ", server=" + config.name);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            LOG.warn("MCP request failed: method=" + method + ", error=" + e.getMessage());
            return null;
        } finally {
            pending.remove(id);
        }
    }

    private void sendNotification(String method, JsonObject params) throws IOException {
        writeLine(McpJsonRpc.buildNotification(method, params).toString());
    }

    private synchronized void writeLine(String json) throws IOException {
        if (stdin == null) throw new IOException("MCP process stdin not available");
        stdin.write(json);
        stdin.write("\n");
        stdin.flush();
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JsonObject obj = gson.fromJson(line, JsonObject.class);
                    if (McpJsonRpc.isResponse(obj)) {
                        long id = obj.get("id").getAsLong();
                        CompletableFuture<JsonObject> future = pending.remove(id);
                        if (future != null) future.complete(obj);
                    }
                    // server->client requests/notifications (e.g. logging) are ignored in Phase 1
                } catch (JsonSyntaxException e) {
                    LOG.debug("Ignoring non-JSON line from MCP server '" + config.name + "': " + line);
                }
            }
        } catch (IOException e) {
            if (!closed) {
                LOG.warn("MCP stdio reader loop ended for '" + config.name + "': " + e.getMessage());
            }
        } finally {
            connected = false;
            failAllPending("MCP server connection closed");
        }
    }

    private void stderrLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                LOG.debug("[mcp:" + config.name + " stderr] " + line);
            }
        } catch (IOException ignored) {
        }
    }

    private void failAllPending(String message) {
        for (Long id : new ArrayList<>(pending.keySet())) {
            CompletableFuture<JsonObject> future = pending.remove(id);
            if (future != null) future.completeExceptionally(new IOException(message));
        }
    }
}
