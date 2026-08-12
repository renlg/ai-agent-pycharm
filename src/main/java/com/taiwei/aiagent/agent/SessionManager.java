package com.taiwei.aiagent.agent;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.model.Conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 多会话管理器
 * 管理多个 AgentContext 实例，每个实例对应一个独立的会话
 * 支持磁盘持久化：最多保存 5 个最新会话
 */
public class SessionManager {

    private static final Logger LOG = Logger.getInstance(SessionManager.class);

    private final Project project;
    // Accessed from both the EDT (UI-triggered session switches/deletes) and pooled
    // background threads (Agent loop persisting state via saveState()); must stay thread-safe.
    // Wrapped in Collections.synchronizedMap to preserve LinkedHashMap's insertion order,
    // which listSessions()/deleteSession() rely on to find the "most recent" session.
    private final Map<String, AgentContext> sessions = Collections.synchronizedMap(new LinkedHashMap<>());
    private final SessionStore sessionStore;
    // Read/written from both the EDT and pooled agent threads.
    private volatile String activeSessionId;

    public SessionManager(Project project) {
        this.project = project;
        String basePath = project.getBasePath() != null ? project.getBasePath() : System.getProperty("user.home");
        this.sessionStore = new SessionStore(basePath);
    }

    /**
     * 从磁盘加载历史会话（最多 5 个）
     * 应在初始化时调用一次
     */
    public void loadFromDisk() {
        List<SessionStore.SessionData> savedSessions = sessionStore.load();
        if (savedSessions.isEmpty()) {
            LOG.debug("No sessions to restore");
            return;
        }

        for (SessionStore.SessionData data : savedSessions) {
            List<ChatMessage> messages = convertToChatMessages(data.messages);
            Conversation conversation = new Conversation(
                    data.id, data.title, data.createdAt, null, messages);
            AgentContext context = new AgentContext(project, conversation);

            // 恢复模式
            if (data.mode != null && !data.mode.isEmpty()) {
                context.setMode(AgentMode.fromString(data.mode));
            }
            // 恢复模型索引
            if (data.modelIndex >= 0) {
                context.setModelIndex(data.modelIndex);
            }

            sessions.put(data.id, context);
        }

        // 默认激活最后一个（最新的）会话
        synchronized (sessions) {
            if (!sessions.isEmpty()) {
                String lastKey = null;
                for (String key : sessions.keySet()) {
                    lastKey = key;
                }
                activeSessionId = lastKey;
            }
        }

        LOG.debug("Restored " + sessions.size() + " sessions from disk");
    }

    /**
     * 将当前所有会话状态保存到磁盘
     */
    public void saveState() {
        // Snapshot both keys and values together (rather than iterating the live map) so a
        // concurrent put/remove from another thread can't trigger a ConcurrentModificationException.
        Map<String, AgentContext> snapshot;
        synchronized (sessions) {
            snapshot = new LinkedHashMap<>(sessions);
        }
        List<SessionStore.SessionData> sessionDataList = new ArrayList<>();
        for (Map.Entry<String, AgentContext> entry : snapshot.entrySet()) {
            String id = entry.getKey();
            AgentContext ctx = entry.getValue();
            Conversation conv = ctx.getConversation();

            List<SessionStore.MessageData> messageDataList = convertToMessageDataList(conv.getNonSystemMessages());

            sessionDataList.add(new SessionStore.SessionData(
                    id,
                    conv.getTitle(),
                    conv.getCreatedAt(),
                    messageDataList,
                    ctx.getMode().toJsValue(),
                    ctx.getModelIndex()
            ));
        }
        sessionStore.save(sessionDataList);
    }

