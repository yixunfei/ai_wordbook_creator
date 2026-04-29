package com.wordbookgen.core.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.wordbookgen.core.model.BatchTask;
import com.wordbookgen.core.model.DictionaryFields;
import com.wordbookgen.core.provider.ProviderExceptions.RetryableProviderException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Extracts and validates model output from OpenAI-compatible and compatible-provider responses.
 */
class ProviderOutputParser {

    private final ObjectMapper mapper;
    private final ObjectMapper lenientMapper;
    private final String providerName;

    ProviderOutputParser(ObjectMapper mapper, String providerName) {
        this.mapper = mapper;
        this.lenientMapper = createLenientMapper();
        this.providerName = providerName == null || providerName.isBlank() ? "provider" : providerName;
    }

    ProviderModelContent extractModelContent(
            String responseBody,
            boolean allowNonStandardResponses,
            boolean allowTruncated
    ) throws RetryableProviderException {
        JsonNode root = parseResponseRoot(responseBody, allowNonStandardResponses);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            if (allowNonStandardResponses) {
                String directContent = extractDirectModelContent(root);
                if (directContent != null && !directContent.isBlank()) {
                    return new ProviderModelContent(directContent, false);
                }
            }
            throw new RetryableProviderException("response missing choices");
        }

        JsonNode firstChoice = choices.get(0);
        String finishReason = firstChoice.path("finish_reason").asText("");
        boolean truncated = "length".equalsIgnoreCase(finishReason);

        JsonNode content = firstChoice.path("message").path("content");
        String contentText = null;
        if (content.isTextual()) {
            contentText = content.asText();
        } else if (allowNonStandardResponses) {
            contentText = extractTextFromContentNode(content);
        }
        if (contentText != null && !contentText.isBlank()) {
            if (truncated && !allowTruncated) {
                throw new RetryableProviderException("model output was truncated (finish_reason=length)");
            }
            return new ProviderModelContent(contentText, truncated);
        }

