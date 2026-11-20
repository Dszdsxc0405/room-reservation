package org.roomreservation.m1;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** M1 persistence for B's lifecycle rules. The D adapters are supplied at composition time. */
final class JdbcBookingLifecycleStore implements BookingLifecycleStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BookingCoordinationLocks locks;
    private final CodeFactLocker codeFacts;
    private final NoShowNoticeWriter notices;

    JdbcBookingLifecycleStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                             BookingCoordinationLocks locks, CodeFactLocker codeFacts,
                             NoShowNoticeWriter notices) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.locks = Objects.requireNonNull(locks);
        this.codeFacts = Objects.requireNonNull(codeFacts);
        this.notices = Objects.requireNonNull(notices);
    }

    @Override
    public <T> T withLockedBooking(String bookingId, LifecycleAction action,
                                   Function<LockedBooking, T> work) {
        Objects.requireNonNull(bookingId);
        Objects.requireNonNull(action);
        Objects.requireNonNull(work);
        // PROPAGATION_REQUIRED joins a caller's cross-module transaction. Locks stay held
        // until that outer transaction commits, including D's prelocked code fact.
        return transactions.execute(status -> {
            Location location = jdbc.query("SELECT room_id, starts_at, use_local_date FROM m1_booking "
                            + "WHERE booking_id = ?", rs -> rs.next()
                            ? new Location(rs.getString(1), rs.getTimestamp(2).toInstant(),
                                    rs.getObject(3, LocalDate.class)) : null, bookingId);
            if (location == null) return applyWhileLocked(work, new JdbcLockedBooking(null, null));

            locks.roomShared(location.roomId());
            LockedCodeFact code = action == LifecycleAction.CHECK_IN
                    ? codeFacts.lockForBookingUseDate(location.roomId(), location.start()) : null;
            BookingRow row = jdbc.query("SELECT booking_id, student_id, room_id, starts_at, "
                            + "use_local_date, status, checked_in_at FROM m1_booking WHERE booking_id = ? "
                            + "FOR UPDATE", rs -> rs.next() ? mapRow(rs) : null, bookingId);
            if (row == null || !location.equals(new Location(row.booking().roomId(),
                    row.booking().start(), row.useDate()))) {
                throw new IllegalStateException("M1 booking location changed while acquiring locks");
            }
            return applyWhileLocked(work, new JdbcLockedBooking(row.booking(), code));
        });
    }

    private static <T> T applyWhileLocked(Function<LockedBooking, T> work, JdbcLockedBooking locked) {
        try {
            return work.apply(locked);
        } finally {
            locked.expire();
        }
    }

    @Override
    public List<String> findPotentialNoShows(Instant at, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        Instant latestDueStart = at.truncatedTo(ChronoUnit.SECONDS).minusSeconds(901);
        return jdbc.query("SELECT booking_id FROM m1_booking WHERE status = 'ACTIVE' "
                        + "AND checked_in_at IS NULL AND starts_at <= ? "
                        + "ORDER BY starts_at, booking_id LIMIT ?",
                (rs, rowNum) -> rs.getString(1), java.sql.Timestamp.from(latestDueStart), limit);
    }

    private static BookingRow mapRow(ResultSet rs) throws SQLException {
        return new BookingRow(new Booking(rs.getString("booking_id"), rs.getString("student_id"),
                rs.getString("room_id"), rs.getTimestamp("starts_at").toInstant(),
                "CANCELLED".equals(rs.getString("status")), rs.getTimestamp("checked_in_at") != null),
                rs.getObject("use_local_date", LocalDate.class));
    }

    private final class JdbcLockedBooking implements LockedBooking {
        private final Booking booking;
        private final LockedCodeFact code;
        private final Thread owner = Thread.currentThread();
        private boolean active = true;

        private JdbcLockedBooking(Booking booking, LockedCodeFact code) {
            this.booking = booking;
            this.code = code;
        }

        @Override public Booking booking() {
            requireActive();
            return booking;
        }

        /** B's proposed port addition delegates only to the fact prelocked before this row. */
        public boolean matchesDailyRoomCode(String submittedCode) {
            requireActive();
            if (code == null) throw new IllegalStateException("No D code fact was locked for this action");
            return code.matches(submittedCode);
        }

        @Override public void markCheckedIn(Instant at) {
            requireActive();
            requireBooking();
            int updated = jdbc.update("UPDATE m1_booking SET checked_in_at = ? WHERE booking_id = ? "
                            + "AND status = 'ACTIVE' AND checked_in_at IS NULL",
                    java.sql.Timestamp.from(at), booking.id());
            if (updated != 1) throw new IllegalStateException("Locked M1 booking cannot be checked in");
        }

        @Override public void cancelForNoShowAndRecordViolationAndNotice(Instant at) {
            requireActive();
            requireBooking();
            int updated = jdbc.update("UPDATE m1_booking SET status = 'CANCELLED', cancelled_at = ?, "
                            + "cancel_reason = 'NO_SHOW' WHERE booking_id = ? AND status = 'ACTIVE' "
                            + "AND checked_in_at IS NULL",
                    java.sql.Timestamp.from(at), booking.id());
            if (updated != 1) throw new IllegalStateException("Locked M1 booking cannot be cancelled for no-show");
            jdbc.update("INSERT INTO m1_no_show_violation (booking_id, occurred_at) VALUES (?, ?)",
                    booking.id(), java.sql.Timestamp.from(at));
            // This must enqueue via F's D port in this transaction. Any enqueue failure
            // rolls back M1's cancellation and violation; delivery runs after commit.
            notices.enqueueNoShow(booking.id(), booking.studentId(), at);
        }

        private void requireBooking() {
            if (booking == null) throw new IllegalStateException("No M1 booking was locked");
        }

        private void requireActive() {
            if (!active || owner != Thread.currentThread()) {
                throw new IllegalStateException("M1 booking lock handle is no longer active");
            }
        }

        private void expire() { active = false; }
    }

    private record Location(String roomId, Instant start, LocalDate useDate) {}
    private record BookingRow(Booking booking, LocalDate useDate) {}
}

/** Adapter to E's DailyRoomCodeFacts; lock must be held in the same transaction. */
@FunctionalInterface
interface CodeFactLocker {
    LockedCodeFact lockForBookingUseDate(String roomId, Instant bookingStart);
}

/** matches must be an in-memory comparison and may report unavailable code facts. */
@FunctionalInterface
interface LockedCodeFact {
    boolean matches(String submittedCode);
}

/** Adapter to F's InAppNoticePort.enqueue; the event key is derived from booking ID and NO_SHOW. */
@FunctionalInterface
interface NoShowNoticeWriter {
    void enqueueNoShow(String bookingId, String studentId, Instant cancelledAt);
}
