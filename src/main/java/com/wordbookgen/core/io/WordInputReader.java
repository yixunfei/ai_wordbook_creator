package com.wordbookgen.core.io;

import com.wordbookgen.core.model.JobConfig;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 单词输入读取器。
 */
public class WordInputReader {

    public List<String> readWords(JobConfig config) throws IOException {
        Path input = config.inputPath();
        Charset charset = config.encoding();

        List<String> lines = Files.readAllLines(input, charset);
        Map<String, String> unique = new LinkedHashMap<>();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String word = line.trim();
            if (!word.isEmpty()) {
                unique.putIfAbsent(normalize(word), word);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private String normalize(String word) {
        if (word == null) {
            return "";
        }
        return word.trim().toLowerCase(Locale.ROOT);
    }
}
