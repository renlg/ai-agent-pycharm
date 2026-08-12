package com.taiwei.aiagent.mcp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.settings.AiAgentSettings;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Project-level MCP 连接管理器：按配置建立/维护 {@link McpClient} 连接，
 * 并将各服务器暴露的工具适配为 {@link McpToolAdapter} 供 Agent 使用。
 * 所有连接启动操作异步执行，不阻塞调用线程。
 */
public class McpManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(McpManager.class);

    private final Project project;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final ExecutorService initExecutor;
    private volatile CompletableFuture<Void> initFuture;

    public McpManager(@NotNull Project project) {
        this.project = project;
        this.initExecutor = Executors.newFixedThreadPool(
                Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
                r -> {
                    Thread t = new Thread(r, "taiwei-mcp-init");
                    t.setDaemon(true);
                    return t;
                });
    }

    public static McpManager getInstance(@NotNull Project project) {
        return project.getService(McpManager.class);
    }

    /**
     * 异步启动所有已启用且尚未连接的 MCP 服务器。立即返回，不阻塞调用线程。
     */
    public void startAll() {
        lifecycleLock.lock();
        try {
            if (initFuture != null && !initFuture.isDone()) {
                return;
            }
            initFuture = CompletableFuture.runAsync(() -> {
                for (AiAgentSettings.McpConfig config : AiAgentSettings.getInstance().getMcpConfigs()) {
                    if (config.enabled && !connections.containsKey(config.name)) {
                        doStart(config);
                    }
                }
            }, initExecutor);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 关闭所有当前连接。
     */
    public void stopAll() {
        lifecycleLock.lock();
        try {
            for (String name : new ArrayList<>(connections.keySet())) {
                doStop(name);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 异步启动（或重启）单个 MCP 服务器连接。立即返回，不阻塞调用线程。
     */
    public void startConnection(AiAgentSettings.McpConfig config) {
        CompletableFuture.runAsync(() -> {
            lifecycleLock.lock();
            try {
                doStop(config.name);
                doStart(config);
            } finally {
                lifecycleLock.unlock();
            }
        }, initExecutor);
    }

    /**
     * 同步启动（或重启）单个 MCP 服务器连接并等待结果。用于测试连接等需要立即获取结果的场景。
     */
    public McpInitResult startConnectionSync(AiAgentSettings.McpConfig config) {
        lifecycleLock.lock();
        try {
            doStop(config.name);
            return doStart(config);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 关闭指定名称的连接。
     */
    public void stopConnection(String name) {
        lifecycleLock.lock();
        try {
            doStop(name);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 测试连接是否可用：完成 initialize 握手后立即关闭，不写入 {@link #connections}，不影响现有连接。
     */
    public McpInitResult testConnection(AiAgentSettings.McpConfig config) {
        McpClient client = createClient(config);
        try {
            return client.initialize();
        } catch (Exception e) {
            LOG.warn("MCP testConnection failed for '" + config.name + "': " + e.getMessage());
            return McpInitResult.failure("Failed to connect: " + e.getMessage());
        } finally {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 获取所有已连接服务器暴露的工具（已按 disabledTools 过滤）。
     * 首次调用时异步启动所有未连接的服务器，立即返回当前已连接的工具列表。
     */
    public List<McpToolAdapter> getActiveTools() {
        if (connections.isEmpty()) {
            startAll();
        }
        List<McpToolAdapter> result = new ArrayList<>();
        for (Connection conn : connections.values()) {
            result.addAll(conn.tools);
        }
        return result;
    }

    /**
     * 等待所有初始化完成。主要用于测试。
     */
    public CompletableFuture<Void> getInitFuture() {
        return initFuture;
    }

    private McpInitResult doStart(AiAgentSettings.McpConfig config) {
        McpClient client = createClient(config);
        McpInitResult result;
        try {
            result = client.initialize();
        } catch (Exception e) {
            LOG.warn("Failed to initialize MCP server '" + config.name + "': " + e.getMessage());
            result = McpInitResult.failure("Failed to connect: " + e.getMessage());
        }
        if (!result.isSuccess()) {
            LOG.warn("MCP server '" + config.name + "' failed to initialize: " + result.getErrorMessage());
            try {
                client.close();
            } catch (Exception ignored) {
            }
            return result;
        }

        List<McpToolInfo> toolInfos;
        try {
            toolInfos = client.listTools();
        } catch (Exception e) {
            LOG.warn("Failed to list tools for MCP server '" + config.name + "': " + e.getMessage());
            toolInfos = Collections.emptyList();
        }

        Set<String> disabled = config.disabledTools == null ? Collections.emptySet() : new HashSet<>(config.disabledTools);
        List<McpToolAdapter> adapters = new ArrayList<>();
        for (McpToolInfo info : toolInfos) {
            if (disabled.contains(info.getName())) continue;
            adapters.add(new McpToolAdapter(info, client, config.name));
        }

        connections.put(config.name, new Connection(client, adapters));
        LOG.debug("MCP server '" + config.name + "' connected with " + adapters.size() + " tool(s)");
        return result;
    }

    private void doStop(String name) {
        Connection conn = connections.remove(name);
        if (conn != null) {
            try {
                conn.client.close();
            } catch (Exception e) {
                LOG.warn("Error closing MCP connection '" + name + "': " + e.getMessage());
            }
        }
    }

    private McpClient createClient(AiAgentSettings.McpConfig config) {
        switch (config.transportType) {
            case STDIO:
                return new StdioMcpClient(config);
            case SSE:
                return new HttpSseMcpClient(config);
            case STREAMABLE_HTTP:
                return new StreamableHttpMcpClient(config);
            default:
                throw new IllegalArgumentException("Unsupported MCP transport type: " + config.transportType);
        }
    }

    @Override
    public void dispose() {
        stopAll();
        initExecutor.shutdownNow();
    }

    private static final class Connection {
        final McpClient client;
        final List<McpToolAdapter> tools;

        Connection(McpClient client, List<McpToolAdapter> tools) {
            this.client = client;
            this.tools = tools;
        }
    }
}
