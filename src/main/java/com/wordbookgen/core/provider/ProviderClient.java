package com.wordbookgen.core.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wordbookgen.core.PauseController;
import com.wordbookgen.core.PromptBuilder;
import com.wordbookgen.core.model.BatchTask;
import com.wordbookgen.core.model.JobConfig;
import com.wordbookgen.core.model.ProviderConfig;
import com.wordbookgen.core.provider.ProviderExceptions.NonRetryableProviderException;
import com.wordbookgen.core.provider.ProviderExceptions.ProviderException;
import com.wordbookgen.core.provider.ProviderExceptions.RateLimitException;
import com.wordbookgen.core.provider.ProviderExceptions.RetryableProviderException;
import com.wordbookgen.core.script.ScriptHookExecutor;
import com.wordbookgen.core.script.ScriptHookExecutor.HookPromptResult;
import com.wordbookgen.core.script.ScriptHookExecutor.ScriptHookException;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * provider 客户端（OpenAI Chat Completions 兼容协议）。
 * Owns request fallback, cancellable async HTTP calls, response extraction, and model-output repair.
 */
public class ProviderClient {

    private static final int MAX_RESPONSE_TOKENS = 16_000;
    private static final int MAX_TRUNCATION_CONTINUATIONS = 3;

    private final ProviderConfig config;
    private final ObjectMapper mapper;
    private final ProviderOutputParser outputParser;
    private final ProviderPromptFactory promptFactory;
    private final HttpClient httpClient;
    private final RequestQuotaLimiter quotaLimiter;
    private final Semaphore concurrencyGuard;
    private final ScriptHookExecutor hookExecutor;
    private volatile boolean disableResponseFormat = false;
    private volatile boolean disableMaxTokens = false;

    public ProviderClient(ProviderConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.outputParser = new ProviderOutputParser(mapper, config.name());
        this.promptFactory = new ProviderPromptFactory(mapper);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.quotaLimiter = new RequestQuotaLimiter(
                config.quotaLimit(),
                Duration.ofMinutes(config.windowMinutes()));
        this.concurrencyGuard = new Semaphore(Math.max(1, Math.min(8, config.maxConcurrency())));
        this.hookExecutor = new ScriptHookExecutor(mapper);
        this.disableResponseFormat = isKnownUnsupportedResponseFormatProvider(config);
    }

    public String name() {
        return config.name();
    }

    public Map<String, JsonNode> translateBatch(
            BatchTask task,
            JobConfig jobConfig,
            PauseController pauseController,
            Consumer<String> log
    ) throws ProviderException, InterruptedException {
        long backoffMs = jobConfig.initialBackoff().toMillis();
        ProviderException last = null;

        for (int attempt = 1; attempt <= jobConfig.maxRetries(); attempt++) {
            pauseController.awaitIfPaused();
            if (pauseController.isStopRequested()) {
                throw new RetryableProviderException("job is stopping");
            }

            try {
                return callOnce(task, jobConfig, pauseController, log);
            } catch (RateLimitException ex) {
                last = ex;
                long waitMs = ex.getRetryAfterMs() > 0 ? ex.getRetryAfterMs() : randomizeBackoff(backoffMs);
                if (log != null) {
                    log.accept("[" + config.name() + "] rate limited, attempt=" + attempt + ", waitMs=" + waitMs
                            + ", detail=" + detailedMessage(ex));
                }
                pauseController.sleepInterruptibly(waitMs);
            } catch (RetryableProviderException ex) {
                last = ex;
                long waitMs = randomizeBackoff(backoffMs);
                if (log != null) {
                    log.accept("[" + config.name() + "] retryable error, attempt=" + attempt + ", waitMs=" + waitMs
                            + ", detail=" + detailedMessage(ex));
                }
                pauseController.sleepInterruptibly(waitMs);
            } catch (NonRetryableProviderException ex) {
                throw ex;
            }

            long doubled = backoffMs * 2;
            backoffMs = Math.min(jobConfig.maxBackoff().toMillis(), Math.max(doubled, jobConfig.initialBackoff().toMillis()));
        }

        throw new RetryableProviderException(
                "provider retries exhausted: " + config.name() + ", last=" + detailedMessage(last),
                last);
    }

