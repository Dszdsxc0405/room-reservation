package org.roomreservation.d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class JdbcDailyRoomCodeFactsTest {
    @Test
    void previousDayEarlyCheckInSelectsBookingUseDateAndRejectsPreviousDayCode() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LocalDate useDate = LocalDate.parse("2026-11-21");
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenAnswer(call -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("revision")).thenReturn(1L);
            when(rs.getString("code_value")).thenReturn("next-day-code");
            RowMapper<?> mapper = call.getArgument(1);
            return List.of(mapper.mapRow(rs, 0));
        });
        JdbcDailyRoomCodeFacts facts = new JdbcDailyRoomCodeFacts(jdbc, ZoneId.of("Asia/Singapore"));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        LockedDailyRoomCode locked;
        try {
            // Booking starts at school-local midnight; check-in may occur the previous day.
            locked = facts.lockForBookingUseDate("room-1",
                    Instant.parse("2026-11-20T16:00:00Z"));
            verify(jdbc).query(anyString(), any(RowMapper.class), eq("room-1"), eq(useDate));
            assertEquals(useDate, locked.useDate());
            assertTrue(locked.matches("next-day-code"));
            assertFalse(locked.matches("previous-day-code"));
        } finally {
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
            TransactionSynchronizationManager.clear();
        }
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> locked.matches("next-day-code"));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void missingCodeIsUnavailableRatherThanInvalidCode() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
        JdbcDailyRoomCodeFacts facts = new JdbcDailyRoomCodeFacts(jdbc, ZoneId.of("Asia/Singapore"));

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            LockedDailyRoomCode missing = facts.lockForBookingUseDate(
                    "room-1", Instant.parse("2026-11-20T16:00:00Z"));
            assertThrows(DailyRoomCodeUnavailableException.class, () -> missing.matches("any-code"));
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void lockReadCannotRunOutsideTransaction() {
        JdbcDailyRoomCodeFacts facts = new JdbcDailyRoomCodeFacts(mock(JdbcTemplate.class),
                ZoneId.of("Asia/Singapore"));
        assertThrows(IllegalStateException.class, () -> facts.lockForBookingUseDate("room-1",
                Instant.parse("2026-11-20T16:00:00Z")));
    }
}
