package dev.yu.worldrepair.migration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmationTokensTest {
    @Test
    void tokenIsBoundToJobActionAndSingleUse() {
        AtomicLong clock = new AtomicLong();
        ConfirmationTokens tokens = new ConfirmationTokens(clock::get);
        String token = tokens.issue("job-a", ConfirmationTokens.Action.QUARANTINE);

        assertFalse(tokens.consume(token, "job-b", ConfirmationTokens.Action.QUARANTINE));
        assertFalse(tokens.consume(token, "job-a", ConfirmationTokens.Action.QUARANTINE));

        String valid = tokens.issue("job-a", ConfirmationTokens.Action.RESTORE);
        assertTrue(tokens.consume(valid, "job-a", ConfirmationTokens.Action.RESTORE));
        assertFalse(tokens.consume(valid, "job-a", ConfirmationTokens.Action.RESTORE));
    }

    @Test
    void tokenExpiresWithoutSleeping() {
        AtomicLong clock = new AtomicLong();
        ConfirmationTokens tokens = new ConfirmationTokens(clock::get);
        String token = tokens.issue("job", ConfirmationTokens.Action.QUARANTINE);
        clock.set(Duration.ofMinutes(6).toNanos());

        assertFalse(tokens.consume(token, "job", ConfirmationTokens.Action.QUARANTINE));
    }
}
