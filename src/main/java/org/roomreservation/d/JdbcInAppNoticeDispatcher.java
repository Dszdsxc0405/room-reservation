package org.roomreservation.d;

import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Call from a post-commit worker. One call processes at most one committed notice. */
@Repository
@Lazy
public class JdbcInAppNoticeDispatcher {
    private final JdbcTemplate jdbc;
    private final InAppNoticeSink sink;

    JdbcInAppNoticeDispatcher(JdbcTemplate jdbc, InAppNoticeSink sink) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.sink = Objects.requireNonNull(sink);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean deliverNext() {
        List<PendingNotice> pending = jdbc.query("""
                SELECT notice_id, recipient_id, content, delivery_attempts
                FROM d_in_app_notice
                WHERE delivered_at IS NULL AND next_attempt_at <= clock_timestamp()
                ORDER BY next_attempt_at, notice_id
                FOR UPDATE SKIP LOCKED LIMIT 1
                """, (rs, row) -> new PendingNotice(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getInt(4)));
        if (pending.isEmpty()) return false;
        PendingNotice notice = pending.getFirst();
        int attempt = notice.attempts() + 1;
        try {
            sink.publish(notice.id(), notice.recipientId(), notice.content());
        } catch (RuntimeException failure) {
            // The sink owns a nested savepoint; its partial writes are rolled back first.
            String reason = failure.getClass().getSimpleName();
            jdbc.update("""
                    INSERT INTO d_in_app_notice_attempt (notice_id, attempt_number, succeeded, failure_reason)
                    VALUES (?, ?, false, ?)
                    """, notice.id(), attempt, reason);
            jdbc.update("""
                    UPDATE d_in_app_notice SET delivery_attempts = ?, last_attempt_at = clock_timestamp(),
                        last_delivery_error = ?, next_attempt_at = clock_timestamp() + interval '1 minute'
                    WHERE notice_id = ?
                    """, attempt, reason, notice.id());
            return true;
        }
        jdbc.update("""
                INSERT INTO d_in_app_notice_attempt (notice_id, attempt_number, succeeded)
                VALUES (?, ?, true)
                """, notice.id(), attempt);
        jdbc.update("""
                UPDATE d_in_app_notice SET delivery_attempts = ?, last_attempt_at = clock_timestamp(),
                    delivered_at = clock_timestamp(), last_delivery_error = NULL
                WHERE notice_id = ?
                """, attempt, notice.id());
        return true;
    }

    private record PendingNotice(long id, String recipientId, String content, int attempts) {}
}
