package com.wordbookgen.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wordbookgen.core.JobListener;
import com.wordbookgen.core.WordbookJobEngine;
import com.wordbookgen.core.model.JobConfig;
import com.wordbookgen.core.model.JobState;
import com.wordbookgen.core.model.OutputFormat;
import com.wordbookgen.core.model.ProgressSnapshot;
import com.wordbookgen.core.model.ProviderConfig;
import com.wordbookgen.core.model.ScriptHookConfig;
import com.wordbookgen.core.model.ScriptLanguage;
import com.wordbookgen.ui.UiConfigStore;
import com.wordbookgen.ui.UiProviderItem;
import com.wordbookgen.ui.UiScriptHookItem;
import com.wordbookgen.ui.UiSettings;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless entry point for scripts, scheduled jobs, and CI runners.
 */
public final class WordbookGenCli {

    private WordbookGenCli() {
    }

    public static int run(String[] args) {
        CliOptions options;
        try {
            options = CliOptions.parse(args);
            if (options.help()) {
                printUsage();
                return 0;
            }
            JobConfig config = buildConfig(options);
            AtomicBoolean failed = new AtomicBoolean(false);
            AtomicReference<JobState> lastState = new AtomicReference<>(JobState.IDLE);

            WordbookJobEngine engine = new WordbookJobEngine();
            Runtime.getRuntime().addShutdownHook(new Thread(engine::stop, "wordbook-cli-shutdown"));
            engine.runBlocking(config, listener(failed, lastState));

            JobState state = lastState.get();
            return failed.get() || state == JobState.FAILED ? 1 : 0;
        } catch (Exception ex) {
            System.err.println("CLI failed: " + ex.getMessage());
            return 2;
        }
    }

    private static JobListener listener(AtomicBoolean failed, AtomicReference<JobState> lastState) {
        return new JobListener() {
            @Override
            public void onLog(String message) {
                System.out.println(message);
            }

            @Override
            public void onStateChanged(JobState state) {
                lastState.set(state);
                System.out.println("[state] " + state.name());
            }

            @Override
            public void onProgress(ProgressSnapshot snapshot) {
                System.out.println(String.format(Locale.ROOT,
                        "[progress] %d/%d pending=%d batches=%d %s",
                        snapshot.completedWords(),
                        snapshot.totalWords(),
                        snapshot.pendingWords(),
                        snapshot.completedBatches(),
                        snapshot.currentMessage()));
            }

            @Override
            public void onError(String message, Throwable throwable) {
                failed.set(true);
                System.err.println("[error] " + message);
            }
        };
    }

    private static JobConfig buildConfig(CliOptions options) throws Exception {
        UiSettings settings = loadSettings(options.configPath());
        boolean allowNonStandardResponses = options.flag("strict-response")
                ? false
                : booleanValue(options, "compatible-response", settings.allowNonStandardResponses);

        List<ProviderConfig> providers = new ArrayList<>();
        for (String line : options.providers()) {
            providers.add(ProviderConfig.parseSingle(line));
        }
        if (providers.isEmpty() && settings.providers != null && !settings.providers.isEmpty()) {
            for (UiProviderItem item : settings.providers) {
                providers.add(new ProviderConfig(
                        require(item.name, "provider name"),
                        PathOrUri.requiredUri(item.url, "provider url"),
                        require(item.apiKey, "provider apiKey"),
                        require(item.model, "provider model"),
                        item.concurrency,
                        item.quota,
                        item.windowMinutes));
            }
        } else if (providers.isEmpty() && settings.providersText != null && !settings.providersText.isBlank()) {
            providers.addAll(ProviderConfig.parseLines(settings.providersText));
        }

        Path input = Path.of(value(options, "input", settings.inputPath, "input"));
        Path output = Path.of(value(options, "output", settings.outputPath, "output"));
        OutputFormat format = OutputFormat.fromText(value(options, "format", settings.outputFormat, null));
        Charset encoding = Charset.forName(value(options, "encoding", settings.encoding, null));

        return JobConfig.builder()
                .providers(providers)
                .customPrompt(value(options, "prompt", settings.promptText, null))
                .inputPath(input)
                .outputPath(output)
                .checkpointPath(Path.of(value(options, "checkpoint", output + ".checkpoint.json", null)))
                .outputFormat(format)
                .sourceLanguage(value(options, "source-lang", settings.sourceLanguage, null))
                .targetLanguage(value(options, "target-lang", settings.targetLanguage, null))
                .encoding(encoding)
                .batchSize(intValue(options, "batch-size", settings.batchSize))
                .parallelism(intValue(options, "parallelism", settings.parallelism))
                .maxRetries(intValue(options, "retries", settings.maxRetries))
                .requestTimeout(Duration.ofSeconds(intValue(options, "timeout-sec", settings.timeoutSec)))
                .initialBackoff(Duration.ofMillis(1200))
                .maxBackoff(Duration.ofSeconds(20))
                .resumeFromCheckpoint(booleanValue(options, "resume", settings.resumeFromCheckpoint))
                .clearCheckpointOnSuccess(booleanValue(options, "clear-checkpoint", settings.clearCheckpointOnSuccess))
                .allowNonStandardResponses(allowNonStandardResponses)
                .autoContinueTruncatedOutput(booleanValue(
                        options,
                        "auto-continue-truncated",
                        settings.autoContinueTruncatedOutput))
                .useSystemPromptOverride(settings.useSystemPromptOverride)
                .systemPromptTemplate(settings.systemPromptTemplate)
                .preRequestHook(toScriptHookConfig(settings.preRequestHook))
                .postResponseHook(toScriptHookConfig(settings.postResponseHook))
                .postParsedHook(toScriptHookConfig(settings.postParsedHook))
                .debugMode(booleanValue(options, "debug", settings.debugMode))
                .build();
    }

