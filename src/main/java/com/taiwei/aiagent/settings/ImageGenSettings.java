package com.taiwei.aiagent.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 图像生成模型配置
 * 存储 OpenAI 兼容的图像生成 API 连接参数
 */
@State(
        name = "ImageGenSettings",
        storages = @Storage("image-gen-settings.xml")
)
public class ImageGenSettings implements PersistentStateComponent<ImageGenSettings.State> {

    private State state = new State();

    public static ImageGenSettings getInstance() {
        return ApplicationManager.getApplication().getService(ImageGenSettings.class);
    }

    @Override
    public @Nullable State getState() {
        State persisted = new State();
        persisted.baseUrl = state.baseUrl;
        persisted.modelName = state.modelName;
        persisted.imageSize = state.imageSize;
        persisted.imageCount = state.imageCount;
        state.encryptedApiKey = SecretEncryption.encryptedForStorage(
                state.apiKey, state.encryptedApiKey);
        persisted.encryptedApiKey = state.encryptedApiKey;
        persisted.apiKey = null;
        return persisted;
    }

    @Override
    public void loadState(@NotNull State state) {
        if (state.encryptedApiKey == null || state.encryptedApiKey.isEmpty()) {
            state.encryptedApiKey = SecretEncryption.encrypt(state.apiKey);
        }
        state.apiKey = state.encryptedApiKey != null && !state.encryptedApiKey.isEmpty()
                ? SecretEncryption.decrypt(state.encryptedApiKey)
                : (state.apiKey != null ? state.apiKey : "");
        this.state = state;
    }

    public String getBaseUrl() {
        return state.baseUrl;
    }

    public void setBaseUrl(String v) {
        state.baseUrl = v != null ? v : "";
    }

    public String getApiKey() {
        return state.apiKey;
    }

    public void setApiKey(String v) {
        state.apiKey = v != null ? v : "";
    }

    public String getModelName() {
        return state.modelName;
    }

    public void setModelName(String v) {
        state.modelName = v != null ? v : "";
    }

    public String getImageSize() {
        return state.imageSize;
    }

    public void setImageSize(String v) {
        state.imageSize = v != null ? v : "1024x1024";
    }

    public int getImageCount() {
        return state.imageCount;
    }

    public void setImageCount(int v) {
        state.imageCount = Math.max(1, Math.min(4, v));
    }

    public boolean isConfigured() {
        return state.baseUrl != null && !state.baseUrl.isEmpty()
                && state.apiKey != null && !state.apiKey.isEmpty()
                && state.modelName != null && !state.modelName.isEmpty();
    }

    public static class State {
        public String baseUrl = "";
        public String apiKey = "";
        public String encryptedApiKey = "";
        public String modelName = "dall-e-3";
        public String imageSize = "1024x1024";
        public int imageCount = 1;
    }
}
