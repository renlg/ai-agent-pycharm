package com.taiwei.aiagent.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 阿里云 IQS 网络搜索配置
 * 存储 AccessKey ID 和 AccessKey Secret
 */
@State(
        name = "IqsSettings",
        storages = @Storage("iqs-settings.xml")
)
public class IqsSettings implements PersistentStateComponent<IqsSettings.State> {

    private State state = new State();

    public static IqsSettings getInstance() {
        return ApplicationManager.getApplication().getService(IqsSettings.class);
    }

    @Override
    public @Nullable State getState() {
        State persisted = new State();
        persisted.endpoint = state.endpoint;
        state.encryptedAccessKeyId = SecretEncryption.encryptedForStorage(
                state.accessKeyId, state.encryptedAccessKeyId);
        state.encryptedAccessKeySecret = SecretEncryption.encryptedForStorage(
                state.accessKeySecret, state.encryptedAccessKeySecret);
        state.encryptedSerpApiKey = SecretEncryption.encryptedForStorage(
                state.serpApiKey, state.encryptedSerpApiKey);
        persisted.encryptedAccessKeyId = state.encryptedAccessKeyId;
        persisted.encryptedAccessKeySecret = state.encryptedAccessKeySecret;
        persisted.encryptedSerpApiKey = state.encryptedSerpApiKey;
        persisted.accessKeyId = null;
        persisted.accessKeySecret = null;
        persisted.serpApiKey = null;
        return persisted;
    }

    @Override
    public void loadState(@NotNull State state) {
        if (state.encryptedAccessKeyId == null || state.encryptedAccessKeyId.isEmpty()) {
            state.encryptedAccessKeyId = SecretEncryption.encrypt(state.accessKeyId);
        }
        if (state.encryptedAccessKeySecret == null || state.encryptedAccessKeySecret.isEmpty()) {
            state.encryptedAccessKeySecret = SecretEncryption.encrypt(state.accessKeySecret);
        }
        if (state.encryptedSerpApiKey == null || state.encryptedSerpApiKey.isEmpty()) {
            state.encryptedSerpApiKey = SecretEncryption.encrypt(state.serpApiKey);
        }
        state.accessKeyId = loadSecret(state.encryptedAccessKeyId, state.accessKeyId);
        state.accessKeySecret = loadSecret(state.encryptedAccessKeySecret, state.accessKeySecret);
        state.serpApiKey = loadSecret(state.encryptedSerpApiKey, state.serpApiKey);
        this.state = state;
    }

    private static String loadSecret(String encrypted, String legacyPlaintext) {
        return encrypted != null && !encrypted.isEmpty()
                ? SecretEncryption.decrypt(encrypted)
                : (legacyPlaintext != null ? legacyPlaintext : "");
    }

    public String getAccessKeyId() {
        return state.accessKeyId;
    }

    public void setAccessKeyId(String accessKeyId) {
        state.accessKeyId = accessKeyId != null ? accessKeyId : "";
    }

    public String getAccessKeySecret() {
        return state.accessKeySecret;
    }

    public void setAccessKeySecret(String accessKeySecret) {
        state.accessKeySecret = accessKeySecret != null ? accessKeySecret : "";
    }

    public String getEndpoint() {
        return state.endpoint;
    }

    public void setEndpoint(String endpoint) {
        state.endpoint = endpoint;
    }

    public String getSerpApiKey() {
        return state.serpApiKey;
    }

    public void setSerpApiKey(String serpApiKey) {
        state.serpApiKey = serpApiKey != null ? serpApiKey : "";
    }

    /**
     * 检查是否已配置 AK/SK
     */
    public boolean isConfigured() {
        return state.accessKeyId != null && !state.accessKeyId.isEmpty()
                && state.accessKeySecret != null && !state.accessKeySecret.isEmpty();
    }

    /**
     * 配置状态
     */
    public static class State {
        public String accessKeyId = "";
        public String accessKeySecret = "";
        public String endpoint = "iqs.cn-zhangjiakou.aliyuncs.com";
        public String serpApiKey = "";
        public String encryptedAccessKeyId = "";
        public String encryptedAccessKeySecret = "";
        public String encryptedSerpApiKey = "";
    }
}
