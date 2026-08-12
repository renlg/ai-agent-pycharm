package com.taiwei.aiagent.util;

import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;

/** Runtime-only probe that keeps Python APIs off the compile classpath. */
public final class PythonPluginAvailability {

    private static final String[] PYTHON_PLUGIN_IDS = {
            "com.intellij.python",
            "org.jetbrains.plugins.python"
    };

    private static final boolean AVAILABLE = detectAvailability();

    private PythonPluginAvailability() {}

    public static boolean isPythonPluginAvailable() {
        return AVAILABLE;
    }

    private static boolean detectAvailability() {
        try {
            for (String pluginId : PYTHON_PLUGIN_IDS) {
                var plugin = PluginManagerCore.getPlugin(PluginId.getId(pluginId));
                if (plugin != null && plugin.isEnabled()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // A missing or incompatible plugin must never prevent Taiwei from loading.
        }
        return false;
    }
}
