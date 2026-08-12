package com.taiwei.aiagent.llm;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.taiwei.aiagent.model.ChatMessage;

import java.util.List;

/**
 * Token counting utility.
 *
 * OpenAI models: API response usage fields take priority.
 * Non-OpenAI models (DeepSeek, Tongyi/Qwen, etc.): falls back to local estimation
 * via jtokkit CL100K_BASE encoding, which matches GPT-3.5/4 tokenisation closely enough
 * for quota tracking purposes.
 */
public final class TokenCounter {

    private static final Encoding ENCODING;

    static {
        Encoding enc = null;
        try {
            EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
            enc = registry.getEncoding(EncodingType.CL100K_BASE);
        } catch (Exception ignored) {}
        ENCODING = enc;
    }

    private TokenCounter() {}

    /** Count tokens in a single string, falling back to a ~4-chars-per-token estimate. */
    public static int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        if (ENCODING == null) return Math.max(1, text.length() / 4);
        try {
            return ENCODING.countTokens(text);
        } catch (Exception e) {
            return Math.max(1, text.length() / 4);
        }
    }

    /**
     * Build a Usage estimate from messages + completion text.
     * Each message adds ~4 overhead tokens for role/formatting (OpenAI convention).
     */
    public static LlmResponse.Usage estimate(List<ChatMessage> messages, String completion) {
        int promptTokens = 0;
        if (messages != null) {
            for (ChatMessage msg : messages) {
                promptTokens += 4; // per-message overhead
                if (msg.getContent() != null) {
                    promptTokens += countTokens(msg.getContent());
                }
            }
            promptTokens += 2; // reply primer
        }
        int completionTokens = countTokens(completion);
        LlmResponse.Usage usage = new LlmResponse.Usage();
        usage.setPromptTokens(promptTokens);
        usage.setCompletionTokens(completionTokens);
        usage.setTotalTokens(promptTokens + completionTokens);
        return usage;
    }
}
