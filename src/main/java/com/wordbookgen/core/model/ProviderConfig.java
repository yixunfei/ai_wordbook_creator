package com.wordbookgen.core.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务提供商配置（OpenAI 兼容协议）。
 */
public record ProviderConfig(
        String name,
        URI endpoint,
        String apiKey,
        String model,
        int maxConcurrency,
        int quotaLimit,
        int windowMinutes
) {

    public static final int DEFAULT_CONCURRENCY = 8;
    public static final int DEFAULT_QUOTA_LIMIT = 1000;
    public static final int DEFAULT_WINDOW_MINUTES = 300;

    public ProviderConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("provider name is required");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("provider endpoint is required");
        }
        String scheme = endpoint.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("provider endpoint must use http or https");
        }
        if (endpoint.getHost() == null || endpoint.getHost().isBlank()) {
            throw new IllegalArgumentException("provider endpoint host is required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("provider apiKey is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("provider model is required");
        }
    }

    /**
     * 多行文本解析：一行一个 provider。
     * 格式：name|url|apiKey|model|concurrency|quota|windowMinutes
     */
    public static List<ProviderConfig> parseLines(String text) {
        List<ProviderConfig> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }

        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            result.add(parseSingle(trimmed));
        }
        return result;
    }

    public static ProviderConfig parseSingle(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length < 4) {
            throw new IllegalArgumentException(
                    "Invalid provider format: " + line + ". expected name|url|apiKey|model|concurrency|quota|windowMinutes");
        }

        String name = required(parts[0], "name");
        URI endpoint = URI.create(required(parts[1], "url"));
        String apiKey = required(parts[2], "apiKey");
        String model = required(parts[3], "model");

        int concurrency = parseInt(parts, 4, DEFAULT_CONCURRENCY);
        int quotaLimit = parseInt(parts, 5, DEFAULT_QUOTA_LIMIT);
        int windowMinutes = parseInt(parts, 6, DEFAULT_WINDOW_MINUTES);

        concurrency = Math.max(1, Math.min(DEFAULT_CONCURRENCY, concurrency));
        quotaLimit = Math.max(1, quotaLimit);
        windowMinutes = Math.max(1, windowMinutes);

        return new ProviderConfig(name, endpoint, apiKey, model, concurrency, quotaLimit, windowMinutes);
    }

    private static String required(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("provider " + field + " is required");
        }
        return value.trim();
    }

    private static int parseInt(String[] parts, int index, int defaultValue) {
        if (parts.length <= index || parts[index] == null || parts[index].isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
