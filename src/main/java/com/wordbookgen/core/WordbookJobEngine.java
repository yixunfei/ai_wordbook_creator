package com.wordbookgen.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wordbookgen.core.checkpoint.CheckpointStore;
import com.wordbookgen.core.io.WordInputReader;
import com.wordbookgen.core.io.WordbookOutputWriter;
import com.wordbookgen.core.model.BatchResult;
import com.wordbookgen.core.model.BatchTask;
import com.wordbookgen.core.model.JobConfig;
import com.wordbookgen.core.model.JobState;
import com.wordbookgen.core.model.ProgressSnapshot;
import com.wordbookgen.core.model.ProviderConfig;
import com.wordbookgen.core.model.WordbookCheckpoint;
import com.wordbookgen.core.provider.ProviderClient;
import com.wordbookgen.core.provider.ProviderExceptions.ProviderException;
import com.wordbookgen.core.provider.ProviderRouter;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 单词本任务引擎。
 * 职责：调度分片、管理 provider、处理重试/限流、更新 checkpoint、输出结果。
 */
public class WordbookJobEngine {

    private static final int MAX_TIMEOUT_SPLIT_DEPTH = 6;

    private final ObjectMapper mapper;
    private final WordInputReader inputReader;
    private final WordbookOutputWriter outputWriter;
    private final CheckpointStore checkpointStore;

    private volatile JobState state = JobState.IDLE;
    private volatile Future<?> runningFuture;
    private volatile PauseController pauseController = new PauseController();

