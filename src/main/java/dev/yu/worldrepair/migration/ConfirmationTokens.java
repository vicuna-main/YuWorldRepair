package dev.yu.worldrepair.migration;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class ConfirmationTokens {
    public enum Action {
        QUARANTINE,
        RESTORE
    }

    private static final int MAX_ACTIVE_TOKENS = 64;
    private static final long TTL_NANOS = Duration.ofMinutes(5).toNanos();

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Grant> grants = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public ConfirmationTokens() {
        this(System::nanoTime);
    }

    ConfirmationTokens(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized String issue(String jobId, Action action) {
        long now = clock.getAsLong();
        purgeExpired(now);
        if (grants.size() >= MAX_ACTIVE_TOKENS) {
            Iterator<String> iterator = grants.keySet().iterator();
            if (iterator.hasNext()) {
                grants.remove(iterator.next());
            }
        }
        byte[] bytes = new byte[12];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        grants.put(token, new Grant(jobId, action, now + TTL_NANOS));
        return token;
    }

    public boolean consume(String token, String jobId, Action action) {
        Grant grant = grants.remove(token);
        if (grant == null) {
            return false;
        }
        long now = clock.getAsLong();
        return now <= grant.expiresAtNanos
                && grant.jobId.equals(jobId)
                && grant.action == action;
    }

    private void purgeExpired(long now) {
        for (Map.Entry<String, Grant> entry : grants.entrySet()) {
            if (now > entry.getValue().expiresAtNanos) {
                grants.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private record Grant(String jobId, Action action, long expiresAtNanos) {
    }
}
