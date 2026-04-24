package com.wordbookgen.core.model;

/**
 * 输出格式。
 */
public enum OutputFormat {
    JSON,
    CSV;

    public static OutputFormat fromText(String text) {
        if (text == null || text.isBlank()) {
            return JSON;
        }
        try {
            return OutputFormat.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return JSON;
        }
    }
}
