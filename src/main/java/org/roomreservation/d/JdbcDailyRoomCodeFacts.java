package org.roomreservation.d;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
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
        LockedDailyRoomCode.Lifetime lifetime = new LockedDailyRoomCode.Lifetime();
        CodeRow fact = facts.isEmpty() ? new CodeRow(0, null) : facts.getFirst();
        LockedDailyRoomCode locked = new LockedDailyRoomCode(roomId, useDate,
                fact.revision(), fact.code(), lifetime);
        TransactionSynchronizationManager.registerSynchronization(lifetime);
        return locked;
    }

    private record CodeRow(long revision, String code) { }
}
