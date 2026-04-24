package com.wordbookgen.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wordbookgen.core.model.ProviderConfig;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.OpenAIChatModel;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Provider 连通性快速测试。
 * 优先走 AgentScope（与主调用框架保持一致），失败时回退原始 HTTP 探测。
 */
public class ProviderConnectivityTester {

    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(5);

    private static final ExecutorService AGENT_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "provider-connectivity-agent");
        t.setDaemon(true);
        return t;
    });

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public TestResult test(ProviderConfig providerConfig, Duration timeout) {
        Duration effectiveTimeout = normalizeTimeout(timeout);

        TestResult agentScopeResult = testByAgentScope(providerConfig, effectiveTimeout);
        if (agentScopeResult.success()) {
            return agentScopeResult;
        }

        TestResult httpFallbackResult = testByHttp(providerConfig, effectiveTimeout);
        if (httpFallbackResult.success()) {
            return TestResult.ok(httpFallbackResult.message() + " (http fallback)");
        }

        return TestResult.failed(
                "agentscope failed: " + agentScopeResult.message() + "; "
                        + "http fallback failed: " + httpFallbackResult.message());
    }

    private TestResult testByAgentScope(ProviderConfig providerConfig, Duration timeout) {
        List<String> candidates = deriveAgentScopeBaseUrlCandidates(providerConfig.endpoint());
        List<String> errors = new ArrayList<>();

        for (String baseUrl : candidates) {
            try {
                String content = requestByAgentScope(providerConfig, baseUrl, timeout);
                if (content == null || content.isBlank()) {
                    errors.add("base=" + baseUrl + " => empty content");
                    continue;
                }
                return TestResult.ok("connected via agentscope, base=" + baseUrl + ", response=\"" + summarize(content) + "\"");
            } catch (Exception ex) {
                errors.add("base=" + baseUrl + " => " + ex.getClass().getSimpleName() + ": " + summarize(ex.getMessage()));
            }
        }

        return TestResult.failed(String.join(" | ", errors));
    }

    private String requestByAgentScope(ProviderConfig providerConfig, String baseUrl, Duration timeout) throws Exception {
        ReActAgent agent = ReActAgent.builder()
                .name("connectivity-checker")
                .sysPrompt("You are a connectivity checker. Reply with OK only.")
                .model(OpenAIChatModel.builder()
                        .stream(false)
                        .baseUrl(baseUrl)
                        .apiKey(providerConfig.apiKey())
                        .modelName(providerConfig.model())
                        .build())
                .build();

        Future<Msg> future = AGENT_EXECUTOR.submit(() -> agent.call(
                Msg.builder().textContent("ping").build()
        ).block());

        Msg response;
        try {
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new IOException("agentscope request timeout after " + timeout.toSeconds() + "s", ex);
        }

        if (response == null) {
            throw new IOException("agentscope response is null");
        }
        return response.getTextContent();
    }

    private List<String> deriveAgentScopeBaseUrlCandidates(URI endpoint) {
        String raw = stripQuery(endpoint.toString());
        Set<String> candidates = new LinkedHashSet<>();

        addCandidate(candidates, removeSuffixIgnoreCase(raw, "/v1/chat/completions"));
        addCandidate(candidates, removeSuffixIgnoreCase(raw, "/chat/completions"));
        addCandidate(candidates, removeSuffixIgnoreCase(raw, "/v1/responses"));
        addCandidate(candidates, removeSuffixIgnoreCase(raw, "/responses"));
        addCandidate(candidates, removeSuffixIgnoreCase(raw, "/completions"));
        addCandidate(candidates, raw);

        if (candidates.isEmpty()) {
            candidates.add(raw);
        }
        return new ArrayList<>(candidates);
    }

    private void addCandidate(Set<String> set, String candidate) {
        if (candidate == null) {
            return;
        }
        String trimmed = trimTrailingSlash(candidate.trim());
        if (!trimmed.isBlank()) {
            set.add(trimmed);
        }
    }

    private String stripQuery(String url) {
        int queryIndex = url.indexOf('?');
        return queryIndex >= 0 ? url.substring(0, queryIndex) : url;
    }

    private String removeSuffixIgnoreCase(String value, String suffix) {
        if (value == null || suffix == null) {
            return value;
        }
        String lower = value.toLowerCase();
        String suffixLower = suffix.toLowerCase();
        if (!lower.endsWith(suffixLower)) {
            return null;
        }
        return value.substring(0, value.length() - suffix.length());
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private TestResult testByHttp(ProviderConfig providerConfig, Duration timeout) {
        try {
            String requestBody = buildHealthCheckPayload(providerConfig.model());
            HttpRequest request = HttpRequest.newBuilder(providerConfig.endpoint())
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + providerConfig.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();

            if (status < 200 || status >= 300) {
                return TestResult.failed("HTTP " + status + ", body=" + summarize(body));
            }

            String content = extractContent(body);
            if (content == null || content.isBlank()) {
                return TestResult.failed("no assistant content in response");
            }
            return TestResult.ok("connected via http, response=\"" + summarize(content) + "\"");
        } catch (Exception ex) {
            return TestResult.failed(ex.getClass().getSimpleName() + ": " + summarize(ex.getMessage()));
        }
    }

    private String buildHealthCheckPayload(String model) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("stream", false);
        root.put("temperature", 0);

        ArrayNode messages = root.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", "You are a connectivity checker. Reply with OK only.");
        messages.addObject()
                .put("role", "user")
                .put("content", "ping");

        return mapper.writeValueAsString(root);
    }

    private String extractContent(String body) throws IOException {
        JsonNode root = mapper.readTree(body);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray() && !content.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                JsonNode text = part.path("text");
                if (text.isTextual()) {
                    sb.append(text.asText());
                }
            }
            return sb.toString();
        }
        return null;
    }

    private Duration normalizeTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return MIN_TIMEOUT;
        }
        if (timeout.compareTo(MIN_TIMEOUT) < 0) {
            return MIN_TIMEOUT;
        }
        return timeout;
    }

    private String summarize(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 200) {
            return compact;
        }
        return compact.substring(0, 200) + "...";
    }

    public record TestResult(boolean success, String message) {
        public static TestResult ok(String message) {
            return new TestResult(true, message);
        }

        public static TestResult failed(String message) {
            return new TestResult(false, message);
        }
    }
}
