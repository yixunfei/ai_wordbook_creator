package com.wordbookgen.core.script;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 自定义脚本钩子执行器。
 * 输入输出统一为 JSON，便于跨语言扩展。
 */
public class ScriptHookExecutor {

    private final ObjectMapper mapper;

    public ScriptHookExecutor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public HookPromptResult applyPreRequestHook(
            JobConfig jobConfig,
            ProviderConfig providerConfig,
            int batchIndex,
            List<String> words,
            String systemPrompt,
            String userPrompt,
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

        JsonNode response = executeHook(hook, payload, log);
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

        JsonNode response = executeHook(hook, payload, log);
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

        JsonNode response = executeHook(hook, payload, log);
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

    private JsonNode executeHook(ScriptHookConfig hook, ObjectNode payload, Consumer<String> log) throws ScriptHookException {
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

        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(
                () -> readStreamSafely(process.getInputStream()));
        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(
                () -> readStreamSafely(process.getErrorStream()));

        try (OutputStream stdin = process.getOutputStream()) {
            byte[] input = mapper.writeValueAsBytes(payload);
            stdin.write(input);
            stdin.flush();
        } catch (IOException ex) {
            destroyProcess(process);
            throw new ScriptHookException("failed to write hook input: " + ex.getMessage(), ex);
        }

        boolean finished;
        try {
            finished = process.waitFor(Math.max(1, hook.timeoutSec()), TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            destroyProcess(process);
            throw new ScriptHookException("hook interrupted", ex);
        }

        if (!finished) {
            destroyProcess(process);
            String stderr = awaitStream(stderrFuture, 2, TimeUnit.SECONDS);
            String suffix = stderr.isBlank() ? "" : ", stderr=" + summarize(stderr);
            throw new ScriptHookException("hook timed out after " + hook.timeoutSec() + "s" + suffix);
        }

        String stdout = awaitStream(stdoutFuture, 2, TimeUnit.SECONDS);
        String stderr = awaitStream(stderrFuture, 2, TimeUnit.SECONDS);
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new ScriptHookException("hook exit code " + exitCode + ", stderr=" + summarize(stderr));
        }

        if (stdout == null || stdout.isBlank()) {
            return null;
        }

        try {
            return mapper.readTree(stdout);
        } catch (IOException ex) {
            throw new ScriptHookException("hook output is not valid json: " + summarize(stdout), ex);
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

    private String readStreamSafely(InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private String awaitStream(CompletableFuture<String> future, long timeout, TimeUnit unit) {
        try {
            return future.get(timeout, unit);
        } catch (Exception ex) {
            return "";
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

    public static class ScriptHookException extends Exception {
        public ScriptHookException(String message) {
            super(message);
        }

        public ScriptHookException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
