package ai.vigzone.chatgptconnector;

import org.junit.Test;
import static org.junit.Assert.*;

public class ValidationRetryTest {
    @Test public void repeatedCallbacksCannotStartAValidationLoop() {
        ValidationRetry retry = new ValidationRetry();
        assertTrue(retry.begin(100));
        for (int i=0;i<1000;i++) assertFalse(retry.begin(100));
        assertTrue(retry.begin(2100));
    }
    @Test public void aMissingCallbackStillAllowsScheduledRecovery() {
        ValidationRetry retry = new ValidationRetry();
        long now=0;
        for (int i=0;i<6;i++) {
            assertTrue(retry.begin(now));
            long delay=retry.delayMillis();
            assertTrue(delay>=2000 && delay<=30000);
            assertFalse(retry.begin(now+delay-1));
            now+=delay;
        }
        assertFalse(retry.begin(now));
        retry.reset();assertTrue(retry.begin(0));
    }
}
