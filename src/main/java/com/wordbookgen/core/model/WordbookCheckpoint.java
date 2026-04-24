package com.wordbookgen.core.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 断点续传快照。
 */
public class WordbookCheckpoint {

    private String configFingerprint;
    private String inputPath;
    private String outputPath;
    private int totalWords;
    private int completedBatches;
    private String updatedAt = Instant.now().toString();

    private List<String> completedWords = new ArrayList<>();
    private Map<String, JsonNode> entries = new LinkedHashMap<>();

    public String getConfigFingerprint() {
        return configFingerprint;
    }

    public void setConfigFingerprint(String configFingerprint) {
        this.configFingerprint = configFingerprint;
    }

    public String getInputPath() {
        return inputPath;
    }

    public void setInputPath(String inputPath) {
        this.inputPath = inputPath;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public int getTotalWords() {
        return totalWords;
    }

    public void setTotalWords(int totalWords) {
        this.totalWords = totalWords;
    }

    public int getCompletedBatches() {
        return completedBatches;
    }

    public void setCompletedBatches(int completedBatches) {
        this.completedBatches = completedBatches;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<String> getCompletedWords() {
        return completedWords;
    }

    public void setCompletedWords(List<String> completedWords) {
        this.completedWords = completedWords;
    }

    public Map<String, JsonNode> getEntries() {
        return entries;
    }

    public void setEntries(Map<String, JsonNode> entries) {
        this.entries = entries;
    }
}
