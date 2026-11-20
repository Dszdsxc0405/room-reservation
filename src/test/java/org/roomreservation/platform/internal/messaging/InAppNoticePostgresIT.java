package org.roomreservation.platform.internal.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.roomreservation.platform.InAppNoticePort;
import org.roomreservation.platform.NoticeEvent;
import org.roomreservation.platform.NoticeEventConflictException;
import org.roomreservation.platform.internal.persistence.JdbcInAppNoticePort;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs only against a PostgreSQL database named *_test or rr_ci. Uses its own schema. */
class InAppNoticePostgresIT {
    @Test
    void transactionalQueueAndProxiedDeliveryAreAtomicAndRetryable() throws Exception {
        String url = System.getenv("F_NOTICE_TEST_JDBC_URL");
        assumeTrue(url != null && !url.isBlank(), "set F_NOTICE_TEST_JDBC_URL for D notice integration");
        String user = System.getenv("F_NOTICE_TEST_DB_USER");
        String password = System.getenv("F_NOTICE_TEST_DB_PASSWORD");
        var adminDataSource = new DriverManagerDataSource(url, user, password);
        var adminJdbc = new JdbcTemplate(adminDataSource);
        String database = adminJdbc.queryForObject("SELECT current_database()", String.class);
        assertTrue(database.equals("rr_ci") || database.endsWith("_test"), "dedicated test database required");

        String schema = "f_notice_" + UUID.randomUUID().toString().replace("-", "");
        adminJdbc.execute("CREATE SCHEMA " + schema);
        try {
            String scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
            var dataSource = new DriverManagerDataSource(scopedUrl, user, password);
            var jdbc = new JdbcTemplate(dataSource);
            try (var stream = getClass().getResourceAsStream("/db/migration/V3__d_in_app_notice.sql")) {
                jdbc.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }

            try (var context = new AnnotationConfigApplicationContext()) {
                context.register(TransactionConfiguration.class);
                context.registerBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource));
                context.registerBean(JdbcInAppNoticePort.class, () -> new JdbcInAppNoticePort(jdbc));
                context.registerBean(FlakyInboxSink.class, () -> new FlakyInboxSink(jdbc));
                context.registerBean(JdbcInAppNoticeDispatcher.class,
                        () -> new JdbcInAppNoticeDispatcher(jdbc, context.getBean(FlakyInboxSink.class)));
                context.refresh();

                var transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                InAppNoticePort notices = context.getBean(JdbcInAppNoticePort.class);
                JdbcInAppNoticeDispatcher dispatcher = context.getBean(JdbcInAppNoticeDispatcher.class);
                var event = new NoticeEvent("booking-1:NO_SHOW", "notice", "NO_SHOW_CANCELLED",
                        "BOOKING", "booking-1", "student-1", "Your booking was cancelled",
                        Instant.parse("2026-11-20T02:15:01Z"));

                assertThrows(IllegalTransactionStateException.class, () -> notices.enqueue(event));
                assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
                    notices.enqueue(event);
                    // REQUIRES_NEW cannot see the uncommitted event from the suspended transaction.
                    assertFalse(dispatcher.deliverNext());
                    assertEquals(0, visibleCount(dataSource));
                    throw new IllegalStateException("cancelled business command");
                }));
                assertEquals(0, visibleCount(dataSource));

                Long firstId = transactions.execute(status -> notices.enqueue(event));
                Long retryId = transactions.execute(status -> notices.enqueue(event));
                assertEquals(firstId, retryId);
                assertEquals(1, visibleCount(dataSource));
                assertThrows(NoticeEventConflictException.class, () -> transactions.execute(status ->
                        notices.enqueue(new NoticeEvent(event.businessOperationId(), event.stepId(),
                                event.eventType(), event.subjectType(), event.subjectId(), event.recipientId(),
                                "Different content", event.triggeredAt()))));
                assertEquals(1, visibleCount(dataSource));

                // NESTED sink writes then fails; its savepoint must remove that inbox row.
                assertTrue(dispatcher.deliverNext());
                assertEquals(0, count(jdbc, "d_in_app_inbox"));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT count(*) FROM d_in_app_notice_attempt WHERE NOT succeeded", Integer.class));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT delivery_attempts FROM d_in_app_notice", Integer.class));
                jdbc.update("UPDATE d_in_app_notice SET next_attempt_at = clock_timestamp() - interval '1 second'");
                assertTrue(dispatcher.deliverNext());
                assertEquals(1, count(jdbc, "d_in_app_inbox"));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT count(*) FROM d_in_app_notice_attempt WHERE succeeded", Integer.class));
                assertEquals(2, jdbc.queryForObject(
                        "SELECT delivery_attempts FROM d_in_app_notice", Integer.class));
            }
        } finally {
            adminJdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private static int visibleCount(DriverManagerDataSource dataSource) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM d_in_app_notice")) {
            rows.next();
            return rows.getInt(1);
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {}

    static class FlakyInboxSink implements InAppNoticeSink {
        private final JdbcTemplate jdbc;
        private boolean fail = true;

        FlakyInboxSink(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override
        @Transactional(propagation = Propagation.NESTED)
        public void publish(long noticeId, String recipientId, String content) {
            jdbc.update("""
                    INSERT INTO d_in_app_inbox (notice_id, recipient_id, content)
                    VALUES (?, ?, ?) ON CONFLICT (notice_id) DO NOTHING
                    """, noticeId, recipientId, content);
            if (fail) {
                fail = false;
                throw new IllegalStateException("delivery unavailable after partial write");
            }
        }
    }
}