    private Map<String, JsonNode> callOnce(
            BatchTask task,
            JobConfig jobConfig,
            PauseController pauseController,
            Consumer<String> log
    ) throws ProviderException, InterruptedException {
        quotaLimiter.acquire(config.name(), pauseController, log);
        concurrencyGuard.acquire();
        try {
            String systemPrompt = PromptBuilder.buildSystemPrompt(jobConfig);
            String userPrompt = PromptBuilder.buildUserPrompt(jobConfig, task.words());
            HookPromptResult adjustedPrompt = hookExecutor.applyPreRequestHook(
                    jobConfig,
                    config,
                    task.index(),
                    task.words(),
                    systemPrompt,
                    userPrompt,
                    log);

            Duration requestTimeout = effectiveRequestTimeout(jobConfig.requestTimeout(), task.words().size());
            HttpResponse<String> response = sendRequestWithFormatFallback(
                    adjustedPrompt.systemPrompt(),
                    adjustedPrompt.userPrompt(),
                    task.words().size(),
                    requestTimeout,
                    jobConfig,
                    pauseController,
                    log,
                    jobConfig.debugMode(),
                    task.index());

            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            debugLog(log, jobConfig.debugMode(),
                    "HTTP response raw (status=" + status + ", batch=" + task.index() + ")", body);
            body = hookExecutor.applyPostResponseHook(
                    jobConfig,
                    config,
                    task.index(),
                    status,
                    body,
                    log);
            debugLog(log, jobConfig.debugMode(),
                    "HTTP response after POST_RESPONSE hook (batch=" + task.index() + ")", body);

            ensureSuccessfulResponse(response, body);

            ProviderModelContent modelContent = outputParser.extractModelContent(
                    body,
                    jobConfig.allowNonStandardResponses(),
                    jobConfig.autoContinueTruncatedOutput());
            String modelText = modelContent.text();
            if (modelContent.truncated()) {
                modelText = continueTruncatedModelOutput(
                        task,
                        jobConfig,
                        pauseController,
                        requestTimeout,
                        adjustedPrompt.systemPrompt(),
                        adjustedPrompt.userPrompt(),
                        modelText,
                        log);
            }
            debugLog(log, jobConfig.debugMode(), "Model message.content text (batch=" + task.index() + ")", modelText);
            Map<String, JsonNode> entries = parseValidateWithRepair(
                    task,
                    jobConfig,
                    pauseController,
                    requestTimeout,
                    modelText,
                    jobConfig.debugMode(),
                    log);
            entries = hookExecutor.applyPostParsedHook(
                    jobConfig,
                    config,
                    task.index(),
                    entries,
                    log);
            outputParser.validatePostParsedHookResult(task, entries);
            return entries;
        } catch (ScriptHookException ex) {
            throw new NonRetryableProviderException("script hook failed: " + ex.getMessage(), ex);
        } finally {
            concurrencyGuard.release();
        }
    }

