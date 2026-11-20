package org.roomreservation.platform.internal.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.roomreservation.platform.DailyRoomCodeFacts;
import org.roomreservation.platform.DailyRoomCodeUnavailableException;
import org.roomreservation.platform.LockedDailyRoomCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL adapter. The outer M1 transaction owns the connection and lock lifetime. */
public final class JdbcDailyRoomCodeFacts implements DailyRoomCodeFacts {
    private final JdbcTemplate jdbc;
    private final ZoneId schoolZone;

    public JdbcDailyRoomCodeFacts(JdbcTemplate jdbc, ZoneId schoolZone) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.schoolZone = Objects.requireNonNull(schoolZone);
    }

    @Override
    public LockedDailyRoomCode lockForBookingUseDate(String roomId, Instant bookingStart) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("daily code read requires an active managed transaction");
        }
        Objects.requireNonNull(roomId);
        Objects.requireNonNull(bookingStart);
        LocalDate useDate = bookingStart.atZone(schoolZone).toLocalDate();
        List<CodeRow> facts = jdbc.query("""
                SELECT code_value, revision
                FROM d_daily_room_code
                WHERE room_id = ? AND use_local_date = ?
                FOR SHARE
                """, (rs, row) -> new CodeRow(rs.getLong("revision"),
                rs.getString("code_value")), roomId, useDate);
        CodeRow fact = facts.isEmpty() ? new CodeRow(0, null) : facts.getFirst();
        CodeHandle locked = new CodeHandle(roomId, useDate, fact.revision(), fact.code());
        TransactionSynchronizationManager.registerSynchronization(locked);
        return locked;
    }

    private record CodeRow(long revision, String code) { }

    private static final class CodeHandle implements LockedDailyRoomCode, TransactionSynchronization {
        private final String roomId;
        private final LocalDate useDate;
        private final long revision;
        private final String code;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private CodeHandle(String roomId, LocalDate useDate, long revision, String code) {
            this.roomId = Objects.requireNonNull(roomId);
            this.useDate = Objects.requireNonNull(useDate);
            this.revision = revision;
            this.code = code;
        }

        @Override public String roomId() { return roomId; }
        @Override public LocalDate useDate() { return useDate; }
        @Override public long revision() { return revision; }

        @Override public boolean matches(String submittedCode) {
            if (!active.get() || !TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.isSynchronizationActive()
                    || !TransactionSynchronizationManager.getSynchronizations().contains(this)) {
                throw new IllegalStateException("daily code comparison requires the locking transaction");
            }
            Objects.requireNonNull(submittedCode);
            if (code == null) throw new DailyRoomCodeUnavailableException(roomId, useDate);
            return MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),
                    submittedCode.getBytes(StandardCharsets.UTF_8));
        }

        @Override public void afterCompletion(int status) { active.set(false); }
    }
}
