package org.roomreservation.platform.internal.persistence;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import org.roomreservation.platform.InAppNoticePort;
import org.roomreservation.platform.NoticeEvent;
import org.roomreservation.platform.NoticeEventConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** D owns the notice rows; M1/M2/M3 only call this port inside their transaction. */
@Repository
@Lazy
public class JdbcInAppNoticePort implements InAppNoticePort {
    private final JdbcTemplate jdbc;

    public JdbcInAppNoticePort(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long enqueue(NoticeEvent event) {
        Objects.requireNonNull(event, "event");
        List<Long> inserted = jdbc.query("""
                INSERT INTO d_in_app_notice (
                    business_operation_id, step_id, event_type, subject_type, subject_id,
                    recipient_id, content, triggered_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (business_operation_id, step_id, event_type, subject_type,
                             subject_id, recipient_id) DO NOTHING
                RETURNING notice_id
                """, (rs, row) -> rs.getLong(1), event.businessOperationId(), event.stepId(),
                event.eventType(), event.subjectType(), event.subjectId(), event.recipientId(),
                event.content(), Timestamp.from(event.triggeredAt()));
        if (!inserted.isEmpty()) return inserted.getFirst();

        // PostgreSQL waits for an in-flight conflicting insert before returning from ON CONFLICT.
        // The event identity is stable; a retry may have a later attempted trigger time.
        List<StoredNotice> existing = jdbc.query("""
                SELECT notice_id, content FROM d_in_app_notice
                WHERE business_operation_id = ? AND step_id = ? AND event_type = ?
                  AND subject_type = ? AND subject_id = ? AND recipient_id = ?
                """, (rs, row) -> new StoredNotice(rs.getLong(1), rs.getString(2)),
                event.businessOperationId(), event.stepId(), event.eventType(),
                event.subjectType(), event.subjectId(), event.recipientId());
        if (existing.size() != 1) throw new IllegalStateException("notice event disappeared during retry");
        StoredNotice stored = existing.getFirst();
        if (!stored.content().equals(event.content())) {
            throw new NoticeEventConflictException("event key already has different content");
        }
        return stored.id();
    }

    private record StoredNotice(long id, String content) {}
}
