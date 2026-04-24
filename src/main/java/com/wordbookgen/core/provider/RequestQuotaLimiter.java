package com.wordbookgen.core.provider;

import com.wordbookgen.core.PauseController;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * provider 本地配额控制：滑动窗口请求上限。
 */
public class RequestQuotaLimiter {

    private final int limit;
    private final long windowMillis;
    private final Deque<Long> requestTimes = new ArrayDeque<>();

    public RequestQuotaLimiter(int limit, Duration windowDuration) {
        this.limit = Math.max(1, limit);
        long rawWindow = windowDuration == null ? 0L : windowDuration.toMillis();
        this.windowMillis = Math.max(1_000L, rawWindow);
    }

    public void acquire(String providerName, PauseController pauseController, Consumer<String> log) throws InterruptedException {
        while (true) {
            long waitMillis;
            synchronized (this) {
                long now = System.currentTimeMillis();
                purgeExpired(now);

                if (requestTimes.size() < limit) {
                    requestTimes.addLast(now);
                    return;
                }

                long oldest = requestTimes.peekFirst() == null ? now : requestTimes.peekFirst();
                waitMillis = Math.max(500L, windowMillis - (now - oldest) + 50L);
            }

            if (log != null) {
                log.accept(String.format(Locale.ROOT,
                        "Provider %s local quota reached (%d req / %d min), wait %d ms",
                        providerName,
                        limit,
                        windowMillis / 60000,
                        waitMillis));
            }
            pauseController.sleepInterruptibly(waitMillis);
        }
    }

    private void purgeExpired(long now) {
        while (!requestTimes.isEmpty()) {
            long oldest = requestTimes.peekFirst();
            if (now - oldest >= windowMillis) {
                requestTimes.pollFirst();
                continue;
            }
            break;
        }
    }
}
