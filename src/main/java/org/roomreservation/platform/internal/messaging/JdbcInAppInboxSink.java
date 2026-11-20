package org.roomreservation.platform.internal.messaging;

import java.util.Objects;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Makes a committed notice visible in the recipient's inbox. */
@Repository
@Lazy
class JdbcInAppInboxSink implements InAppNoticeSink {
    private final JdbcTemplate jdbc;

    JdbcInAppInboxSink(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    @Transactional(propagation = Propagation.NESTED)
    public void publish(long noticeId, String recipientId, String content) {
        jdbc.update("""
                INSERT INTO d_in_app_inbox (notice_id, recipient_id, content)
                VALUES (?, ?, ?) ON CONFLICT (notice_id) DO NOTHING
                """, noticeId, recipientId, content);
    }
}
