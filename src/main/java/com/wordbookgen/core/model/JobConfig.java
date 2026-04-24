package com.wordbookgen.core.model;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 任务配置。
 * 通过 Builder 聚合 UI 输入，避免构造函数参数过长。
 */
public final class JobConfig {

    private final List<ProviderConfig> providers;
    private final String customPrompt;
    private final Path inputPath;
    private final Path outputPath;
    private final Path checkpointPath;
    private final OutputFormat outputFormat;
    private final String sourceLanguage;
    private final String targetLanguage;
    private final Charset encoding;

    private final int batchSize;
    private final int parallelism;
    private final int maxRetries;

    private final Duration requestTimeout;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    private final boolean resumeFromCheckpoint;
    private final boolean clearCheckpointOnSuccess;
    private final boolean allowNonStandardResponses;
    private final boolean autoContinueTruncatedOutput;

    /**
     * 高级设置：系统提示词覆盖。
     */
    private final boolean useSystemPromptOverride;
    private final String systemPromptTemplate;

    /**
     * 高级设置：脚本钩子。
     */
    private final ScriptHookConfig preRequestHook;
    private final ScriptHookConfig postResponseHook;
    private final ScriptHookConfig postParsedHook;
    private final boolean debugMode;

    private JobConfig(Builder builder) {
        this.providers = List.copyOf(builder.providers);
        this.customPrompt = builder.customPrompt;
        this.inputPath = builder.inputPath;
        this.outputPath = builder.outputPath;
        this.checkpointPath = builder.checkpointPath;
        this.outputFormat = builder.outputFormat;
        this.sourceLanguage = builder.sourceLanguage;
        this.targetLanguage = builder.targetLanguage;
        this.encoding = builder.encoding;
        this.batchSize = builder.batchSize;
        this.parallelism = builder.parallelism;
        this.maxRetries = builder.maxRetries;
        this.requestTimeout = builder.requestTimeout;
        this.initialBackoff = builder.initialBackoff;
        this.maxBackoff = builder.maxBackoff;
        this.resumeFromCheckpoint = builder.resumeFromCheckpoint;
        this.clearCheckpointOnSuccess = builder.clearCheckpointOnSuccess;
        this.allowNonStandardResponses = builder.allowNonStandardResponses;
        this.autoContinueTruncatedOutput = builder.autoContinueTruncatedOutput;
        this.useSystemPromptOverride = builder.useSystemPromptOverride;
        this.systemPromptTemplate = builder.systemPromptTemplate;
        this.preRequestHook = builder.preRequestHook;
        this.postResponseHook = builder.postResponseHook;
        this.postParsedHook = builder.postParsedHook;
        this.debugMode = builder.debugMode;
    }

    public List<ProviderConfig> providers() {
        return providers;
    }

    public String customPrompt() {
        return customPrompt;
    }

    public Path inputPath() {
        return inputPath;
    }

    public Path outputPath() {
        return outputPath;
    }

    public Path checkpointPath() {
        return checkpointPath;
    }

    public OutputFormat outputFormat() {
        return outputFormat;
    }

    public String sourceLanguage() {
        return sourceLanguage;
    }

    public String targetLanguage() {
        return targetLanguage;
    }

    public Charset encoding() {
        return encoding;
    }

    public int batchSize() {
        return batchSize;
    }