    private Map<String, JsonNode> parseValidateWithRepair(
            BatchTask task,
            JobConfig jobConfig,
            PauseController pauseController,
            Duration requestTimeout,
            String modelText,
            boolean debugMode,
            Consumer<String> log
    ) throws ProviderException, InterruptedException, ScriptHookException {
        try {
            return outputParser.parseAndValidateModelText(
                    task,
                    modelText,
                    debugMode,
                    jobConfig.allowNonStandardResponses(),
                    log);
        } catch (RetryableProviderException firstFailure) {
            if (!jobConfig.allowNonStandardResponses()) {
                throw firstFailure;
            }
            if (log != null) {
                log.accept("[" + config.name() + "] model output JSON invalid, requesting repair"
                        + " (batch=" + task.index() + ", reason=" + detailedMessage(firstFailure) + ")");
            }

            String repairedText = repairModelOutput(
                    task,
                    jobConfig,
                    pauseController,
                    requestTimeout,
                    modelText,
                    firstFailure,
                    log);
            try {
                return outputParser.parseAndValidateModelText(task, repairedText, debugMode, true, log);
            } catch (RetryableProviderException repairFailure) {
                repairFailure.addSuppressed(firstFailure);
                throw new RetryableProviderException(
                        "model output repair failed: " + detailedMessage(repairFailure)
                                + "; original=" + detailedMessage(firstFailure),
                        repairFailure);
            }
        }
    }

    private String repairModelOutput(
            BatchTask task,
            JobConfig jobConfig,
            PauseController pauseController,
            Duration requestTimeout,
            String invalidModelText,
            RetryableProviderException firstFailure,
            Consumer<String> log
    ) throws ProviderException, InterruptedException, ScriptHookException {
        pauseController.awaitIfPaused();
        if (pauseController.isStopRequested()) {
            throw new RetryableProviderException("job is stopping");
        }
        quotaLimiter.acquire(config.name(), pauseController, log);

        String repairSystemPrompt = promptFactory.buildRepairSystemPrompt(jobConfig);
        String repairUserPrompt = promptFactory.buildRepairUserPrompt(jobConfig, task, invalidModelText, firstFailure);
        HttpResponse<String> response = sendRequestWithFormatFallback(
                repairSystemPrompt,
                repairUserPrompt,
                task.words().size(),
                requestTimeout,
                jobConfig,
                pauseController,
                log,
                jobConfig.debugMode(),
                task.index());

        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        debugLog(log, jobConfig.debugMode(),
                "HTTP repair response raw (status=" + status + ", batch=" + task.index() + ")", body);
        body = hookExecutor.applyPostResponseHook(
                jobConfig,
                config,
                task.index(),
                status,
                body,
                log);
        debugLog(log, jobConfig.debugMode(),
                "HTTP repair response after POST_RESPONSE hook (batch=" + task.index() + ")", body);
        ensureSuccessfulResponse(response, body);

        String repairedText = outputParser.extractModelContent(body, true, true).text();
        debugLog(log, jobConfig.debugMode(),
                "Repaired model message.content text (batch=" + task.index() + ")", repairedText);
        return repairedText;
    }

    private String continueTruncatedModelOutput(
            BatchTask task,
            JobConfig jobConfig,
            PauseController pauseController,
            Duration requestTimeout,
            String originalSystemPrompt,
            String originalUserPrompt,
            String partialText,
            Consumer<String> log
    ) throws ProviderException, InterruptedException, ScriptHookException {
        // Ask for append-only suffixes; json_object response_format can block valid partial suffixes.
        StringBuilder combined = new StringBuilder(partialText == null ? "" : partialText);
        for (int round = 1; round <= MAX_TRUNCATION_CONTINUATIONS; round++) {
            pauseController.awaitIfPaused();
            if (pauseController.isStopRequested()) {
                throw new RetryableProviderException("job is stopping");
            }
            quotaLimiter.acquire(config.name(), pauseController, log);
            if (log != null) {
                log.accept("[" + config.name() + "] model output truncated, requesting continuation"
                        + " (batch=" + task.index() + ", round=" + round + ")");
            }

            HttpResponse<String> response = sendRequestWithFormatFallback(
                    promptFactory.buildContinuationSystemPrompt(),
                    promptFactory.buildContinuationUserPrompt(
                            task,
                            originalSystemPrompt,
                            originalUserPrompt,
                            combined.toString()),
                    task.words().size(),
                    requestTimeout,
                    jobConfig,
                    pauseController,
                    log,
                    jobConfig.debugMode(),
                    task.index(),
                    false);

            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();
            debugLog(log, jobConfig.debugMode(),
                    "HTTP continuation response raw (status=" + status + ", batch=" + task.index()
                            + ", round=" + round + ")", body);
            body = hookExecutor.applyPostResponseHook(
                    jobConfig,
                    config,
                    task.index(),
                    status,
                    body,
                    log);
            ensureSuccessfulResponse(response, body);

            ProviderModelContent continuation = outputParser.extractModelContent(body, true, true);
            combined.append(continuation.text());
            if (!continuation.truncated()) {
                return combined.toString();
            }
        }
        throw new RetryableProviderException("model output remained truncated after "
                + MAX_TRUNCATION_CONTINUATIONS + " continuation requests");
    }

