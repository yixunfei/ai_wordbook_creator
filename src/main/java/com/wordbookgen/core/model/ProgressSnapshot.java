package com.wordbookgen.core.model;

/**
 * 进度快照，供 UI 实时显示。
 */
public record ProgressSnapshot(
        JobState state,
        int totalWords,
        int completedWords,
        int pendingWords,
        int completedBatches,
        String currentMessage
) {
}