    public int parallelism() {
        return parallelism;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration initialBackoff() {
        return initialBackoff;
    }

    public Duration maxBackoff() {
        return maxBackoff;
    }

    public boolean resumeFromCheckpoint() {
        return resumeFromCheckpoint;
    }

    public boolean clearCheckpointOnSuccess() {
        return clearCheckpointOnSuccess;
    }

    public boolean allowNonStandardResponses() {
        return allowNonStandardResponses;
    }

    public boolean autoContinueTruncatedOutput() {
        return autoContinueTruncatedOutput;
    }

    public boolean useSystemPromptOverride() {
        return useSystemPromptOverride;
    }

    public String systemPromptTemplate() {
        return systemPromptTemplate;
    }

    public ScriptHookConfig preRequestHook() {
        return preRequestHook;
    }

    public ScriptHookConfig postResponseHook() {
        return postResponseHook;
    }

    public ScriptHookConfig postParsedHook() {
        return postParsedHook;
    }

    public boolean debugMode() {
        return debugMode;
    }

    /**
     * 用于 checkpoint 兼容性判断。
     */
    public String fingerprint() {
        return String.join("|",
                outputFormat.name(),
                safe(sourceLanguage),
                safe(targetLanguage),
                safe(inputPath == null ? null : inputPath.toAbsolutePath().toString()),
                safe(outputPath == null ? null : outputPath.toAbsolutePath().toString()),
                String.valueOf(batchSize),
                String.valueOf(parallelism),
                String.valueOf(allowNonStandardResponses),
                String.valueOf(autoContinueTruncatedOutput),
                String.valueOf(useSystemPromptOverride),
                safe(systemPromptTemplate),
                hookFingerprint(preRequestHook),
                hookFingerprint(postResponseHook),
                hookFingerprint(postParsedHook));
    }

    private static String hookFingerprint(ScriptHookConfig hook) {
        if (hook == null) {
            return "hook:null";
        }
        return hook.enabled()
                + "," + hook.language()
                + "," + safe(hook.scriptPath())
                + "," + hook.timeoutSec();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private List<ProviderConfig> providers = new ArrayList<>();
        private String customPrompt = "";
        private Path inputPath;
        private Path outputPath;
        private Path checkpointPath;
        private OutputFormat outputFormat = OutputFormat.JSON;
        private String sourceLanguage = "English";
        private String targetLanguage = "Chinese";
        private Charset encoding = StandardCharsets.UTF_8;

        private int batchSize = 12;
        private int parallelism = 4;
        private int maxRetries = 5;

        private Duration requestTimeout = Duration.ofSeconds(180);
        private Duration initialBackoff = Duration.ofMillis(1200);
        private Duration maxBackoff = Duration.ofSeconds(20);

        private boolean resumeFromCheckpoint = true;
        private boolean clearCheckpointOnSuccess = true;
        private boolean allowNonStandardResponses = true;
        private boolean autoContinueTruncatedOutput = false;

        private boolean useSystemPromptOverride = false;
        private String systemPromptTemplate = "";
        private ScriptHookConfig preRequestHook = ScriptHookConfig.disabled();
        private ScriptHookConfig postResponseHook = ScriptHookConfig.disabled();
        private ScriptHookConfig postParsedHook = ScriptHookConfig.disabled();
        private boolean debugMode = false;

        public Builder providers(List<ProviderConfig> providers) {
            this.providers = providers == null ? Collections.emptyList() : providers;
            return this;
        }

        public Builder customPrompt(String customPrompt) {
            this.customPrompt = customPrompt == null ? "" : customPrompt;
            return this;
        }

        public Builder inputPath(Path inputPath) {
            this.inputPath = inputPath;
            return this;
        }

        public Builder outputPath(Path outputPath) {
            this.outputPath = outputPath;
            return this;
        }

        public Builder checkpointPath(Path checkpointPath) {
            this.checkpointPath = checkpointPath;
            return this;
        }

        public Builder outputFormat(OutputFormat outputFormat) {
            this.outputFormat = outputFormat == null ? OutputFormat.JSON : outputFormat;
            return this;
        }

        public Builder sourceLanguage(String sourceLanguage) {
            this.sourceLanguage = sourceLanguage == null ? "English" : sourceLanguage.trim();
            return this;
        }

        public Builder targetLanguage(String targetLanguage) {
            this.targetLanguage = targetLanguage == null ? "Chinese" : targetLanguage.trim();
            return this;
        }

        public Builder encoding(Charset encoding) {
            this.encoding = encoding == null ? StandardCharsets.UTF_8 : encoding;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder parallelism(int parallelism) {
            this.parallelism = parallelism;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        public Builder resumeFromCheckpoint(boolean resumeFromCheckpoint) {
            this.resumeFromCheckpoint = resumeFromCheckpoint;
            return this;
        }

        public Builder clearCheckpointOnSuccess(boolean clearCheckpointOnSuccess) {
            this.clearCheckpointOnSuccess = clearCheckpointOnSuccess;
            return this;
        }

        public Builder allowNonStandardResponses(boolean allowNonStandardResponses) {
            this.allowNonStandardResponses = allowNonStandardResponses;
            return this;
        }

        public Builder autoContinueTruncatedOutput(boolean autoContinueTruncatedOutput) {
            this.autoContinueTruncatedOutput = autoContinueTruncatedOutput;
            return this;
        }

        public Builder useSystemPromptOverride(boolean useSystemPromptOverride) {
            this.useSystemPromptOverride = useSystemPromptOverride;
            return this;
        }

        public Builder systemPromptTemplate(String systemPromptTemplate) {
            this.systemPromptTemplate = systemPromptTemplate == null ? "" : systemPromptTemplate;
            return this;
        }

        public Builder preRequestHook(ScriptHookConfig preRequestHook) {
            this.preRequestHook = preRequestHook == null ? ScriptHookConfig.disabled() : preRequestHook;
            return this;
        }

        public Builder postResponseHook(ScriptHookConfig postResponseHook) {
            this.postResponseHook = postResponseHook == null ? ScriptHookConfig.disabled() : postResponseHook;
            return this;
        }

        public Builder postParsedHook(ScriptHookConfig postParsedHook) {
            this.postParsedHook = postParsedHook == null ? ScriptHookConfig.disabled() : postParsedHook;
            return this;
        }

        public Builder debugMode(boolean debugMode) {
            this.debugMode = debugMode;
            return this;
        }

        public JobConfig build() {
            if (providers == null || providers.isEmpty()) {
                throw new IllegalArgumentException("At least one provider is required.");
            }
            if (inputPath == null) {
                throw new IllegalArgumentException("Input path is required.");
            }
            if (outputPath == null) {
                throw new IllegalArgumentException("Output path is required.");
            }

            batchSize = Math.max(1, batchSize);
            parallelism = Math.max(1, Math.min(8, parallelism));
            maxRetries = Math.max(1, maxRetries);
            requestTimeout = positiveOrDefault(requestTimeout, Duration.ofSeconds(180));
            initialBackoff = positiveOrDefault(initialBackoff, Duration.ofMillis(1200));
            maxBackoff = positiveOrDefault(maxBackoff, Duration.ofSeconds(20));
            if (maxBackoff.toMillis() < initialBackoff.toMillis()) {
                maxBackoff = initialBackoff;
            }

            if (checkpointPath == null) {
                checkpointPath = Path.of(outputPath + ".checkpoint.json");
            }

            validateHook(preRequestHook, "preRequestHook");
            validateHook(postResponseHook, "postResponseHook");
            validateHook(postParsedHook, "postParsedHook");

            return new JobConfig(this);
        }

        private void validateHook(ScriptHookConfig hook, String field) {
            if (hook != null && hook.enabled()) {
                if (hook.scriptPath() == null || hook.scriptPath().isBlank()) {
                    throw new IllegalArgumentException(field + " is enabled but scriptPath is blank.");
                }
            }
        }

        private Duration positiveOrDefault(Duration value, Duration fallback) {
            if (value == null || value.isZero() || value.isNegative()) {
                return fallback;
            }
            return value;
        }
    }
}
