package com.taiwei.aiagent.util;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;

public final class JavaPluginAvailability {

    private static final boolean AVAILABLE;

    static {
        boolean available;
        try {
            available = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.java")) != null;
        } catch (Exception e) {
            available = false;
        }
        AVAILABLE = available;
    }

    private JavaPluginAvailability() {}

    public static boolean isJavaPluginAvailable() {
        return AVAILABLE;
    }
}
