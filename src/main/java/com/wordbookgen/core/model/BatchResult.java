package com.wordbookgen.core.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 分片执行结果。
 */
public record BatchResult(
        int batchIndex,
        String providerName,
        Map<String, JsonNode> entries
) {
}
