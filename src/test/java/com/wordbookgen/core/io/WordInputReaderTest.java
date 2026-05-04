package com.wordbookgen.core.io;

import com.wordbookgen.core.model.JobConfig;
import com.wordbookgen.core.model.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WordInputReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void deduplicatesWordsCaseInsensitivelyAndKeepsFirstSpelling() throws Exception {
        Path input = tempDir.resolve("words.txt");
        Files.writeString(input, "Apple\n apple \nBANANA\nbanana\nC++\n", StandardCharsets.UTF_8);

        JobConfig config = JobConfig.builder()
                .providers(List.of(provider()))
                .inputPath(input)
                .outputPath(tempDir.resolve("out.json"))
                .encoding(StandardCharsets.UTF_8)
                .build();

        List<String> words = new WordInputReader().readWords(config);

        assertEquals(List.of("Apple", "BANANA", "C++"), words);
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
