package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.model.Conversation;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.settings.ImageGenSettings;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.tool.ToolError;
import com.taiwei.aiagent.util.I18nUtil;
import okhttp3.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ImageGenerationTool implements Tool {

    private static final Logger LOG = Logger.getInstance(ImageGenerationTool.class);
    private static final int TIMEOUT_SECONDS = 120;

    /**
     * 工具描述/参数说明文案的模板资源路径，用户可直接编辑该文件调整文案而无需改代码。
     * 文件缺失或缺少某个 key 时回退到下方内置默认文案，并记录一条警告日志。
     */
    private static final String PROMPTS_RESOURCE = "prompts/image_generation.properties";

    private static final String DEFAULT_TOOL_DESCRIPTION = I18nUtil.getMessage("tool.description.generateImage");
    private static final String DEFAULT_PROMPT_PARAM_DESCRIPTION = I18nUtil.getMessage("tool.image.promptDescription");
    private static final String DEFAULT_SIZE_PARAM_DESCRIPTION = I18nUtil.getMessage("tool.image.sizeDescription");
    private static final String DEFAULT_N_PARAM_DESCRIPTION = I18nUtil.getMessage("tool.image.countDescription");

    private static volatile Properties cachedPrompts;

    private static Properties loadPrompts() {
        Properties local = cachedPrompts;
        if (local != null) {
            return local;
        }
        Properties props = new Properties();
        try (InputStream is = ImageGenerationTool.class.getClassLoader().getResourceAsStream(PROMPTS_RESOURCE)) {
            if (is == null) {
                LOG.warn("Image prompt template not found; using defaults: " + PROMPTS_RESOURCE);
            } else {
                try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load image prompt template; using defaults: " + e.getMessage());
        }
        cachedPrompts = props;
        return props;
    }

    private static String getPrompt(String key, String defaultValue) {
        String value = loadPrompts().getProperty(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    /**
     * 提供当前会话引用，用于自动读取最近一条带图用户消息作为图生图参考图。
     * 由 AgentContext 在构造时注入；ToolManagerDialog 等仅展示工具信息的场景不会注入，此时保持 text-to-image 行为。
     */
    private volatile Supplier<Conversation> conversationSupplier;

    public void setConversationSupplier(Supplier<Conversation> conversationSupplier) {
        this.conversationSupplier = conversationSupplier;
    }

    private static OkHttpClient buildHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (AiAgentSettings.getInstance().isBypassHostnameVerificationEnabled()) {
            LOG.warn("已启用跳过主机名校验，图像生成 API 请求将不校验 TLS 证书主机名");
            builder.hostnameVerifier((hostname, session) -> true);
        }
        return builder.build();
    }

    @Override
    public String getName() {
        return "generate_image";
    }

    @Override
    public String getDescription() {
        return getPrompt("image_gen.tool_description", DEFAULT_TOOL_DESCRIPTION);
    }

    @Override
    public String getParametersSchema() {
        String promptDesc = getPrompt("image_gen.param.prompt.description", DEFAULT_PROMPT_PARAM_DESCRIPTION);
        String sizeDesc = getPrompt("image_gen.param.size.description", DEFAULT_SIZE_PARAM_DESCRIPTION);
        String nDesc = getPrompt("image_gen.param.n.description", DEFAULT_N_PARAM_DESCRIPTION);

        return """
                {
                  "type": "object",
                  "properties": {
                    "prompt": {
                      "type": "string",
                      "description": "%s"
                    },
                    "size": {
                      "type": "string",
                      "description": "%s",
                      "enum": ["256x256", "512x512", "1024x1024", "1792x1024", "1024x1792"]
                    },
                    "n": {
                      "type": "integer",
                      "description": "%s",
                      "minimum": 1,
                      "maximum": 4
                    }
                  },
                  "required": ["prompt"]
                }
                """.formatted(escapeJson(promptDesc), escapeJson(sizeDesc), escapeJson(nDesc));
    }

    /**
     * 转义 JSON 字符串中的反斜杠和双引号，防止用户在模板文件中自定义文案时破坏 JSON 结构。
     */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public String execute(String arguments) {
        ImageGenSettings settings = ImageGenSettings.getInstance();
        if (!settings.isConfigured()) {
            return ToolError.of("NOT_CONFIGURED", I18nUtil.getMessage("tool.image.notConfigured"), I18nUtil.getMessage("tool.image.configureHint"));
        }

        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String prompt = args.get("prompt").getAsString().trim();
            if (prompt.isEmpty()) {
                return ToolError.of("INVALID_ARGUMENT", I18nUtil.getMessage("tool.image.promptEmpty"), I18nUtil.getMessage("tool.hint.provideValidArguments"));
            }

            String size = args.has("size") ? args.get("size").getAsString() : settings.getImageSize();
            int n = args.has("n") ? args.get("n").getAsInt() : settings.getImageCount();
            n = Math.max(1, Math.min(4, n));

            String baseUrl = settings.getBaseUrl();
            if (!baseUrl.endsWith("/")) baseUrl += "/";

            List<ChatMessage.ImageContent> referenceImages = resolveReferenceImages();
            if (referenceImages != null && !referenceImages.isEmpty()) {
                return generateImageToImage(settings, baseUrl, prompt, size, n, referenceImages);
            }
            return generateTextToImage(settings, baseUrl, prompt, size, n);

        } catch (Exception e) {
            return ToolError.unexpected(LOG, "Image generation failed unexpectedly", e,
                    I18nUtil.getMessage("tool.image.failed", e.getMessage()), I18nUtil.getMessage("tool.hint.retry"));
        }
    }

    /**
     * 从会话中最近一条带图片的用户消息读取参考图（图生图使用），无会话上下文或无图片时返回 null
     */
    private List<ChatMessage.ImageContent> resolveReferenceImages() {
        Supplier<Conversation> supplier = this.conversationSupplier;
        if (supplier == null) {
            return null;
        }
        Conversation conversation = supplier.get();
        if (conversation == null) {
            return null;
        }
        ChatMessage msg = conversation.getLastUserMessageWithImages();
        return msg != null ? msg.getImageContents() : null;
    }

    private String generateTextToImage(ImageGenSettings settings, String baseUrl, String prompt, String size, int n) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.getModelName());
        body.addProperty("prompt", prompt);
        body.addProperty("n", n);
        body.addProperty("size", size);

        String endpoint = baseUrl + "images/generations";
        LOG.debug("Calling image generation API: " + endpoint);

        try {
            RequestBody requestBody = RequestBody.create(
                    body.toString(), MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(endpoint)
                    .post(requestBody)
                    .header("Authorization", "Bearer " + settings.getApiKey())
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = buildHttpClient().newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    LOG.warn("Image generation API returned HTTP " + response.code());
                    return ToolError.of("HTTP_ERROR", I18nUtil.getMessage("tool.search.httpFailedWithBody", response.code(), truncate(responseBody, 300)), I18nUtil.getMessage("tool.image.apiHint"));
                }
                return parseImageResponse(responseBody, prompt, size);
            }
        } catch (IOException e) {
            LOG.warn("Image generation request failed: " + e.getMessage());
            return ToolError.of("NETWORK_ERROR", I18nUtil.getMessage("tool.image.networkFailed", e.getMessage()), I18nUtil.getMessage("tool.search.networkHint"));
        }
    }

    /**
     * 图生图：优先调用 OpenAI 兼容的 images/edits（multipart/form-data，标准 i2i 形状）。
     * 若上游不支持该端点（4xx/5xx 或网络异常），回退为 images/generations + image(s) 字段（部分中转支持）。
     */
    private String generateImageToImage(ImageGenSettings settings, String baseUrl, String prompt, String size, int n,
                                          List<ChatMessage.ImageContent> referenceImages) {
        try {
            String result = tryImagesEdits(settings, baseUrl, prompt, size, n, referenceImages);
            if (result != null) {
                return result;
            }
        } catch (Exception e) {
            LOG.warn("images/edits failed; falling back to images/generations: " + e.getMessage());
        }

        try {
            return tryGenerationsWithImage(settings, baseUrl, prompt, size, n, referenceImages);
        } catch (Exception e) {
            LOG.warn("Image-to-image fallback failed: " + e.getMessage());
            return ToolError.of("REFERENCE_IMAGE_UNSUPPORTED", I18nUtil.getMessage("tool.image.referenceUnsupported", e.getMessage()), I18nUtil.getMessage("tool.image.referenceHint"));
        }
    }

    /**
     * 返回 null 表示端点不可用/失败，调用方应尝试回退；返回非 null 即为最终结果（成功或明确的失败信息）
     */
    private String tryImagesEdits(ImageGenSettings settings, String baseUrl, String prompt, String size, int n,
                                    List<ChatMessage.ImageContent> referenceImages) throws IOException {
        String endpoint = baseUrl + "images/edits";
        LOG.debug("Calling image edit API: endpoint=" + endpoint + ", model=" + settings.getModelName()
                + ", refImages=" + referenceImages.size());

        MultipartBody.Builder multipart = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", settings.getModelName())
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("n", String.valueOf(n))
                .addFormDataPart("size", size);

        int idx = 0;
        for (ChatMessage.ImageContent img : referenceImages) {
            byte[] bytes = Base64.getDecoder().decode(img.getBase64Data());
            String mimeType = img.getMimeType() != null && !img.getMimeType().isBlank() ? img.getMimeType() : "image/png";
            String ext = mimeType.contains("/") ? mimeType.substring(mimeType.indexOf('/') + 1) : "png";
            String filename = "reference_" + (idx++) + "." + ext;
            multipart.addFormDataPart("image", filename, RequestBody.create(bytes, MediaType.parse(mimeType)));
        }

        Request request = new Request.Builder()
                .url(endpoint)
                .post(multipart.build())
                .header("Authorization", "Bearer " + settings.getApiKey())
                .build();

        try (Response response = buildHttpClient().newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                LOG.warn("images/edits returned HTTP " + response.code());
                return null;
            }
            return parseImageResponse(responseBody, prompt, size);
        }
    }

    private String tryGenerationsWithImage(ImageGenSettings settings, String baseUrl, String prompt, String size, int n,
                                             List<ChatMessage.ImageContent> referenceImages) throws IOException {
        String endpoint = baseUrl + "images/generations";
        LOG.debug("Calling image generation fallback API: " + endpoint);

        JsonObject body = new JsonObject();
        body.addProperty("model", settings.getModelName());
        body.addProperty("prompt", prompt);
        body.addProperty("n", n);
        body.addProperty("size", size);

        if (referenceImages.size() == 1) {
            body.addProperty("image", referenceImages.get(0).getBase64Data());
        } else {
            JsonArray images = new JsonArray();
            for (ChatMessage.ImageContent img : referenceImages) {
                images.add(img.getBase64Data());
            }
            body.add("images", images);
        }

        RequestBody requestBody = RequestBody.create(
                body.toString(), MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(endpoint)
                .post(requestBody)
                .header("Authorization", "Bearer " + settings.getApiKey())
                .header("Content-Type", "application/json")
                .build();

        try (Response response = buildHttpClient().newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                LOG.warn("images/generations image fallback returned HTTP " + response.code());
                throw new IOException("HTTP " + response.code() + ": " + truncate(responseBody, 300));
            }
            return parseImageResponse(responseBody, prompt, size);
        }
    }

    private String parseImageResponse(String responseBody, String prompt, String size) {
        try {
            if (responseBody == null || responseBody.isBlank()) {
                return ToolError.of("EMPTY_RESPONSE", I18nUtil.getMessage("tool.image.emptyResponse"), I18nUtil.getMessage("tool.image.apiHint"));
            }
            com.google.gson.JsonElement respElement = JsonParser.parseString(responseBody);
            if (respElement == null || respElement.isJsonNull() || !respElement.isJsonObject()) {
                return ToolError.of("INVALID_RESPONSE", I18nUtil.getMessage("tool.image.invalidResponse", truncate(responseBody, 300)), I18nUtil.getMessage("tool.image.apiHint"));
            }
            JsonObject resp = respElement.getAsJsonObject();
            JsonArray data = resp.getAsJsonArray("data");
            if (data == null || data.size() == 0) {
                return ToolError.of("EMPTY_RESPONSE", I18nUtil.getMessage("tool.image.emptyData", truncate(responseBody, 300)), I18nUtil.getMessage("tool.image.apiHint"));
            }

            JsonArray images = new JsonArray();
            for (int i = 0; i < data.size(); i++) {
                if (!data.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject item = data.get(i).getAsJsonObject();

                String url = getNonBlankString(item, "url");
                String b64Json = url == null ? getNonBlankString(item, "b64_json") : null;
                if (url == null && b64Json == null) {
                    continue;
                }

                JsonObject imgObj = new JsonObject();
                imgObj.addProperty("mimeType", "image/png");
                if (url != null) {
                    imgObj.addProperty("url", url);
                } else {
                    imgObj.addProperty("base64", b64Json);
                }

                String revisedPrompt = getNonBlankString(item, "revised_prompt");
                if (revisedPrompt != null) {
                    imgObj.addProperty("revisedPrompt", revisedPrompt);
                }
                images.add(imgObj);
            }

            if (images.size() == 0) {
                return ToolError.of("INVALID_RESPONSE", I18nUtil.getMessage("tool.image.missingImage", truncate(responseBody, 300)), I18nUtil.getMessage("tool.image.apiHint"));
            }

            JsonObject result = new JsonObject();
            result.addProperty("__type", "generated_image");
            result.addProperty("prompt", prompt);
            result.addProperty("size", size);
            result.add("images", images);
            return result.toString();

        } catch (Exception e) {
            LOG.warn("Failed to parse image response: " + e.getMessage());
            return ToolError.of("INVALID_RESPONSE", I18nUtil.getMessage("tool.image.parseFailed", e.getMessage()), I18nUtil.getMessage("tool.image.apiHint"));
        }
    }

    /**
     * 安全提取字段为非空字符串：字段缺失、值为 JsonNull、非字符串类型或空白字符串均返回 null，不抛异常。
     */
    private static String getNonBlankString(JsonObject obj, String key) {
        if (!obj.has(key)) {
            return null;
        }
        com.google.gson.JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            return null;
        }
        String s = el.getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : (s != null ? s : "");
    }
}