        throw new RetryableProviderException("response missing message.content text");
    }

    Map<String, JsonNode> parseAndValidateModelText(
            BatchTask task,
            String modelText,
            boolean debugMode,
            boolean allowNonStandardResponses,
            Consumer<String> log
    ) throws RetryableProviderException {
        ArrayNode arrayNode = parseAsArray(
                modelText,
                debugMode,
                allowNonStandardResponses,
                log,
                task.index(),
                task.words().size());
        try {
            return validate(task, arrayNode);
        } catch (RetryableProviderException ex) {
            logSingleWordFailureContext(task, modelText, arrayNode, log);
            throw ex;
        }
    }

    void validatePostParsedHookResult(BatchTask task, Map<String, JsonNode> entries) throws RetryableProviderException {
        Set<String> expected = new HashSet<>();
        for (String word : task.words()) {
            expected.add(normalize(word));
        }

        if (entries == null || entries.size() != expected.size()) {
            throw new RetryableProviderException("post hook entries size mismatch");
        }

        for (Map.Entry<String, JsonNode> entry : entries.entrySet()) {
            String wordKey = normalize(entry.getKey());
            if (!expected.contains(wordKey)) {
                throw new RetryableProviderException("post hook returned unexpected word: " + wordKey);
            }

            JsonNode node = entry.getValue();
            if (node == null || !node.isObject()) {
                throw new RetryableProviderException("post hook entry is not object: " + wordKey);
            }
            for (String field : DictionaryFields.REQUIRED) {
                if (!node.has(field)) {
                    throw new RetryableProviderException("post hook missing required field: " + field);
                }
            }

            String normalizedWordValue = normalize(node.path(DictionaryFields.WORD).asText(""));
            if (normalizedWordValue.isEmpty()) {
                throw new RetryableProviderException("post hook word field is empty: " + wordKey);
            }
            if (!normalizedWordValue.equals(wordKey)) {
                throw new RetryableProviderException(
                        "post hook word mismatch: key=" + wordKey + ", field=" + normalizedWordValue);
            }
        }
    }

    private JsonNode parseResponseRoot(String responseBody, boolean allowNonStandardResponses) throws RetryableProviderException {
        if (!allowNonStandardResponses) {
            try {
                return mapper.readTree(responseBody);
            } catch (JsonProcessingException ex) {
                throw new RetryableProviderException("response body not valid json", ex);
            }
        }
        JsonNode root = tryParse(responseBody);
        if (root == null) {
            String extracted = tryExtractOuterJson(responseBody);
            root = extracted == null ? null : tryParse(extracted);
        }
        if (root == null) {
            throw new RetryableProviderException("response body not valid json");
        }
        return root;
    }

    private String extractDirectModelContent(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }

        String text = extractTextFromContentNode(root.path("message").path("content"));
        if (text == null || text.isBlank()) {
            text = extractTextFromContentNode(root.path("content"));
        }
        if (text == null || text.isBlank()) {
            text = extractTextFromContentNode(root.path("output_text"));
        }
        if (text == null || text.isBlank()) {
            text = extractTextFromContentNode(root.path("text"));
        }
        if (text == null || text.isBlank()) {
            text = extractTextFromContentNode(root.path("response"));
        }
        if (text != null && !text.isBlank()) {
            return text;
        }

        JsonNode output = root.path("output");
        if (output.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : output) {
                String itemText = extractTextFromContentNode(item.path("content"));
                if (itemText != null) {
                    sb.append(itemText);
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        }

        if (looksLikeDictionaryPayload(root)) {
            return root.toString();
        }
        return null;
    }

    private String extractTextFromContentNode(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isObject()) {
            JsonNode textNode = content.path("text");
            if (textNode.isTextual()) {
                return textNode.asText();
            }
            JsonNode outputTextNode = content.path("output_text");
            if (outputTextNode.isTextual()) {
                return outputTextNode.asText();
            }
            if (looksLikeDictionaryPayload(content)) {
                return content.toString();
            }
            return null;
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                String partText = extractTextFromContentNode(part);
                if (partText != null) {
                    sb.append(partText);
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
            if (looksLikeDictionaryPayload(content)) {
                return content.toString();
            }
        }
        return null;
    }

    private boolean looksLikeDictionaryPayload(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isArray()) {
            return true;
        }
        if (!node.isObject()) {
            return false;
        }
        if (node.has(DictionaryFields.WORD)) {
            return true;
        }
        for (String key : Arrays.asList("data", "items", "result", "results", "words", "entries")) {
            JsonNode nested = node.get(key);
            if (nested != null && (nested.isArray() || nested.isObject())) {
                return true;
            }
        }
        return false;
    }

    private ArrayNode parseAsArray(
            String content,
            boolean debugMode,
            boolean allowNonStandardResponses,
            Consumer<String> log,
            int batchIndex,
            int wordCount
    ) throws RetryableProviderException {
        String stripped = allowNonStandardResponses
                ? normalizeModelOutputText(stripFence(content))
                : normalizeStrictJsonText(content);
        JsonNode parsed = allowNonStandardResponses ? tryParse(stripped) : tryParseStrict(stripped);
        if (parsed == null && allowNonStandardResponses) {
            String extracted = tryExtractOuterJson(stripped);
            parsed = extracted == null ? null : tryParse(extracted);
        }

        if (parsed == null) {
            boolean forceDump = debugMode || wordCount <= 1;
            if (forceDump) {
                debugLog(log, true, "Unparseable model output full text (batch=" + batchIndex + ")", stripped);
            }
            throw new RetryableProviderException("model output is not parseable json: " + summarize(stripped));
        }

        if (parsed.isArray()) {
            return (ArrayNode) parsed;
        }

        if (parsed.isObject()) {
            for (String key : Arrays.asList("data", "items", "result", "results", "words", "entries")) {
                JsonNode nested = parsed.get(key);
                if (nested != null && nested.isArray()) {
                    return (ArrayNode) nested;
                }
            }
            if (!allowNonStandardResponses) {
                throw new RetryableProviderException("model output object missing items array");
            }
            ArrayNode wrapped = mapper.createArrayNode();
            wrapped.add(parsed);
            return wrapped;
        }

        throw new RetryableProviderException("model output json type is unsupported");
    }

    private void logSingleWordFailureContext(
            BatchTask task,
            String modelText,
            ArrayNode parsedArray,
            Consumer<String> log
    ) {
        if (task == null || task.words() == null || task.words().size() > 1 || log == null) {
            return;
        }
        debugLog(log, true,
                "Single-word batch raw model text before validation (batch=" + task.index() + ")",
                modelText);
        String parsedJson;
        try {
            parsedJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsedArray);
        } catch (JsonProcessingException ex) {
            parsedJson = parsedArray == null ? "null" : parsedArray.toString();
        }
        debugLog(log, true,
                "Single-word batch parsed JSON before validation (batch=" + task.index() + ")",
                parsedJson);
    }

    private Map<String, JsonNode> validate(BatchTask task, ArrayNode arrayNode) throws RetryableProviderException {
        Set<String> expected = new HashSet<>();
        for (String word : task.words()) {
            expected.add(normalize(word));
        }

        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode item : arrayNode) {
            if (!item.isObject()) {
                throw new RetryableProviderException("response item is not object");
            }

            for (String field : DictionaryFields.REQUIRED) {
                if (!item.has(field)) {
                    throw new RetryableProviderException("missing required field: " + field);
                }
            }

            String word = normalize(item.path(DictionaryFields.WORD).asText(""));
            if (word.isEmpty()) {
                throw new RetryableProviderException("empty field: " + DictionaryFields.WORD);
            }
            if (!expected.contains(word)) {
                throw new RetryableProviderException("unexpected word from model: " + word);
            }
            if (result.containsKey(word)) {
                throw new RetryableProviderException("duplicate word from model: " + word);
            }
            result.put(word, item);
        }

        if (result.size() != expected.size()) {
            Set<String> missing = new HashSet<>(expected);
            missing.removeAll(result.keySet());
            throw new RetryableProviderException("missing words from response: " + missing);
        }
        return result;
    }

    private JsonNode tryParse(String candidate) {
        return tryParse(candidate, 0);
    }

    private JsonNode tryParseStrict(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(candidate);
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode tryParse(String candidate, int depth) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String normalized = normalizeModelOutputText(candidate);
        JsonNode parsed = tryParseWith(mapper, normalized);
        if (parsed == null) {
            parsed = tryParseWith(lenientMapper, normalized);
        }
        if (parsed != null && parsed.isTextual() && depth < 1) {
            return tryParse(parsed.asText(), depth + 1);
        }
        return parsed;
    }

    private JsonNode tryParseWith(ObjectMapper parser, String candidate) {
        if (parser == null || candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return parser.readTree(candidate);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripFence(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFence <= firstLineEnd) {
            return trimmed;
        }
        return trimmed.substring(firstLineEnd + 1, lastFence).trim();
    }

    private String tryExtractOuterJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != '[' && ch != '{') {
                continue;
            }
            String candidate = extractBalancedJson(text, i);
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            JsonNode parsed = tryParse(candidate);
            if (parsed != null) {
                return candidate;
            }
        }
        return null;
    }

    private String normalizeModelOutputText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace('\u201C', '"')
                .replace('\u201D', '"')
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .trim();
        normalized = normalizeJsonDelimitersOutsideStrings(normalized);
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    private String normalizeStrictJsonText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .trim();
    }

    private String normalizeJsonDelimitersOutsideStrings(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                sb.append(ch);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                sb.append(ch);
                continue;
            }

            switch (ch) {
                case '，' -> sb.append(',');
                case '：' -> sb.append(':');
                case '；' -> sb.append(';');
                case '【' -> sb.append('[');
                case '】' -> sb.append(']');
                case '｛' -> sb.append('{');
                case '｝' -> sb.append('}');
                default -> sb.append(ch);
            }
        }
        return sb.toString();
    }

    private ObjectMapper createLenientMapper() {
        ObjectMapper parser = new ObjectMapper();
        parser.configure(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(), true);
        parser.configure(JsonReadFeature.ALLOW_YAML_COMMENTS.mappedFeature(), true);
        parser.configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true);
        parser.configure(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature(), true);
        parser.configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
        parser.configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true);
        parser.configure(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.mappedFeature(), true);
        return parser;
    }

    private String extractBalancedJson(String text, int start) {
        if (text == null || start < 0 || start >= text.length()) {
            return null;
        }
        char open = text.charAt(start);
        char close = open == '[' ? ']' : '}';
        if (open != '[' && open != '{') {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                continue;
            }
            if (ch == open) {
                depth++;
                continue;
            }
            if (ch == close) {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private String summarize(String body) {
        if (body == null) {
            return "";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        int maxLen = 240;
        if (compact.length() <= maxLen) {
            return compact;
        }
        return compact.substring(0, maxLen) + "...";
    }

    private void debugLog(Consumer<String> log, boolean enabled, String title, String content) {
        if (!enabled || log == null) {
            return;
        }
        String safeContent = content == null ? "" : content;
        log.accept("[debug][" + providerName + "] ===== " + title + " BEGIN =====");
        log.accept(safeContent);
        log.accept("[debug][" + providerName + "] ===== " + title + " END =====");
    }

    private String normalize(String word) {
        if (word == null) {
            return "";
        }
        return word.trim().toLowerCase(Locale.ROOT);
    }
}
