package com.taiwei.aiagent.tool;

import com.intellij.openapi.diagnostic.Logger;

/**
 * Unified text representation for tool failures.
 *
 * Tool.execute intentionally continues to return String so the LLM-facing tool result
 * schema remains unchanged. The stable fields make failures actionable and machine-readable.
 */
public final class ToolError {

    private ToolError() {
    }

    public static String of(String code, String message, String hint) {
        StringBuilder result = new StringBuilder("[tool_error]\n")
                .append("code: ").append(code).append('\n')
                .append("message: ").append(safe(message));
        if (hint != null && !hint.isBlank()) {
            result.append('\n').append("hint: ").append(hint);
        }
        return result.toString();
    }

    /** Unexpected internal failures are always logged with their stack trace. */
    public static String unexpected(Logger logger, String logMessage, Throwable cause,
                                    String userMessage, String hint) {
        logger.error(logMessage, cause);
        return of("INTERNAL_ERROR", userMessage, hint);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "Unknown error" : value;
    }
}
