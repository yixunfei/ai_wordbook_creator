package com.wordbookgen.core.io;

import com.wordbookgen.core.model.JobConfig;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 单词输入读取器。
 */
public class WordInputReader {

    public List<String> readWords(JobConfig config) throws IOException {
        Path input = config.inputPath();
        Charset charset = config.encoding();

        List<String> lines = Files.readAllLines(input, charset);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            String word = line.trim();
            if (!word.isEmpty()) {
                unique.add(word);
            }
        }
        return new ArrayList<>(unique);
    }
}