    private HttpResponse<String> sendRequestWithFormatFallback(
            String systemPrompt,
            String userPrompt,
            int wordCount,
            Duration requestTimeout,
            JobConfig jobConfig,
            PauseController pauseController,
            Consumer<String> log,
            boolean debugMode,
            int batchIndex
    ) throws RetryableProviderException {
        return sendRequestWithFormatFallback(
                systemPrompt,
                userPrompt,
                wordCount,
                requestTimeout,
                jobConfig,
                pauseController,
                log,
                debugMode,
                batchIndex,
                true);
    }

    private HttpResponse<String> sendRequestWithFormatFallback(
            String systemPrompt,
            String userPrompt,
            int wordCount,
            Duration requestTimeout,
            JobConfig jobConfig,
            PauseController pauseController,
            Consumer<String> log,
            boolean debugMode,
            int batchIndex,
            boolean allowResponseFormat
    ) throws RetryableProviderException {
        List<RequestBodyOptions> attempts = new ArrayList<>();
        if (allowResponseFormat) {
            attempts.add(new RequestBodyOptions(true, true));
            attempts.add(new RequestBodyOptions(true, false));
        }
        attempts.add(new RequestBodyOptions(false, true));
        attempts.add(new RequestBodyOptions(false, false));

        HttpResponse<String> response = null;
        for (RequestBodyOptions options : attempts) {
            if (options.useResponseFormat() && disableResponseFormat) {
                continue;
            }
            if (options.includeMaxTokens() && disableMaxTokens) {
                continue;
            }
            String body = buildRequestBody(systemPrompt, userPrompt, wordCount, options);
            debugLog(log, debugMode,
                    "Request body attempt (batch=" + batchIndex
                            + ", response_format=" + options.useResponseFormat()
                            + ", max_tokens=" + options.includeMaxTokens() + ")", body);
            response = sendRaw(body, requestTimeout, jobConfig, pauseController);
            int status = response.statusCode();
            String responseBody = response.body() == null ? "" : response.body();
            debugLog(log, debugMode,
                    "Raw response for request attempt (batch=" + batchIndex
                            + ", status=" + status + ", response_format="
                            + options.useResponseFormat() + ", max_tokens=" + options.includeMaxTokens() + ")",
                    responseBody);
            if (status != 400) {
                return response;
            }

            if (options.useResponseFormat() && isUnsupportedResponseFormat(responseBody)) {
                boolean firstDisable = !disableResponseFormat;
                disableResponseFormat = true;
                if (log != null) {
                    log.accept("[" + config.name() + "] response_format not supported, fallback without response_format"
                            + " (batch=" + batchIndex + ")");
                    if (firstDisable) {
                        log.accept("[" + config.name() + "] capability cache: disable response_format for subsequent requests.");
                    }
                }
                continue;
            }
            if (options.includeMaxTokens() && isUnsupportedMaxTokens(responseBody)) {
                boolean firstDisable = !disableMaxTokens;
                disableMaxTokens = true;
                if (log != null) {
                    log.accept("[" + config.name() + "] max_tokens not supported, fallback without max_tokens"
                            + " (batch=" + batchIndex + ")");
                    if (firstDisable) {
                        log.accept("[" + config.name() + "] capability cache: disable max_tokens for subsequent requests.");
                    }
                }
                continue;
            }
            return response;
        }
        return response;
    }

