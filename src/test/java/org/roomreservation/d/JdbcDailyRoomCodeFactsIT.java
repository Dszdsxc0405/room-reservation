package org.roomreservation.d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real PostgreSQL lock check; runs only with -Pdb-integration on a dedicated test DB. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JdbcDailyRoomCodeFactsIT {
    @Autowired private DataSource dataSource;
    @Value("${test.database.name}") private String expectedDatabase;

    @Test
    void sharedCodeFactLockBlocksSameDayUpdateUntilBookingTransactionEnds() throws Exception {
        assertTrue(expectedDatabase.equals("rr_ci") || expectedDatabase.endsWith("_test"));
        String schema = "e_code_" + UUID.randomUUID().toString().replace("-", "");
        String migration = new ClassPathResource("db/migration/V2__d_daily_room_code.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertEquals(expectedDatabase, connection.getCatalog());
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET search_path TO " + schema);
            statement.execute(migration);
            statement.execute("INSERT INTO d_daily_room_code(room_id, use_local_date, code_value) "
                    + "VALUES ('room-1', DATE '2026-11-21', 'code-a')");
        }

        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            JdbcDailyRoomCodeFacts facts = new JdbcDailyRoomCodeFacts(jdbc, ZoneId.of("Asia/Singapore"));
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var reading = executor.submit(() -> tx.execute(status -> {
                    jdbc.execute("SET LOCAL search_path TO " + schema);
                    LockedDailyRoomCode fact = facts.lockForBookingUseDate("room-1",
                            Instant.parse("2026-11-20T16:00:00Z"));
                    assertTrue(fact.matches("code-a"));
                    locked.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                    return fact;
                }));
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("SET search_path TO " + schema);
                    statement.execute("SET lock_timeout TO '500ms'");
                    try {
                        statement.executeUpdate("UPDATE d_daily_room_code SET code_value = 'code-b' "
                                + "WHERE room_id = 'room-1' AND use_local_date = DATE '2026-11-21'");
                        throw new AssertionError("update should wait for the code read lock");
                    } catch (SQLException blocked) {
                        assertEquals("55P03", blocked.getSQLState());
                    }
                } finally {
                    release.countDown();
                }
                LockedDailyRoomCode old = reading.get(5, TimeUnit.SECONDS);
                try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("SET search_path TO " + schema);
                    assertEquals(1, statement.executeUpdate("UPDATE d_daily_room_code SET code_value = 'code-b' "
                            + "WHERE room_id = 'room-1' AND use_local_date = DATE '2026-11-21'"));
                }
                // A fact captured under the completed transaction cannot be reused after rotation.
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        () -> old.matches("code-a"));
            }
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