    /**
     * 创建新会话并设为活跃会话
     *
     * @return 新会话的 sessionId
     */
    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        AgentContext context = new AgentContext(project);
        sessions.put(sessionId, context);
        activeSessionId = sessionId;
        saveState();
        return sessionId;
    }

    /**
     * 获取或创建会话
     * 如果 sessionId 对应的会话已存在，返回该会话；否则创建新会话
     *
     * @param sessionId 会话 ID，为 null 或空则创建新会话
     * @return 会话 ID
     */
    public String getOrCreateSession(String sessionId) {
        if (sessionId != null && !sessionId.isEmpty() && sessions.containsKey(sessionId)) {
            activeSessionId = sessionId;
            return sessionId;
        }
        return createSession();
    }

    /**
     * 切换到指定会话
     */
    public void switchSession(String sessionId) {
        if (sessions.containsKey(sessionId)) {
            activeSessionId = sessionId;
        }
    }

    /**
     * 获取当前活跃会话的上下文
     */
    public AgentContext getActiveContext() {
        if (activeSessionId == null) {
            createSession();
        }
        return sessions.get(activeSessionId);
    }

    /**
     * 获取指定会话的上下文
     */
    public AgentContext getContext(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 获取当前活跃会话 ID
     */
    public String getActiveSessionId() {
        if (activeSessionId == null) {
            createSession();
        }
        return activeSessionId;
    }

    /**
     * 列出所有会话的摘要信息
     */
    public List<SessionInfo> listSessions() {
        List<SessionInfo> list = new ArrayList<>();
        synchronized (sessions) {
            for (Map.Entry<String, AgentContext> entry : sessions.entrySet()) {
                String id = entry.getKey();
                AgentContext ctx = entry.getValue();
                String title = ctx.getConversation().getTitle();
                long createdAt = ctx.getConversation().getCreatedAt();
                int messageCount = ctx.getConversation().getMessageCount();
                boolean active = id.equals(activeSessionId);
                list.add(new SessionInfo(id, title, createdAt, messageCount, active));
            }
        }
        return list;
    }

    /**
     * 删除指定会话
     * 如果删除的是当前活跃会话，自动切换到最近的会话
     */
    public void deleteSession(String sessionId) {
        sessions.remove(sessionId);
        if (sessionId.equals(activeSessionId)) {
            boolean empty;
            String lastKey = null;
            synchronized (sessions) {
                empty = sessions.isEmpty();
                if (!empty) {
                    for (String key : sessions.keySet()) {
                        lastKey = key;
                    }
                }
            }
            if (empty) {
                createSession();
            } else {
                activeSessionId = lastKey;
            }
        }
        saveState();
    }

    /**
     * Flushes any pending session save and stops the store's scheduler thread.
     * Called from AgentService.dispose() on project close.
     */
    public void dispose() {
        sessionStore.close();
    }

    // ========== 消息格式转换 ==========

    private List<SessionStore.MessageData> convertToMessageDataList(List<ChatMessage> messages) {
        List<SessionStore.MessageData> result = new ArrayList<>();
        for (ChatMessage msg : messages) {
            String toolName = null;
            String toolArgs = null;
            String toolResult = null;
            List<SessionStore.ToolCallData> toolCallsData = null;

            if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null && msg.getToolCalls().length > 0) {
                toolName = msg.getToolCalls()[0].getFunction().getName();
                toolArgs = msg.getToolCalls()[0].getFunction().getArguments();
                toolCallsData = new ArrayList<>();
                for (ChatMessage.ToolCall tc : msg.getToolCalls()) {
                    SessionStore.FunctionCallData funcData = null;
                    if (tc.getFunction() != null) {
                        funcData = new SessionStore.FunctionCallData(
                                tc.getFunction().getName(),
                                tc.getFunction().getArguments()
                        );
                    }
                    toolCallsData.add(new SessionStore.ToolCallData(tc.getId(), tc.getType(), funcData));
                }
            } else if ("tool".equals(msg.getRole())) {
                toolResult = msg.getContent();
            }

            SessionStore.MessageData data = new SessionStore.MessageData(
                    msg.getRole(), msg.getContent(), toolName, toolArgs, toolResult,
                    msg.getToolCallId(), toolCallsData);

            // 序列化图片内容（视觉输入），供重启/切换会话后恢复
            if (msg.getImageContents() != null && !msg.getImageContents().isEmpty()) {
                List<SessionStore.ImageData> imageDataList = new ArrayList<>();
                for (ChatMessage.ImageContent img : msg.getImageContents()) {
                    imageDataList.add(new SessionStore.ImageData(img.getBase64Data(), img.getMimeType()));
                }
                data.images = imageDataList;
            }

            result.add(data);
        }
        return result;
    }

    private List<ChatMessage> convertToChatMessages(List<SessionStore.MessageData> messageDataList) {
        List<ChatMessage> result = new ArrayList<>();
        if (messageDataList == null) return result;

        for (SessionStore.MessageData md : messageDataList) {
            ChatMessage msg = new ChatMessage(md.role, md.content);
            msg.setToolCallId(md.toolCallId);
            // 恢复图片内容（视觉输入）
            if (md.images != null && !md.images.isEmpty()) {
                List<ChatMessage.ImageContent> imageContents = new ArrayList<>();
                for (SessionStore.ImageData img : md.images) {
                    imageContents.add(new ChatMessage.ImageContent(img.base64Data, img.mimeType));
                }
                msg.setImageContents(imageContents);
            }
            if (md.toolCalls != null) {
                ChatMessage.ToolCall[] toolCalls = new ChatMessage.ToolCall[md.toolCalls.size()];
                for (int i = 0; i < md.toolCalls.size(); i++) {
                    SessionStore.ToolCallData tcd = md.toolCalls.get(i);
                    ChatMessage.ToolCall tc = new ChatMessage.ToolCall();
                    tc.setId(tcd.id);
                    tc.setType(tcd.type);
                    if (tcd.function != null) {
                        ChatMessage.FunctionCall fc = new ChatMessage.FunctionCall();
                        fc.setName(tcd.function.name);
                        fc.setArguments(tcd.function.arguments);
                        tc.setFunction(fc);
                    }
                    toolCalls[i] = tc;
                }
                msg.setToolCalls(toolCalls);
            }
            result.add(msg);
        }
        return mergeConsecutiveAssistantMessages(result);
    }

    /**
     * 合并连续的 assistant 消息（多轮工具调用迭代产生）
     * 合并规则：
     * 1. content 使用第一条非空的 content（保留最早的不为空的那条）
     * 2. tool_calls 合并所有 tool_call 数组，顺序追加
     */
    private List<ChatMessage> mergeConsecutiveAssistantMessages(List<ChatMessage> messages) {
        List<ChatMessage> merged = new ArrayList<>();
        ChatMessage pending = null;

        for (ChatMessage msg : messages) {
            if ("assistant".equals(msg.getRole())) {
                if (pending == null) {
                    // 开始新的 assistant 消息块
                    pending = new ChatMessage("assistant", msg.getContent());
                    if (msg.getToolCalls() != null && msg.getToolCalls().length > 0) {
                        pending.setToolCalls(msg.getToolCalls());
                    }
                } else {
                    // 合并到已有的 pending assistant 消息
                    // content: 保留最早的非空 content
                    if ((pending.getContent() == null || pending.getContent().isEmpty())
                            && msg.getContent() != null && !msg.getContent().isEmpty()) {
                        pending.setContent(msg.getContent());
                    }
                    // tool_calls: 合并所有
                    if (msg.getToolCalls() != null && msg.getToolCalls().length > 0) {
                        ChatMessage.ToolCall[] existing = pending.getToolCalls();
                        if (existing == null) {
                            pending.setToolCalls(msg.getToolCalls());
                        } else {
                            ChatMessage.ToolCall[] combined = new ChatMessage.ToolCall[existing.length + msg.getToolCalls().length];
                            System.arraycopy(existing, 0, combined, 0, existing.length);
                            System.arraycopy(msg.getToolCalls(), 0, combined, existing.length, msg.getToolCalls().length);
                            pending.setToolCalls(combined);
                        }
                    }
                }
            } else {
                if (pending != null) {
                    merged.add(pending);
                    pending = null;
                }
                merged.add(msg);
            }
        }
        if (pending != null) {
            merged.add(pending);
        }
        return merged;
    }

    // ========== 会话摘要 ==========

    /**
     * 会话摘要信息
     */
    public static class SessionInfo {
        private final String id;
        private final String title;
        private final long createdAt;
        private final int messageCount;
        private final boolean active;

        public SessionInfo(String id, String title, long createdAt, int messageCount, boolean active) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.messageCount = messageCount;
            this.active = active;
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
        public long getCreatedAt() { return createdAt; }
        public int getMessageCount() { return messageCount; }
        public boolean isActive() { return active; }
    }
}
