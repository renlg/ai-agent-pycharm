package com.taiwei.aiagent.util;

import com.intellij.openapi.diagnostic.Logger;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.taiwei.aiagent.model.ChatMessage;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Token 计数器工具类
 * 根据当前使用的模型自动匹配对应的 tokenizer 进行精确计数
 * 支持 OpenAI 系列（o200k_base / cl100k_base / p50k_base）
 * 对于非 OpenAI 模型（如 Qwen、DeepSeek），回退到 cl100k_base 近似计数
 */
public class TokenCounter {

    private static final Logger LOG = Logger.getInstance(TokenCounter.class);

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();

    // 模型名 → Encoding 映射规则（按优先级顺序匹配）
    private static final ModelEncodingRule[] RULES = {
            // o200k_base: GPT-4o, o1 系列
            new ModelEncodingRule(Pattern.compile("gpt-4o|o1-|o3-|o4-"), EncodingType.O200K_BASE),
            // cl100k_base: GPT-4, GPT-3.5-turbo, text-embedding 系列
            new ModelEncodingRule(Pattern.compile("gpt-4|gpt-3\\.5-turbo|text-embedding-"), EncodingType.CL100K_BASE),
            // p50k_base: code-davinci 系列
            new ModelEncodingRule(Pattern.compile("code-davinci|code-cushman"), EncodingType.P50K_BASE),
    };

    /**
     * 缓存当前模型对应的 Encoding，避免重复查找
     */
    private static volatile String cachedModelName = null;
    private static volatile Encoding cachedEncoding = null;

    /**
     * 根据模型名称获取对应的 Encoding
     */
    public static Encoding getEncoding(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            return REGISTRY.getEncoding(EncodingType.CL100K_BASE);
        }

        // 检查缓存
        if (modelName.equals(cachedModelName) && cachedEncoding != null) {
            return cachedEncoding;
        }

        String lowerModel = modelName.toLowerCase();
        for (ModelEncodingRule rule : RULES) {
            if (rule.pattern.matcher(lowerModel).find()) {
                Encoding enc = REGISTRY.getEncoding(rule.encodingType);
                cachedModelName = modelName;
                cachedEncoding = enc;
                LOG.info("模型 " + modelName + " 匹配 tokenizer: " + rule.encodingType);
                return enc;
            }
        }

        // 非 OpenAI 模型（Qwen、DeepSeek、GLM 等）回退到 cl100k_base
        Encoding fallback = REGISTRY.getEncoding(EncodingType.CL100K_BASE);
        cachedModelName = modelName;
        cachedEncoding = fallback;
        LOG.info("模型 " + modelName + " 无精确匹配，回退到 cl100k_base");
        return fallback;
    }

    /**
     * 计算单条文本的 Token 数
     */
    public static int countTokens(String text, String modelName) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Encoding encoding = getEncoding(modelName);
        return encoding.countTokens(text);
    }

    /**
     * 计算消息列表的总 Token 数（含每条消息的 overhead）
     * 参考 OpenAI 的 token 计算方式：每条消息约 4 tokens overhead（role、分隔符等）
     */
    public static int countTokens(List<ChatMessage> messages, String modelName) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage msg : messages) {
            total += countMessageTokens(msg, modelName);
        }
        total += 3; // 每次请求的 priming tokens
        return total;
    }

    /**
     * 计算单条消息的 Token 数（含该消息自身的 overhead），不含请求级别的 priming tokens。
     * 供 Conversation 做增量/缓存计数时按消息复用。
     */
    public static int countMessageTokens(ChatMessage msg, String modelName) {
        Encoding encoding = getEncoding(modelName);
        int total = 4; // 每条消息的固定 overhead（role、分隔符等）
        String content = msg.getContent();
        if (content != null && !content.isEmpty()) {
            total += encoding.countTokens(content);
        }
        // 工具调用参数也占 token
        if (msg.getToolCalls() != null) {
            for (ChatMessage.ToolCall tc : msg.getToolCalls()) {
                if (tc.getFunction() != null) {
                    if (tc.getFunction().getName() != null) {
                        total += encoding.countTokens(tc.getFunction().getName());
                    }
                    if (tc.getFunction().getArguments() != null) {
                        total += encoding.countTokens(tc.getFunction().getArguments());
                    }
                }
            }
        }
        // 工具返回结果
        if (msg.getToolCallId() != null) {
            total += 2; // tool_call_id overhead
        }
        return total;
    }

    /**
     * 估算单张图片消耗的 Token 数
     * 采用 OpenAI 的图块（tile）计算公式：
     *   tiles = ceil(width/512) * ceil(height/512)
     *   tokens = 170 * tiles + 85
     * 当宽高未知（<= 0）时，回退到每张图片 200 tokens 的启发式估算。
     *
     * @param width    图片宽度（像素），未知时传 0
     * @param height   图片高度（像素），未知时传 0
     * @param mimeType 图片 MIME 类型（当前不影响估算，保留以便未来扩展）
     */
    public static int estimateImageTokens(int width, int height, String mimeType) {
        if (width <= 0 || height <= 0) {
            return 200; // 未知尺寸时的启发式估算
        }
        int tiles = (int) (Math.ceil(width / 512.0) * Math.ceil(height / 512.0));
        return 170 * tiles + 85;
    }

    /**
     * 清除缓存（模型切换时调用）
     */
    public static void clearCache() {
        cachedModelName = null;
        cachedEncoding = null;
    }

    /**
     * 模型名 → Encoding 映射规则
     */
    private static class ModelEncodingRule {
        final Pattern pattern;
        final EncodingType encodingType;

        ModelEncodingRule(Pattern pattern, EncodingType encodingType) {
            this.pattern = pattern;
            this.encodingType = encodingType;
        }
    }
}
