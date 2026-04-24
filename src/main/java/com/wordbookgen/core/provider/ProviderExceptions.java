package com.wordbookgen.core.provider;

/**
 * Provider 错误类型定义。
 */
public final class ProviderExceptions {

    private ProviderExceptions() {
    }

    public static class ProviderException extends Exception {
        public ProviderException(String message) {
            super(message);
        }

        public ProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class RetryableProviderException extends ProviderException {
        public RetryableProviderException(String message) {
            super(message);
        }

        public RetryableProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class NonRetryableProviderException extends ProviderException {
        public NonRetryableProviderException(String message) {
            super(message);
        }

        public NonRetryableProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class RateLimitException extends RetryableProviderException {
        private final long retryAfterMs;

        public RateLimitException(String message, long retryAfterMs) {
            super(message);
            this.retryAfterMs = retryAfterMs;
        }

        public long getRetryAfterMs() {
            return retryAfterMs;
        }
    }
}