    private HttpResponse<String> sendRaw(
            String requestBody,
            Duration requestTimeout,
            JobConfig jobConfig,
            PauseController pauseController
    ) throws RetryableProviderException {
        HttpRequest request = HttpRequest.newBuilder(config.endpoint())
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(jobConfig.encoding()));
        while (true) {
            if (pauseController != null && pauseController.isStopRequested()) {
                future.cancel(true);
                throw new RetryableProviderException("request canceled because job is stopping");
            }

            try {
                return future.get(250L, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Keep polling so a stop request can cancel the in-flight HTTP future promptly.
            } catch (InterruptedException ex) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new RetryableProviderException("request interrupted", ex);
            } catch (CancellationException ex) {
                throw new RetryableProviderException("request canceled", ex);
            } catch (ExecutionException ex) {
                throw mapAsyncRequestFailure(ex.getCause(), requestTimeout);
            }
        }
    }

    private RetryableProviderException mapAsyncRequestFailure(Throwable cause, Duration requestTimeout) {
        if (cause instanceof HttpTimeoutException) {
            return new RetryableProviderException(
                    "request timed out after " + requestTimeout.toSeconds() + "s",
                    cause);
        }
        if (cause instanceof IOException) {
            return new RetryableProviderException("request send failed", cause);
        }
        if (cause instanceof CancellationException) {
            return new RetryableProviderException("request canceled", cause);
        }
        return new RetryableProviderException(
                "request async failed: " + (cause == null ? "unknown" : cause.getMessage()),
                cause);
    }

    private String buildRequestBody(
            String systemPrompt,
            String userPrompt,
            int wordCount,
            RequestBodyOptions options
    ) throws RetryableProviderException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", this.config.model());
        root.put("stream", false);
        root.put("temperature", 0.2);
        if (options.includeMaxTokens()) {
            root.put("max_tokens", estimateMaxTokens(wordCount));
        }
        if (options.useResponseFormat()) {
            ObjectNode responseFormat = root.putObject("response_format");
            responseFormat.put("type", "json_object");
        }

        ArrayNode messages = root.putArray("messages");
        messages.addObject()
                .put("role", "system")
                .put("content", systemPrompt);
        messages.addObject()
                .put("role", "user")
                .put("content", userPrompt);

        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new RetryableProviderException("failed to serialize request body", ex);
        }
    }

    private void ensureSuccessfulResponse(HttpResponse<String> response, String body) throws ProviderException {
        int status = response.statusCode();
        if (status == 429) {
            throw new RateLimitException(
                    "remote 429: " + summarize(body),
                    parseRetryAfterMillis(response.headers()));
        }
        if (status >= 500 || status == 408 || status == 409 || status == 425) {
            throw new RetryableProviderException("remote transient status=" + status + ", body=" + summarize(body));
        }
        if (status < 200 || status >= 300) {
            throw new NonRetryableProviderException("remote non-retryable status=" + status + ", body=" + summarize(body));
        }
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

    private long parseRetryAfterMillis(HttpHeaders headers) {
        String retryAfter = headers.firstValue("Retry-After").orElse("").trim();
        if (retryAfter.isEmpty()) {
            return -1;
        }

        try {
            return Math.max(0L, Long.parseLong(retryAfter) * 1000L);
        } catch (NumberFormatException ignored) {
            // parse RFC-1123 datetime
        }

        try {
            ZonedDateTime dt = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0L, Duration.between(Instant.now(), dt.toInstant()).toMillis());
        } catch (Exception ignored) {
            return -1;
        }
    }

    private long randomizeBackoff(long baseMs) {
        long jitter = ThreadLocalRandom.current().nextLong(250L, 1000L);
        return Math.max(500L, baseMs + jitter);
    }

    private int estimateMaxTokens(int wordCount) {
        int safeWords = Math.max(1, wordCount);
        long estimate = 1200L + safeWords * 420L;
        long clamped = Math.max(1200L, Math.min(MAX_RESPONSE_TOKENS, estimate));
        return (int) clamped;
    }

    private Duration effectiveRequestTimeout(Duration configuredTimeout, int wordCount) {
        long baseSeconds = configuredTimeout == null ? 90L : Math.max(10L, configuredTimeout.toSeconds());
        if (wordCount <= 8) {
            return Duration.ofSeconds(baseSeconds);
        }
        long extra = Math.min(240L, (long) (wordCount - 8) * 6L);
        return Duration.ofSeconds(Math.min(900L, baseSeconds + extra));
    }

    private void debugLog(Consumer<String> log, boolean enabled, String title, String content) {
        if (!enabled || log == null) {
            return;
        }
        String safeContent = content == null ? "" : content;
        log.accept("[debug][" + config.name() + "] ===== " + title + " BEGIN =====");
        log.accept(safeContent);
        log.accept("[debug][" + config.name() + "] ===== " + title + " END =====");
    }

    private String detailedMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor != null && depth < 8) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            String type = cursor.getClass().getSimpleName();
            String msg = cursor.getMessage();
            if (msg == null || msg.isBlank()) {
                sb.append(type);
            } else {
                sb.append(type).append(": ").append(msg);
            }
            cursor = cursor.getCause();
            depth++;
        }
        return sb.toString();
    }

    private boolean isUnsupportedResponseFormat(String body) {
        return isUnsupportedParam(body, "response_format");
    }

    private boolean isUnsupportedMaxTokens(String body) {
        return isUnsupportedParam(body, "max_tokens");
    }

    private boolean isUnsupportedParam(String body, String paramName) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (lower.isBlank()) {
            return false;
        }

        String paramLower = paramName == null ? "" : paramName.toLowerCase(Locale.ROOT);
        String errorParam = extractErrorParam(body);
        boolean paramMatched = matchesUnsupportedParamName(errorParam, paramLower) || lower.contains(paramLower);
        if (!paramMatched) {
            return false;
        }

        return lower.contains("unsupported")
                || lower.contains("not support")
                || lower.contains("not valid")
                || lower.contains("invalid")
                || lower.contains("not available")
                || lower.contains("not allowed")
                || lower.contains("unknown")
                || lower.contains("unrecognized");
    }

    private boolean matchesUnsupportedParamName(String errorParam, String requestedParam) {
        if (errorParam == null || errorParam.isBlank() || requestedParam == null || requestedParam.isBlank()) {
            return false;
        }
        return errorParam.equals(requestedParam)
                || errorParam.startsWith(requestedParam + ".")
                || requestedParam.startsWith(errorParam + ".");
    }

    private boolean isKnownUnsupportedResponseFormatProvider(ProviderConfig providerConfig) {
        if (providerConfig == null || providerConfig.endpoint() == null) {
            return false;
        }
        String model = providerConfig.model() == null
                ? ""
                : providerConfig.model().toLowerCase(Locale.ROOT);
        String host = providerConfig.endpoint().getHost() == null
                ? ""
                : providerConfig.endpoint().getHost().toLowerCase(Locale.ROOT);
        String path = providerConfig.endpoint().getPath() == null
                ? ""
                : providerConfig.endpoint().getPath().toLowerCase(Locale.ROOT);
        return model.contains("doubao")
                || host.startsWith("ark.")
                || path.contains("/api/coding/");
    }

    private String extractErrorParam(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonNode root = mapper.readTree(responseBody);
            return root.path("error").path("param").asText("").toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private record RequestBodyOptions(boolean useResponseFormat, boolean includeMaxTokens) {
    }
}
