package com.taiwei.aiagent.util;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * 国际化消息工具类
 */
public class I18nUtil {

    private static final String BUNDLE_NAME = "messages";
    
    /**
     * 获取当前语言的资源包
     */
    public static ResourceBundle getResourceBundle() {
        Locale locale = Locale.getDefault();
        // 只支持中文和英文
        if (locale.getLanguage().equals("zh")) {
            return ResourceBundle.getBundle(BUNDLE_NAME, Locale.SIMPLIFIED_CHINESE);
        } else {
            return ResourceBundle.getBundle(BUNDLE_NAME, Locale.ENGLISH);
        }
    }
    
    /**
     * 获取国际化消息
     */
    public static String getMessage(String key) {
        try {
            return getResourceBundle().getString(key);
        } catch (Exception e) {
            return key; // 如果找不到，返回 key 本身
        }
    }

    /**
     * 获取带占位参数的国际化消息。
     */
    public static String getMessage(String key, Object... arguments) {
        return MessageFormat.format(getMessage(key), arguments);
    }
}