    private static UiSettings loadSettings(Path configPath) throws Exception {
        if (configPath == null) {
            return new UiConfigStore().load().orElseGet(UiSettings::new);
        }
        Path normalized = configPath.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            return new UiConfigStore().load(normalized).orElseGet(UiSettings::new);
        }
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return mapper.readValue(normalized.toFile(), UiSettings.class);
    }

    private static ScriptHookConfig toScriptHookConfig(UiScriptHookItem item) {
        if (item == null) {
            return ScriptHookConfig.disabled();
        }
        return new ScriptHookConfig(
                item.enabled,
                ScriptLanguage.fromText(item.language),
                item.scriptPath == null ? "" : item.scriptPath.trim(),
                item.timeoutSec <= 0 ? 30 : item.timeoutSec
        );
    }

    private static String value(CliOptions options, String key, String fallback, String requiredName) {
        String value = options.value(key).orElse(fallback == null ? "" : fallback);
        if ((value == null || value.isBlank()) && requiredName != null) {
            throw new IllegalArgumentException("--" + requiredName + " is required");
        }
        return value == null ? "" : value.trim();
    }

    private static int intValue(CliOptions options, String key, int fallback) {
        return options.value(key).map(Integer::parseInt).orElse(fallback);
    }

    private static boolean booleanValue(CliOptions options, String key, boolean fallback) {
        if (options.flag(key)) {
            return true;
        }
        return options.value(key).map(Boolean::parseBoolean).orElse(fallback);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static void printUsage() {
        System.out.println("""
                Usage:
                  java -jar target/wordbook-gen-1.0.0-shaded.jar --cli [--config <ui-settings.json|config-dir>] --input words.txt --output wordbook.json

                Common options:
                  --provider "name|url|apiKey|model|concurrency|quota|windowMinutes"  Repeatable. Overrides providers from config.
                  --format JSON|CSV
                  --checkpoint wordbook.json.checkpoint.json
                  --source-lang English
                  --target-lang Chinese
                  --encoding UTF-8
                  --batch-size 12
                  --parallelism 4
                  --retries 5
                  --timeout-sec 180
                  --resume true|false
                  --clear-checkpoint true|false
                  --compatible-response true|false
                  --strict-response
                  --auto-continue-truncated
                  --debug
                """);
    }

    private record CliOptions(Map<String, String> values, List<String> providers, boolean help) {
        static CliOptions parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            List<String> providers = new ArrayList<>();
            boolean help = false;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
                    continue;
                }
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                String key = arg.substring(2);
                String value = "true";
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i];
                }
                if ("provider".equals(key)) {
                    providers.add(value);
                } else if ("config".equals(key)) {
                    values.put(key, value);
                } else {
                    values.put(key, value);
                }
            }
            return new CliOptions(values, providers, help);
        }

        Optional<String> value(String key) {
            return Optional.ofNullable(values.get(key));
        }

        boolean flag(String key) {
            return "true".equalsIgnoreCase(values.get(key));
        }

        Path configPath() {
            return value("config").map(Path::of).orElse(null);
        }
    }

    private static final class PathOrUri {
        private PathOrUri() {
        }

        static java.net.URI requiredUri(String value, String field) {
            return java.net.URI.create(require(value, field));
        }
    }
}
