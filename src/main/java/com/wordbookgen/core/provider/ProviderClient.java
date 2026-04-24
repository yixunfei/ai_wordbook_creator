package com.wordbookgen.core.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.wordbookgen.core.PauseController;
import com.wordbookgen.core.PromptBuilder;
import com.wordbookgen.core.model.BatchTask;
import com.wordbookgen.core.model.DictionaryFields;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final ObjectMapper lenientMapper;
    private final HttpClient httpClient;
    private final RequestQuotaLimiter quotaLimiter;
    private final Semaphore concurrencyGuard;
    private final ScriptHookExecutor hookExecutor;
    private volatile boolean disableResponseFormat = false;
    private volatile boolean disableMaxTokens = false;

    public ProviderClient(ProviderConfig config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
        this.lenientMapper = createLenientMapper();
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

            ModelContent modelContent = extractModelContent(
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
            validatePostParsedHookResult(task, entries);
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
            return parseAndValidateModelText(task, modelText, debugMode, jobConfig.allowNonStandardResponses(), log);
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
                return parseAndValidateModelText(task, repairedText, debugMode, true, log);
            } catch (RetryableProviderException repairFailure) {
                repairFailure.addSuppressed(firstFailure);
                throw new RetryableProviderException(
                        "model output repair failed: " + detailedMessage(repairFailure)
                                + "; original=" + detailedMessage(firstFailure),
                        repairFailure);
            }
        }
    }

    private Map<String, JsonNode> parseAndValidateModelText(
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

        String repairSystemPrompt = buildRepairSystemPrompt(jobConfig);
        String repairUserPrompt = buildRepairUserPrompt(jobConfig, task, invalidModelText, firstFailure);
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

        String repairedText = extractModelContent(body, true, true).text();
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
                    buildContinuationSystemPrompt(),
                    buildContinuationUserPrompt(task, originalSystemPrompt, originalUserPrompt, combined.toString()),
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

            ModelContent continuation = extractModelContent(body, true, true);
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

    private String buildRepairSystemPrompt(JobConfig jobConfig) {
        String sourceLanguage = safe(jobConfig.sourceLanguage(), "English");
        String targetLanguage = safe(jobConfig.targetLanguage(), "Chinese");
        return "You repair malformed dictionary JSON. "
                + "Return strict JSON only, no markdown, no code fence, no comments, no explanations. "
                + "The top-level shape must be {\"items\":[...]}. "
                + "Use the exact required field names and produce exactly one item per input word. "
                + "If a previous value is unusable or missing, regenerate a concise dictionary value. "
                + "Source language: " + sourceLanguage + ". "
                + "Target explanation language: " + targetLanguage + ".";
    }

    private String buildContinuationSystemPrompt() {
        return "You continue a truncated JSON response. "
                + "Return only the missing suffix that should be appended to the previous output. "
                + "Do not repeat prior content. Do not use markdown, code fences, comments, or explanations.";
    }

    private String buildContinuationUserPrompt(
            BatchTask task,
            String originalSystemPrompt,
            String originalUserPrompt,
            String partialText
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("The previous response was cut off because it reached the token limit.\n");
        sb.append("Continue the same JSON output from the next character only.\n");
        sb.append("Input words JSON: ").append(toJsonString(task.words())).append("\n");
        sb.append("Original system prompt as JSON string:\n")
                .append(toJsonString(compactForPrompt(originalSystemPrompt, 4000)))
                .append("\n");
        sb.append("Original user prompt as JSON string:\n")
                .append(toJsonString(compactForPrompt(originalUserPrompt, 6000)))
                .append("\n");
        sb.append("Already received output as JSON string:\n")
                .append(toJsonString(compactForPrompt(partialText, 12000)))
                .append("\n");
        sb.append("Return only the remaining suffix.");
        return sb.toString();
    }

    private String buildRepairUserPrompt(
            JobConfig jobConfig,
            BatchTask task,
            String invalidModelText,
            RetryableProviderException firstFailure
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Repair the previous model output into valid JSON.\n");
        sb.append("Failure reason: ").append(detailedMessage(firstFailure)).append("\n");
        sb.append("Input words JSON: ").append(toJsonString(task.words())).append("\n");
        sb.append("Item count must be exactly ").append(task.words().size()).append(".\n");
        sb.append("Language scope: source=")
                .append(safe(jobConfig.sourceLanguage(), "English"))
                .append(", target explanation=")
                .append(safe(jobConfig.targetLanguage(), "Chinese"))
                .append(".\n");
        sb.append("Each item field '").append(DictionaryFields.WORD)
                .append("' must equal one input word exactly, case-insensitive.\n");
        sb.append("Required schema:\n");
        appendRequiredSchema(sb);
        sb.append("Output top-level JSON object only: {\"items\":[...]}\n");
        sb.append("Previous invalid model output as JSON string:\n");
        sb.append(toJsonString(compactForPrompt(invalidModelText, 12000))).append("\n");
        sb.append("Return only the repaired JSON object now.");
        return sb.toString();
    }

    private void appendRequiredSchema(StringBuilder sb) {
        sb.append("- ").append(DictionaryFields.WORD).append(": string\n");
        sb.append("- ").append(DictionaryFields.PHONETIC).append(": string\n");
        sb.append("- ").append(DictionaryFields.PART_OF_SPEECH).append(": string\n");
        sb.append("- ").append(DictionaryFields.CORE_MEANING).append(": string\n");
        sb.append("- ").append(DictionaryFields.WORD_FORMS).append(": string\n");
        sb.append("- ").append(DictionaryFields.COMMON_PHRASES).append(": string[]\n");
        sb.append("- ").append(DictionaryFields.EXAMPLE_SENTENCES)
                .append(": object with keys ")
                .append(DictionaryFields.SUBFIELD_EN)
                .append(", ")
                .append(DictionaryFields.SUBFIELD_ZH)
                .append("\n");
        sb.append("- ").append(DictionaryFields.AFFIX_ANALYSIS).append(": string\n");
        sb.append("- ").append(DictionaryFields.MEMORY_STORY)
                .append(": object with keys ")
                .append(DictionaryFields.SUBFIELD_EN)
                .append(", ")
                .append(DictionaryFields.SUBFIELD_ZH)
                .append("\n");
    }

    private String compactForPrompt(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String compact = text.trim();
        if (compact.length() <= maxChars) {
            return compact;
        }
        int head = Math.max(1000, maxChars / 2);
        int tail = Math.max(1000, maxChars - head - 120);
        return compact.substring(0, Math.min(head, compact.length()))
                + "\n...[content truncated for JSON repair]...\n"
                + compact.substring(Math.max(0, compact.length() - tail));
    }

    private String toJsonString(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private ModelContent extractModelContent(
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
                    return new ModelContent(directContent, false);
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
            // Some compatible providers use content arrays instead of a plain string.
            contentText = extractTextFromContentNode(content);
        }
        if (contentText != null && !contentText.isBlank()) {
            if (truncated && !allowTruncated) {
                throw new RetryableProviderException("model output was truncated (finish_reason=length)");
            }
            return new ModelContent(contentText, truncated);
        }

        throw new RetryableProviderException("response missing message.content text");
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

    /**
     * 对钩子修改后的 entries 结果做二次校验，避免脚本破坏结构约束。
     */
    private void validatePostParsedHookResult(BatchTask task, Map<String, JsonNode> entries) throws RetryableProviderException {
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

    private String normalize(String word) {
        if (word == null) {
            return "";
        }
        return word.trim().toLowerCase(Locale.ROOT);
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
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

    private record ModelContent(String text, boolean truncated) {
    }

    private record RequestBodyOptions(boolean useResponseFormat, boolean includeMaxTokens) {
    }
}
