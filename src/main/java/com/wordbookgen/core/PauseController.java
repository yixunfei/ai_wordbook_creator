package com.wordbookgen.core;

/**
 * 控制暂停/继续/停止的轻量并发门闩。
 */
public class PauseController {

    private final Object lock = new Object();
    private volatile boolean paused = false;
    private volatile boolean stopRequested = false;

    public void pause() {
        paused = true;
    }

    public void resume() {
        synchronized (lock) {
            paused = false;
            lock.notifyAll();
        }
    }

    public void requestStop() {
        synchronized (lock) {
            stopRequested = true;
            paused = false;
            lock.notifyAll();
        }
    }

    /**
     * 在关键步骤前调用，确保暂停时不会继续推进流程。
     */
    public void awaitIfPaused() throws InterruptedException {
        synchronized (lock) {
            while (paused && !stopRequested) {
                lock.wait(300L);
            }
        }
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    /**
     * 支持被暂停/停止打断的 sleep。
     */
    public void sleepInterruptibly(long millis) throws InterruptedException {
        long remaining = millis;
        long chunk = 300L;
        while (remaining > 0) {
            if (stopRequested) {
                return;
            }
            awaitIfPaused();
            long now = Math.min(chunk, remaining);
            Thread.sleep(now);
            remaining -= now;
        }
    }
}