    private final ExecutorService supervisor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wordbook-supervisor");
        t.setDaemon(true);
        return t;
    });

    private JobListener listener;

    public WordbookJobEngine() {
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.inputReader = new WordInputReader();
        this.outputWriter = new WordbookOutputWriter(mapper);
        this.checkpointStore = new CheckpointStore(mapper);
    }

    public synchronized void start(JobConfig config, JobListener listener) {
        if (config == null) {
            throw new IllegalArgumentException("Job config is required.");
        }
        if (state == JobState.RUNNING || state == JobState.PAUSED || state == JobState.STOPPING) {
            throw new IllegalStateException("Job is already active.");
        }
        this.listener = listener;
        this.pauseController = new PauseController();

        setState(JobState.RUNNING);
        runningFuture = supervisor.submit(() -> runInternal(config));
    }

    /**
     * Runs on the caller thread for CLI usage while sharing the same pause/stop and checkpoint path.
     */
    public void runBlocking(JobConfig config, JobListener listener) {
        synchronized (this) {
            if (config == null) {
                throw new IllegalArgumentException("Job config is required.");
            }
            if (state == JobState.RUNNING || state == JobState.PAUSED || state == JobState.STOPPING) {
                throw new IllegalStateException("Job is already active.");
            }
            this.listener = listener;
            this.pauseController = new PauseController();
            setState(JobState.RUNNING);
        }
        runInternal(config);
    }

    public synchronized void pause() {
        if (state != JobState.RUNNING) {
            return;
        }
        pauseController.pause();
        setState(JobState.PAUSED);
        log("Job paused.");
    }

    public synchronized void resume() {
        if (state != JobState.PAUSED) {
            return;
        }
        pauseController.resume();
        setState(JobState.RUNNING);
        log("Job resumed.");
    }

    public synchronized void stop() {
        if (!(state == JobState.RUNNING || state == JobState.PAUSED || state == JobState.STOPPING)) {
            return;
        }
        setState(JobState.STOPPING);
        pauseController.requestStop();
        log("Stop requested, waiting for workers to finish current round...");
    }

    public JobState state() {
        return state;
    }

    private void runInternal(JobConfig config) {
        try {
            validatePaths(config);
            log("Loading words from: " + config.inputPath().toAbsolutePath());
            log("Runtime config: batchSize=" + config.batchSize()
                    + ", parallelism=" + config.parallelism()
                    + ", timeoutSec=" + config.requestTimeout().toSeconds()
                    + ", debugMode=" + config.debugMode()
                    + ", allowNonStandardResponses=" + config.allowNonStandardResponses()
                    + ", autoContinueTruncatedOutput=" + config.autoContinueTruncatedOutput()
                    + ", providers=" + config.providers().size());
            if (config.debugMode() && (config.batchSize() > 1 || config.parallelism() > 1)) {
                log("Debug tip: set batchSize=1 and parallelism=1 to get deterministic per-word logs.");
            }

            List<String> allWords = inputReader.readWords(config);
            if (allWords.isEmpty()) {
                throw new IllegalStateException("Input word list is empty.");
            }

            Map<String, JsonNode> entries = new HashMap<>();
            Set<String> completedSet = new HashSet<>();
            int completedBatches = 0;

            if (config.resumeFromCheckpoint()) {
                ResumeState resumed = tryLoadCheckpoint(config, allWords.size());
                if (resumed != null) {
                    entries.putAll(resumed.entries());
                    completedSet.addAll(resumed.completedWords());
                    completedBatches = resumed.completedBatches();
                    log("Checkpoint loaded: completed words=" + completedSet.size());
                }
            }

            // 过滤已完成词条，减少重复请求。
            List<String> pendingWords = allWords.stream()
                    .filter(word -> !completedSet.contains(normalize(word)))
                    .toList();

            List<BatchTask> batchTasks = splitToBatches(pendingWords, config.batchSize());
            log("Total words=" + allWords.size() + ", pending=" + pendingWords.size() + ", batch count=" + batchTasks.size());

            notifyProgress(config, allWords.size(), completedSet.size(), completedBatches, "Job started.");

            List<ProviderClient> clients = new ArrayList<>();
            for (ProviderConfig providerConfig : config.providers()) {
                clients.add(new ProviderClient(providerConfig, mapper));
            }
            ProviderRouter router = new ProviderRouter(clients);

            completedBatches = processBatches(
                    config,
                    allWords,
                    entries,
                    completedSet,
                    completedBatches,
                    batchTasks,
                    router);

            if (pauseController.isStopRequested()) {
                saveCheckpoint(config, allWords.size(), entries, completedSet, completedBatches);
                setState(JobState.STOPPED);
                notifyProgress(config, allWords.size(), completedSet.size(), completedBatches, "Stopped by user.");
                return;
            }

            outputWriter.write(config, allWords, entries);
            log("Output written: " + config.outputPath().toAbsolutePath());

            if (config.clearCheckpointOnSuccess()) {
                checkpointStore.deleteIfExists(config.checkpointPath());
                log("Checkpoint cleared after success.");
            }

            setState(JobState.COMPLETED);
            notifyProgress(config, allWords.size(), completedSet.size(), completedBatches, "Completed.");
        } catch (Throwable ex) {
            String detail = detailedMessage(ex);
            setState(JobState.FAILED);
            if (listener != null) {
                listener.onError(detail, ex);
            }
            log("Job failed: " + detail);
            log("Job failed stacktrace:\n" + fullStackTrace(ex));
        }
    }

    private int processBatches(
            JobConfig config,
            List<String> allWords,
            Map<String, JsonNode> entries,
            Set<String> completedSet,
            int completedBatches,
            List<BatchTask> tasks,
            ProviderRouter router
    ) throws Exception {
        if (tasks.isEmpty()) {
            return completedBatches;
        }

        ExecutorService workerPool = Executors.newFixedThreadPool(config.parallelism(), r -> {
            Thread t = new Thread(r, "wordbook-worker");
            t.setDaemon(true);
            return t;
        });

        try {
            CompletionService<BatchResult> completion = new ExecutorCompletionService<>(workerPool);

            Iterator<BatchTask> iterator = tasks.iterator();
            int running = 0;

            while (running > 0 || iterator.hasNext()) {
                if (!pauseController.isStopRequested()) {
                    pauseController.awaitIfPaused();
                }

                // 未停止时继续按并发上限提交分片。
                while (!pauseController.isStopRequested()
                        && !pauseController.isPaused()
                        && running < config.parallelism()
                        && iterator.hasNext()) {
                    BatchTask task = iterator.next();
                    completion.submit(() -> executeBatch(task, config, router));
                    running++;
                }

                Future<BatchResult> done = completion.poll(500L, TimeUnit.MILLISECONDS);
                if (done == null) {
                    if (pauseController.isStopRequested() && running == 0) {
                        break;
                    }
                    continue;
                }
                running--;

                BatchResult result;
                try {
                    result = done.get();
                } catch (ExecutionException ex) {
                    Throwable root = ex.getCause() == null ? ex : ex.getCause();
                    if (pauseController.isStopRequested()) {
                        log("Batch canceled after stop request: " + detailedMessage(root));
                        continue;
                    }
                    log("Batch execution failed root: " + detailedMessage(root));
                    log("Batch execution stacktrace:\n" + fullStackTrace(root));
                    throw new RuntimeException("Batch execution failed: " + detailedMessage(root), root);
                }

                // 仅在协调线程合并，避免并发写 map。
                for (Map.Entry<String, JsonNode> entry : result.entries().entrySet()) {
                    entries.put(entry.getKey(), entry.getValue());
                    completedSet.add(entry.getKey());
                }
                completedBatches++;

                saveCheckpoint(config, allWords.size(), entries, completedSet, completedBatches);
                notifyProgress(config, allWords.size(), completedSet.size(), completedBatches,
                        "batch=" + result.batchIndex() + " provider=" + result.providerName() + " finished");
            }
            return completedBatches;
        } finally {
            workerPool.shutdownNow();
        }
    }

    private BatchResult executeBatch(BatchTask task, JobConfig config, ProviderRouter router) throws Exception {
        return executeBatch(task, config, router, 0);
    }

    private BatchResult executeBatch(BatchTask task, JobConfig config, ProviderRouter router, int splitDepth) throws Exception {
        List<ProviderClient> ordered = router.orderedClientsForNextBatch();
        List<String> errors = new ArrayList<>();
        boolean allAdaptiveSplitCandidates = true;
        boolean sawTimeoutRelated = false;

        for (ProviderClient client : ordered) {
            try {
                Map<String, JsonNode> data = client.translateBatch(task, config, pauseController, this::log);
                return new BatchResult(task.index(), client.name(), data);
            } catch (ProviderException ex) {
                if (pauseController.isStopRequested()) {
                    throw ex;
                }
                boolean timeoutRelated = isTimeoutRelated(ex);
                sawTimeoutRelated = sawTimeoutRelated || timeoutRelated;
                if (!isAdaptiveSplitCandidate(ex)) {
                    allAdaptiveSplitCandidates = false;
                }
                String detail = detailedMessage(ex);
                errors.add("provider=" + client.name() + ", reason=" + detail);
                log("batch=" + task.index() + " provider failed: " + detail);
                log("batch=" + task.index() + " provider stacktrace:\n" + fullStackTrace(ex));
            }
        }

        if (allAdaptiveSplitCandidates && canSplitBatch(task, splitDepth)) {
            int total = task.words().size();
            int mid = total / 2;
            List<String> leftWords = List.copyOf(task.words().subList(0, mid));
            List<String> rightWords = List.copyOf(task.words().subList(mid, total));
            String splitReason = sawTimeoutRelated ? "timeout" : "incomplete model output";

            log("batch=" + task.index() + " adaptive split triggered by " + splitReason + ", "
                    + total + " -> "
                    + leftWords.size() + " + " + rightWords.size());

            BatchResult left = executeBatch(new BatchTask(task.index(), leftWords), config, router, splitDepth + 1);
            BatchResult right = executeBatch(new BatchTask(task.index(), rightWords), config, router, splitDepth + 1);

            Map<String, JsonNode> merged = new HashMap<>(left.entries());
            merged.putAll(right.entries());
            return new BatchResult(task.index(), "adaptive-split", merged);
        }

        if (sawTimeoutRelated) {
            throw new IllegalStateException("All providers timed out for batch=" + task.index()
                    + ", words=" + task.words().size() + ", details=" + errors
                    + ". Try reducing batch size or increasing request timeout.");
        }
        if (allAdaptiveSplitCandidates) {
            throw new IllegalStateException("All providers returned incomplete model output for batch=" + task.index()
                    + ", words=" + task.words().size() + ", details=" + errors
                    + ". Try reducing batch size.");
        }
        throw new IllegalStateException("All providers failed for batch=" + task.index() + ", details=" + errors);
    }

    private boolean canSplitBatch(BatchTask task, int splitDepth) {
        if (task == null || task.words() == null) {
            return false;
        }
        return task.words().size() > 1 && splitDepth < MAX_TIMEOUT_SPLIT_DEPTH;
    }

    private boolean isAdaptiveSplitCandidate(Throwable throwable) {
        if (isTimeoutRelated(throwable)) {
            return true;
        }
        Throwable cursor = throwable;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("missing words from response")
                        || lower.contains("response missing choices")
                        || lower.contains("response missing message.content text")
                        || lower.contains("model output is not parseable json")
                        || lower.contains("model output was truncated")
                        || lower.contains("finish_reason=length")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private boolean isTimeoutRelated(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof HttpTimeoutException || cursor instanceof SocketTimeoutException) {
                return true;
            }
            String message = cursor.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("timeout") || lower.contains("timed out")) {
                    return true;
                }
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private ResumeState tryLoadCheckpoint(JobConfig config, int totalWords) {
        Optional<WordbookCheckpoint> optional;
        try {
            optional = checkpointStore.load(config.checkpointPath());
        } catch (IOException ex) {
            log("Checkpoint load failed, ignored: " + detailedMessage(ex));
            return null;
        }
        if (optional.isEmpty()) {
            return null;
        }

        WordbookCheckpoint cp = optional.get();
        if (cp.getConfigFingerprint() == null || !cp.getConfigFingerprint().equals(config.fingerprint())) {
            log("Checkpoint found but fingerprint mismatch, ignored.");
            return null;
        }

        Set<String> completed = new HashSet<>();
        if (cp.getCompletedWords() != null) {
            for (String word : cp.getCompletedWords()) {
                completed.add(normalize(word));
            }
        }

        Map<String, JsonNode> entries = cp.getEntries() == null ? new HashMap<>() : new HashMap<>(cp.getEntries());
        completed.removeIf(word -> !entries.containsKey(word));

        log("Checkpoint updatedAt=" + cp.getUpdatedAt() + ", totalWords=" + cp.getTotalWords() + ", restored=" + completed.size());

        if (cp.getTotalWords() > 0 && cp.getTotalWords() != totalWords) {
            log("Warning: input word count changed since checkpoint (old=" + cp.getTotalWords() + ", now=" + totalWords + ").");
        }

        return new ResumeState(entries, completed, Math.max(cp.getCompletedBatches(), 0));
    }

    private void saveCheckpoint(
            JobConfig config,
            int totalWords,
            Map<String, JsonNode> entries,
            Set<String> completedWords,
            int completedBatches
    ) throws IOException {
        WordbookCheckpoint cp = new WordbookCheckpoint();
        cp.setConfigFingerprint(config.fingerprint());
        cp.setInputPath(config.inputPath().toAbsolutePath().toString());
        cp.setOutputPath(config.outputPath().toAbsolutePath().toString());
        cp.setTotalWords(totalWords);
        cp.setCompletedBatches(completedBatches);
        cp.setUpdatedAt(Instant.now().toString());
        cp.setCompletedWords(new ArrayList<>(completedWords));
        cp.setEntries(new HashMap<>(entries));

        checkpointStore.save(config.checkpointPath(), cp);
    }

    private void validatePaths(JobConfig config) throws IOException {
        Path inputPath = config.inputPath().toAbsolutePath().normalize();
        Path outputPath = config.outputPath().toAbsolutePath().normalize();
        Path checkpointPath = config.checkpointPath().toAbsolutePath().normalize();

        if (!Files.exists(inputPath)) {
            throw new IllegalArgumentException("Input file not found: " + config.inputPath());
        }
        if (Files.isDirectory(inputPath)) {
            throw new IllegalArgumentException("Input path is a directory: " + config.inputPath());
        }

        if (inputPath.equals(outputPath)) {
            throw new IllegalArgumentException("Input and output path cannot be the same file.");
        }
        if (inputPath.equals(checkpointPath)) {
            throw new IllegalArgumentException("Input and checkpoint path cannot be the same file.");
        }
        if (outputPath.equals(checkpointPath)) {
            throw new IllegalArgumentException("Output and checkpoint path cannot be the same file.");
        }

        if (Files.exists(outputPath) && Files.isDirectory(outputPath)) {
            throw new IllegalArgumentException("Output path is a directory: " + outputPath);
        }
        if (Files.exists(checkpointPath) && Files.isDirectory(checkpointPath)) {
            throw new IllegalArgumentException("Checkpoint path is a directory: " + checkpointPath);
        }

        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        if (checkpointPath.getParent() != null) {
            Files.createDirectories(checkpointPath.getParent());
        }
    }

    private List<BatchTask> splitToBatches(List<String> words, int batchSize) {
        List<BatchTask> tasks = new ArrayList<>();
        if (words.isEmpty()) {
            return tasks;
        }

        int index = 0;
        for (int i = 0; i < words.size(); i += batchSize) {
            int end = Math.min(words.size(), i + batchSize);
            tasks.add(new BatchTask(index++, words.subList(i, end)));
        }
        return tasks;
    }

    private void notifyProgress(
            JobConfig config,
            int totalWords,
            int completedWords,
            int completedBatches,
            String message
    ) {
        if (listener == null) {
            return;
        }
        int pendingWords = Math.max(0, totalWords - completedWords);
        listener.onProgress(new ProgressSnapshot(
                state,
                totalWords,
                completedWords,
                pendingWords,
                completedBatches,
                message));
    }

    private void log(String message) {
        if (listener != null) {
            String line = String.format(Locale.ROOT, "[%s] %s", Instant.now(), message);
            listener.onLog(line);
        }
    }

    private void setState(JobState newState) {
        this.state = newState;
        if (listener != null) {
            listener.onStateChanged(newState);
        }
    }

    private String detailedMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor != null && depth < 10) {
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

    private String fullStackTrace(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private String normalize(String word) {
        if (word == null) {
            return "";
        }
        return word.trim().toLowerCase(Locale.ROOT);
    }

    private record ResumeState(
            Map<String, JsonNode> entries,
            Set<String> completedWords,
            int completedBatches
    ) {
    }
}
