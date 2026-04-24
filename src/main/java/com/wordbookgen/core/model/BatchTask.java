package com.wordbookgen.core.model;

import java.util.List;

/**
 * 单个分片任务。
 */
public record BatchTask(int index, List<String> words) {
}
