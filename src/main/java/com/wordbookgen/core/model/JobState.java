package com.wordbookgen.core.model;

/**
 * 任务生命周期状态。
 */
public enum JobState {
    IDLE,
    RUNNING,
    PAUSED,
    STOPPING,
    STOPPED,
    COMPLETED,
    FAILED
}
