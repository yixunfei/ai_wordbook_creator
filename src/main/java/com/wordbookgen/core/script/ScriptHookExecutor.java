package com.wordbookgen.core.script;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wordbookgen.core.PauseController;
import com.wordbookgen.core.model.JobConfig;
import com.wordbookgen.core.model.ProviderConfig;
import com.wordbookgen.core.model.ScriptHookConfig;
import com.wordbookgen.core.model.ScriptHookStage;
import com.wordbookgen.core.model.ScriptLanguage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 自定义脚本钩子执行器。
 * 输入输出统一为 JSON，便于跨语言扩展。
 */
public class ScriptHookExecutor {

    static final int MAX_HOOK_OUTPUT_BYTES = 1024 * 1024;

    private final ObjectMapper mapper;
    private final ExecutorService streamReaderExecutor;

    public ScriptHookExecutor(ObjectMapper mapper) {
        this.mapper = mapper;
        this.streamReaderExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "wordbook-hook-stream-reader");
            t.setDaemon(true);
            return t;
        });
    }

    public HookPromptResult applyPreRequestHook(
            JobConfig jobConfig,
            ProviderConfig providerConfig,
            int batchIndex,
            List<String> words,
            String systemPrompt,
            String userPrompt,
            PauseController pauseController,
            Consumer<String> log
    ) throws ScriptHookException {
        ScriptHookConfig hook = jobConfig.preRequestHook();
        if (hook == null || !hook.enabled()) {
            return new HookPromptResult(systemPrompt, userPrompt);
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("stage", ScriptHookStage.PRE_REQUEST.name());
        payload.put("providerName", providerConfig.name());
        payload.put("providerModel", providerConfig.model());
        payload.put("batchIndex", batchIndex);
        payload.put("sourceLanguage", jobConfig.sourceLanguage());
        payload.put("targetLanguage", jobConfig.targetLanguage());
        payload.put("systemPrompt", systemPrompt);
        payload.put("userPrompt", userPrompt);

        ArrayNode wordsNode = payload.putArray("words");
        for (String word : words) {
            wordsNode.add(word);
        }

        JsonNode response = executeHook(hook, payload, pauseController, log);
        if (response == null || !response.isObject()) {
            return new HookPromptResult(systemPrompt, userPrompt);
        }

        String newSystemPrompt = response.path("systemPrompt").asText(systemPrompt);
        String newUserPrompt = response.path("userPrompt").asText(userPrompt);
        return new HookPromptResult(newSystemPrompt, newUserPrompt);
    }

    public String applyPostResponseHook(
            JobConfig jobConfig,
            ProviderConfig providerConfig,
            int batchIndex,
            int statusCode,
            String rawResponse,
            PauseController pauseController,
            Consumer<String> log
    ) throws ScriptHookException {
        ScriptHookConfig hook = jobConfig.postResponseHook();
        if (hook == null || !hook.enabled()) {
            return rawResponse;
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("stage", ScriptHookStage.POST_RESPONSE.name());
        payload.put("providerName", providerConfig.name());
        payload.put("providerModel", providerConfig.model());
        payload.put("batchIndex", batchIndex);
        payload.put("statusCode", statusCode);
        payload.put("rawResponse", rawResponse == null ? "" : rawResponse);

        JsonNode response = executeHook(hook, payload, pauseController, log);
        if (response == null || !response.isObject()) {
            return rawResponse;
        }
        return response.path("rawResponse").asText(rawResponse);
    }

    public Map<String, JsonNode> applyPostParsedHook(
            JobConfig jobConfig,
            ProviderConfig providerConfig,
            int batchIndex,
            Map<String, JsonNode> entries,
            PauseController pauseController,
            Consumer<String> log
    ) throws ScriptHookException {
        ScriptHookConfig hook = jobConfig.postParsedHook();
        if (hook == null || !hook.enabled()) {
            return entries;
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("stage", ScriptHookStage.POST_PARSED.name());
        payload.put("providerName", providerConfig.name());
        payload.put("providerModel", providerConfig.model());
        payload.put("batchIndex", batchIndex);
        payload.set("entries", mapper.valueToTree(entries));

        JsonNode response = executeHook(hook, payload, pauseController, log);
        if (response == null || !response.isObject()) {
            return entries;
        }
        JsonNode entriesNode = response.get("entries");
        if (entriesNode == null || !entriesNode.isObject()) {
            return entries;
        }
        return mapper.convertValue(entriesNode, new TypeReference<Map<String, JsonNode>>() {
        });
    }

    private JsonNode executeHook(
            ScriptHookConfig hook,
            ObjectNode payload,
            PauseController pauseController,
            Consumer<String> log
    ) throws ScriptHookException {
        ensureNotStopping(pauseController);
        List<String> command = buildCommand(hook);
        if (log != null) {
            log.accept("[hook] execute stage=" + payload.path("stage").asText() + ", command=" + String.join(" ", command));
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(false);
        processBuilder.environment().put("WORDBOOK_HOOK_STAGE", payload.path("stage").asText());
        Path scriptParent = resolveScriptParentDirectory(hook.scriptPath());
        if (scriptParent != null) {
            processBuilder.directory(scriptParent.toFile());
        }

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException ex) {
            throw new ScriptHookException("failed to start hook process: " + ex.getMessage(), ex);
        }

        CompletableFuture<HookStreamResult> stdoutFuture = CompletableFuture.supplyAsync(
                () -> readStreamSafely(process.getInputStream(), "stdout"),
                streamReaderExecutor);
        CompletableFuture<HookStreamResult> stderrFuture = CompletableFuture.supplyAsync(
                () -> readStreamSafely(process.getErrorStream(), "stderr"),
                streamReaderExecutor);

        try (OutputStream stdin = process.getOutputStream()) {
            byte[] input = mapper.writeValueAsBytes(payload);
            stdin.write(input);
            stdin.flush();
        } catch (IOException ex) {
            destroyProcess(process);
            throw new ScriptHookException("failed to write hook input: " + ex.getMessage(), ex);
        }

        HookWaitResult waitResult = waitForHook(process, Math.max(1, hook.timeoutSec()), pauseController);
        if (waitResult == HookWaitResult.STOPPED) {
            destroyProcess(process);
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            throw new ScriptHookException("hook canceled because job is stopping");
        }
        if (waitResult == HookWaitResult.TIMED_OUT) {
            destroyProcess(process);
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            HookStreamResult stderr = awaitStream(stderrFuture, 2, TimeUnit.SECONDS);
            String stderrText = stderr.text();
            String suffix = stderrText.isBlank() ? "" : ", stderr=" + summarize(stderrText);
            throw new ScriptHookException("hook timed out after " + hook.timeoutSec() + "s" + suffix);
        }

        HookStreamResult stdout = awaitStream(stdoutFuture, 2, TimeUnit.SECONDS);
        HookStreamResult stderr = awaitStream(stderrFuture, 2, TimeUnit.SECONDS);
        if (stdout.limitExceeded()) {
            throw new ScriptHookException("hook stdout exceeded " + MAX_HOOK_OUTPUT_BYTES + " bytes");
        }
        if (stderr.limitExceeded()) {
            throw new ScriptHookException("hook stderr exceeded " + MAX_HOOK_OUTPUT_BYTES + " bytes");
        }
        String stdoutText = stdout.text();
        String stderrText = stderr.text();
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new ScriptHookException("hook exit code " + exitCode + ", stderr=" + summarize(stderrText));
        }

        if (stdoutText == null || stdoutText.isBlank()) {
            return null;
        }

        try {
            return mapper.readTree(stdoutText);
        } catch (IOException ex) {
            throw new ScriptHookException("hook output is not valid json: " + summarize(stdoutText), ex);
        }
    }

    private List<String> buildCommand(ScriptHookConfig hook) throws ScriptHookException {
        ScriptLanguage language = hook.language() == null ? ScriptLanguage.JAVASCRIPT : hook.language();
        String scriptPath = hook.scriptPath() == null ? "" : hook.scriptPath().trim();
        if (scriptPath.isEmpty()) {
            throw new ScriptHookException("hook scriptPath is blank");
        }

        List<String> command = new ArrayList<>();
        switch (language) {
            case PYTHON -> {
                Path scriptFile = resolveScriptFile(scriptPath, language);
                requireScriptExists(scriptFile, language);
                command.add("python");
                command.add(scriptFile.toString());
            }
            case JAVASCRIPT -> {
                Path scriptFile = resolveScriptFile(scriptPath, language);
                requireScriptExists(scriptFile, language);
                command.add("node");
                command.add(scriptFile.toString());
            }
            case LUA -> {
                Path scriptFile = resolveScriptFile(scriptPath, language);
                requireScriptExists(scriptFile, language);
                command.add("lua");
                command.add(scriptFile.toString());
            }
            case JAVA -> {
                if (scriptPath.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    Path scriptFile = resolveScriptFile(scriptPath, language);
                    requireScriptExists(scriptFile, language);
                    command.add("java");
                    command.add("-jar");
                    command.add(scriptFile.toString());
                } else {
                    // 非 jar 的 Java 脚本场景由用户自行封装可执行命令入口。
                    command.add("java");
                    command.add(scriptPath);
                }
            }
            default -> throw new ScriptHookException("unsupported hook language: " + language);
        }
        return command;
    }

    private Path resolveScriptFile(String scriptPath, ScriptLanguage language) throws ScriptHookException {
        try {
            return Path.of(scriptPath).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new ScriptHookException("invalid script path for " + language + ": " + scriptPath, ex);
        }
    }

    private Path resolveScriptParentDirectory(String scriptPath) {
        if (scriptPath == null || scriptPath.isBlank()) {
            return null;
        }
        try {
            Path script = Path.of(scriptPath).toAbsolutePath().normalize();
            if (Files.exists(script)) {
                Path parent = script.getParent();
                if (parent != null && Files.exists(parent)) {
                    return parent;
                }
            }
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private void requireScriptExists(Path scriptFile, ScriptLanguage language) throws ScriptHookException {
        if (!Files.exists(scriptFile)) {
            throw new ScriptHookException("hook script file not found for " + language + ": " + scriptFile);
        }
        if (Files.isDirectory(scriptFile)) {
            throw new ScriptHookException("hook script path is a directory for " + language + ": " + scriptFile);
        }
    }

    private HookWaitResult waitForHook(
            Process process,
            int timeoutSec,
            PauseController pauseController
    ) throws ScriptHookException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec);
        while (true) {
            if (pauseController != null && pauseController.isStopRequested()) {
                return HookWaitResult.STOPPED;
            }
            try {
                if (process.waitFor(250L, TimeUnit.MILLISECONDS)) {
                    return HookWaitResult.FINISHED;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                destroyProcess(process);
                throw new ScriptHookException("hook interrupted", ex);
            }
            if (System.nanoTime() >= deadlineNanos) {
                return HookWaitResult.TIMED_OUT;
            }
        }
    }

    private void ensureNotStopping(PauseController pauseController) throws ScriptHookException {
        if (pauseController != null && pauseController.isStopRequested()) {
            throw new ScriptHookException("hook canceled because job is stopping");
        }
    }

    private HookStreamResult readStreamSafely(InputStream stream, String streamName) {
        try {
            LimitedBytes bytes = readLimited(stream, MAX_HOOK_OUTPUT_BYTES);
            return new HookStreamResult(
                    new String(bytes.bytes(), StandardCharsets.UTF_8),
                    bytes.limitExceeded());
        } catch (IOException ex) {
            return new HookStreamResult("__HOOK_STREAM_ERROR__ " + streamName + ": " + ex.getMessage(), false);
        }
    }

    private LimitedBytes readLimited(InputStream stream, int maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        byte[] output = new byte[Math.min(maxBytes, 8192)];
        int total = 0;
        boolean limitExceeded = false;
        while (true) {
            int read = stream.read(buffer);
            if (read < 0) {
                byte[] exact = new byte[total];
                System.arraycopy(output, 0, exact, 0, total);
                return new LimitedBytes(exact, limitExceeded);
            }
            int required = total + read;
            if (required > output.length) {
                byte[] expanded = new byte[Math.min(maxBytes, Math.max(required, output.length * 2))];
                System.arraycopy(output, 0, expanded, 0, total);
                output = expanded;
            }
            int copyLength = Math.min(read, output.length - total);
            System.arraycopy(buffer, 0, output, total, copyLength);
            total += copyLength;
            if (required > maxBytes) {
                limitExceeded = true;
            }
        }
    }

    private HookStreamResult awaitStream(CompletableFuture<HookStreamResult> future, long timeout, TimeUnit unit) {
        try {
            return future.get(timeout, unit);
        } catch (Exception ex) {
            return new HookStreamResult("", false);
        }
    }

    private void destroyProcess(Process process) {
        if (process == null) {
            return;
        }
        process.destroyForcibly();
        try {
            process.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private String summarize(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 180) {
            return compact;
        }
        return compact.substring(0, 180) + "...";
    }

    public record HookPromptResult(String systemPrompt, String userPrompt) {
    }

    private record LimitedBytes(byte[] bytes, boolean limitExceeded) {
    }

    private record HookStreamResult(String text, boolean limitExceeded) {
    }

    private enum HookWaitResult {
        FINISHED,
        TIMED_OUT,
        STOPPED
    }

    public static class ScriptHookException extends Exception {
        public ScriptHookException(String message) {
            super(message);
        }

        public ScriptHookException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
