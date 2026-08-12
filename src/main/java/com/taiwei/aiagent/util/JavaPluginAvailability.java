package com.taiwei.aiagent.util;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;

public final class JavaPluginAvailability {

    private static final boolean AVAILABLE;

    static {
        boolean available;
        try {
            var javaPlugin = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.java"));
            available = javaPlugin != null && javaPlugin.isEnabled();
        } catch (Throwable e) {
            // Availability checks must never prevent the base plugin from loading in a
            // non-Java IDE (for example PyCharm).
            available = false;
        }
        AVAILABLE = available;
    }

    private JavaPluginAvailability() {}

    public static boolean isJavaPluginAvailable() {
        return AVAILABLE;
    }
}
