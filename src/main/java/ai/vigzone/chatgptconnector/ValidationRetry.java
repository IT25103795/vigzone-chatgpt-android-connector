package ai.vigzone.chatgptconnector;

/** Bounds trust checks, including callbacks that fail immediately or never arrive. */
final class ValidationRetry {
    private int attempts;
    private long nextAttempt;
    boolean begin(long now) {
        if (attempts >= 6 || now < nextAttempt) return false;
        attempts++;
        nextAttempt = now + delayMillis();
        return true;
    }
    long delayMillis() { return Math.min(30000L, 1000L << Math.min(attempts, 5)); }
    void reset() { attempts = 0; nextAttempt = 0; }
}
