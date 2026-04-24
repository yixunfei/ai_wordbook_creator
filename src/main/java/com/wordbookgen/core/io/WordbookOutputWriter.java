package com.wordbookgen.core.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.wordbookgen.core.model.DictionaryFields;
import com.wordbookgen.core.model.JobConfig;
import com.wordbookgen.core.model.OutputFormat;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 结果输出器，支持 JSON/CSV。
 */
public class WordbookOutputWriter {

    private final ObjectMapper mapper;

    public WordbookOutputWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(
            JobConfig config,
            List<String> orderedWords,
            Map<String, JsonNode> normalizedWordEntries
    ) throws IOException {
        if (config.outputFormat() == OutputFormat.CSV) {
            writeCsv(config.outputPath(), config.encoding(), orderedWords, normalizedWordEntries);
            return;
        }
        writeJson(config.outputPath(), config.encoding(), orderedWords, normalizedWordEntries);
    }

    private void writeJson(Path outputPath, Charset charset, List<String> words, Map<String, JsonNode> entries)
            throws IOException {
        ensureParent(outputPath);

        ArrayNode arrayNode = mapper.createArrayNode();
        for (String word : words) {
            JsonNode node = entries.get(normalize(word));
            if (node != null) {
                arrayNode.add(node);
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, charset)) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(writer, arrayNode);
        }
    }

    private void writeCsv(Path outputPath, Charset charset, List<String> words, Map<String, JsonNode> entries)
            throws IOException {
        ensureParent(outputPath);

        List<String> headers = new ArrayList<>(DictionaryFields.REQUIRED);

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(headers.toArray(new String[0]))
                .build();

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, charset);
             CSVPrinter printer = new CSVPrinter(writer, format)) {
            for (String word : words) {
                JsonNode node = entries.get(normalize(word));
                if (node == null) {
                    continue;
                }

                List<String> row = new ArrayList<>();
                for (String field : DictionaryFields.REQUIRED) {
                    JsonNode value = node.get(field);
                    row.add(toCell(value));
                }
                printer.printRecord(row);
            }
        }
    }

    private String toCell(JsonNode node) throws IOException {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return mapper.writeValueAsString(node);
    }

    private void ensureParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private String normalize(String word) {
        if (word == null) {
            return "";
        }
        return word.trim().toLowerCase(Locale.ROOT);
    }
}
