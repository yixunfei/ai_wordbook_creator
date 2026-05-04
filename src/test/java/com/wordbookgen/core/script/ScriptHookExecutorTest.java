package com.wordbookgen.core.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wordbookgen.core.PauseController;
import com.wordbookgen.core.model.JobConfig;
import com.wordbookgen.core.model.ProviderConfig;
import com.wordbookgen.core.model.ScriptHookConfig;
import com.wordbookgen.core.model.ScriptLanguage;
import com.wordbookgen.core.script.ScriptHookExecutor.HookPromptResult;
import com.wordbookgen.core.script.ScriptHookExecutor.ScriptHookException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptHookExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsHookStdoutThatExceedsLimit() throws Exception {
        Path script = tempDir.resolve("TooMuchOutput.java");
        Files.writeString(script, """
                public class TooMuchOutput {
                    public static void main(String[] args) throws Exception {
                        System.in.readAllBytes();
                        System.out.print("{\\"systemPrompt\\":\\"");
                        for (int i = 0; i < 1100000; i++) {
                            System.out.print("x");
                        }
                        System.out.print("\\"}");
                    }
                }
                """, StandardCharsets.UTF_8);

        JobConfig config = JobConfig.builder()
                .providers(List.of(provider()))
                .inputPath(tempDir.resolve("words.txt"))
                .outputPath(tempDir.resolve("out.json"))
                .preRequestHook(new ScriptHookConfig(true, ScriptLanguage.JAVA, script.toString(), 10))
                .build();

        ScriptHookExecutor executor = new ScriptHookExecutor(new ObjectMapper());

        ScriptHookException ex = assertThrows(
                ScriptHookException.class,
                () -> executor.applyPreRequestHook(
                        config,
                        provider(),
                        0,
                        List.of("word"),
                        "system",
                        "user",
                        new PauseController(),
                        null));

        assertTrue(ex.getMessage().contains("stdout exceeded"));
    }

    @Test
    void doesNotStartHookWhenStopAlreadyRequested() throws Exception {
        Path script = tempDir.resolve("NoOp.java");
        Files.writeString(script, """
                public class NoOp {
                    public static void main(String[] args) {}
                }
                """, StandardCharsets.UTF_8);

        JobConfig config = JobConfig.builder()
                .providers(List.of(provider()))
                .inputPath(tempDir.resolve("words.txt"))
                .outputPath(tempDir.resolve("out.json"))
                .preRequestHook(new ScriptHookConfig(true, ScriptLanguage.JAVA, script.toString(), 10))
                .build();
        PauseController pauseController = new PauseController();
        pauseController.requestStop();

        ScriptHookExecutor executor = new ScriptHookExecutor(new ObjectMapper());

        ScriptHookException ex = assertThrows(
                ScriptHookException.class,
                () -> {
                    HookPromptResult ignored = executor.applyPreRequestHook(
                            config,
                            provider(),
                            0,
                            List.of("word"),
                            "system",
                            "user",
                            pauseController,
                            null);
                });

        assertTrue(ex.getMessage().contains("job is stopping"));
    }

    private ProviderConfig provider() {
        return new ProviderConfig(
                "test",
                URI.create("https://example.com/v1/chat/completions"),
                "key",
                "model",
                1,
                100,
                60);
    }
}
