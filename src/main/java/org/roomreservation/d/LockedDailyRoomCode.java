package org.roomreservation.d;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A D code fact protected by the caller's still-open database transaction. */
public final class LockedDailyRoomCode {
    private final String roomId;
    private final LocalDate useDate;
    private final long revision;
    private final String code;
    private final Lifetime lifetime;

    LockedDailyRoomCode(String roomId, LocalDate useDate, long revision, String code,
                        Lifetime lifetime) {
        this.roomId = Objects.requireNonNull(roomId);
        this.useDate = Objects.requireNonNull(useDate);
        this.revision = revision;
        this.code = code;
        this.lifetime = Objects.requireNonNull(lifetime);
    }

    public String roomId() { return roomId; }
    public LocalDate useDate() { return useDate; }
    public long revision() { return revision; }

    /** Compare only while the row lock is held. Missing facts are never treated as a bad code. */
    public boolean matches(String submittedCode) {
        if (!lifetime.active.get() || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getSynchronizations().contains(lifetime)) {
            throw new IllegalStateException("daily code comparison requires the locking transaction");
        }
        Objects.requireNonNull(submittedCode);
        if (code == null) throw new DailyRoomCodeUnavailableException(roomId, useDate);
        return MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),
                submittedCode.getBytes(StandardCharsets.UTF_8));
    }

    static final class Lifetime implements TransactionSynchronization {
        private final AtomicBoolean active = new AtomicBoolean(true);

        @Override public void afterCompletion(int status) { active.set(false); }
    }
}
