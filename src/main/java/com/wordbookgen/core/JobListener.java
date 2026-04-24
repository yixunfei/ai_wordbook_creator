package com.wordbookgen.core;

import com.wordbookgen.core.model.JobState;
import com.wordbookgen.core.model.ProgressSnapshot;

/**
 * 引擎回调接口。
 * UI 层通过此接口接收日志、状态和进度。
 */
public interface JobListener {

    void onLog(String message);

    void onStateChanged(JobState state);

    void onProgress(ProgressSnapshot snapshot);

    void onError(String message, Throwable throwable);
}
