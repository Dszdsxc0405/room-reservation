package org.roomreservation.m1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BookingStorageIT {
    private static final Instant START = Instant.parse("2026-11-20T02:00:00Z");
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    private JdbcTemplate jdbc;
    private BookingCoordinationLocks locks;
    private final String testSuffix = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        locks = new BookingCoordinationLocks(jdbc);
    }

    @AfterEach
    void removeOwnBookings() {
        jdbc.update("DELETE FROM m1_no_show_violation WHERE booking_id LIKE ?", "%--" + testSuffix);
        jdbc.update("DELETE FROM m1_booking WHERE booking_id LIKE ?", "%--" + testSuffix);
        jdbc.update("DELETE FROM m1_room_coord_lock WHERE room_id LIKE ?", "%--" + testSuffix);
        jdbc.update("DELETE FROM m1_student_coord_lock WHERE student_id LIKE ?", "%--" + testSuffix);
        jdbc.update("DELETE FROM m1_seat_coord_lock WHERE room_id LIKE ?", "%--" + testSuffix);
    }

    @Test
    void effectiveOverlapIsScopedToRoomAndSeatAndStudent() {
        insert("b1", "s1", "r1", "seat-1", START, START.plusSeconds(3600));
        insert("b2", "s2", "r2", "seat-1", START, START.plusSeconds(3600));
        assertThrows(DataIntegrityViolationException.class,
                () -> insert("b3", "s3", "r1", "seat-1", START.plusSeconds(1800),
                        START.plusSeconds(5400)));
        assertThrows(DataIntegrityViolationException.class,
                () -> insert("b4", "s1", "r2", "seat-2", START.plusSeconds(1800),
                        START.plusSeconds(5400)));
        jdbc.update("UPDATE m1_booking SET status = 'CANCELLED', cancelled_at = ?, "
                + "cancel_reason = 'ROOM_APPROVAL' WHERE booking_id = ?",
                java.sql.Timestamp.from(START), id("b1"));
        insert("b5", "s3", "r1", "seat-1", START, START.plusSeconds(3600));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE m1_booking SET room_id = ? WHERE booking_id = ?",
                        id("r3"), id("b5")));
    }

    @Test
    void checkInWaitsForRoomCoordinationLockAndUsesTimeAfterBookingLock() throws Exception {
        insert("b1", "s1", "r1", "seat-1", START, START.plusSeconds(3600));
        CountDownLatch roomHeld = new CountDownLatch(1);
        CountDownLatch releaseRoom = new CountDownLatch(1);
        CountDownLatch prelockedCode = new CountDownLatch(1);
        AtomicReference<Instant> now = new AtomicReference<>(START.plusSeconds(900));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var store = store((room, start) -> {
            prelockedCode.countDown();
            return code -> true;
        }, (booking, student, at) -> {});
        var lifecycle = new BookingLifecycle(store, (room, start, code, at) -> true, clock);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                locks.roomExclusive(id("r1"));
                roomHeld.countDown();
                try {
                    if (!releaseRoom.await(5, TimeUnit.SECONDS)) throw new AssertionError("room lock timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return null;
            }));
            assertTrue(roomHeld.await(5, TimeUnit.SECONDS));
            var checkIn = executor.submit(() -> lifecycle.checkIn(id("b1"), id("s1"), "valid"));
            assertThrows(TimeoutException.class, () -> checkIn.get(150, TimeUnit.MILLISECONDS));
            assertFalse(prelockedCode.await(150, TimeUnit.MILLISECONDS), "D fact lock must follow room lock");
            now.set(START.plusSeconds(901));
            releaseRoom.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertEquals(CheckInResult.OUTSIDE_WINDOW, checkIn.get(5, TimeUnit.SECONDS));
            assertTrue(prelockedCode.await(5, TimeUnit.SECONDS));
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM m1_booking WHERE booking_id = ? "
                + "AND checked_in_at IS NOT NULL", Integer.class, id("b1")));
    }

    @Test
    void checkInWaitsForBookingRowAndCannotUseHttpArrivalSecond() throws Exception {
        insert("b1", "s1", "r1", "seat-1", START, START.plusSeconds(3600));
        jdbc.update("INSERT INTO m1_room_coord_lock (room_id) VALUES (?) ON CONFLICT DO NOTHING",
                id("r1"));
        CountDownLatch bookingHeld = new CountDownLatch(1);
        CountDownLatch releaseBooking = new CountDownLatch(1);
        CountDownLatch codePrelocked = new CountDownLatch(1);
        AtomicReference<Instant> now = new AtomicReference<>(START.plusSeconds(900));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var store = store((room, start) -> {
            codePrelocked.countDown();
            return code -> true;
        }, (booking, student, at) -> {});
        var lifecycle = new BookingLifecycle(store, (room, start, code, at) -> true, clock);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                locks.roomShared(id("r1"));
                jdbc.queryForObject("SELECT booking_id FROM m1_booking WHERE booking_id = ? FOR UPDATE",
                        String.class, id("b1"));
                bookingHeld.countDown();
                try {
                    if (!releaseBooking.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("booking lock timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return null;
            }));
            assertTrue(bookingHeld.await(5, TimeUnit.SECONDS));
            var checkIn = executor.submit(() -> lifecycle.checkIn(id("b1"), id("s1"), "valid"));
            assertTrue(codePrelocked.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> checkIn.get(150, TimeUnit.MILLISECONDS));
            now.set(START.plusSeconds(901));
            releaseBooking.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertEquals(CheckInResult.OUTSIDE_WINDOW, checkIn.get(5, TimeUnit.SECONDS));
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM m1_booking WHERE booking_id = ? "
                + "AND checked_in_at IS NOT NULL", Integer.class, id("b1")));
    }

    @Test
    void noShowNoticeEnqueueFailureRollsBackCancellationAndViolation() {
        insert("b1", "s1", "r1", "seat-1", START, START.plusSeconds(3600));
        Clock dueClock = Clock.fixed(START.plusSeconds(901), ZoneId.of("UTC"));
        var failing = new BookingLifecycle(store((room, start) -> code -> true,
                (booking, student, at) -> { throw new IllegalStateException("D enqueue unavailable"); }),
                (room, start, code, at) -> true, dueClock);
        assertThrows(IllegalStateException.class, () -> failing.handleNoShow(id("b1")));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM m1_booking WHERE booking_id = ?",
                String.class, id("b1")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM m1_no_show_violation WHERE booking_id = ?",
                Integer.class, id("b1")));

        var working = new BookingLifecycle(store((room, start) -> code -> true,
                (booking, student, at) -> {}), (room, start, code, at) -> true, dueClock);
        assertEquals(TimeoutResult.CANCELLED_FOR_NO_SHOW, working.handleNoShow(id("b1")));
        assertEquals(TimeoutResult.ALREADY_RESOLVED, working.handleNoShow(id("b1")));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM m1_no_show_violation WHERE booking_id = ?",
                Integer.class, id("b1")));
    }

    @Test
    void committedCheckInAndConcurrentNoShowCannotBothWin() throws Exception {
        insert("b1", "s1", "r1", "seat-1", START, START.plusSeconds(3600));
        CountDownLatch checkInHasBookingLock = new CountDownLatch(1);
        CountDownLatch releaseCheckIn = new CountDownLatch(1);
        AtomicReference<Instant> now = new AtomicReference<>(START.plusSeconds(900));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var store = store((room, start) -> code -> true, (booking, student, at) -> {});
        var checkInLifecycle = new BookingLifecycle(store, (room, start, code, at) -> {
            checkInHasBookingLock.countDown();
            try {
                if (!releaseCheckIn.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("check-in timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return true;
        }, clock);
        var timeoutLifecycle = new BookingLifecycle(store, (room, start, code, at) -> true, clock);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var checkIn = executor.submit(() -> checkInLifecycle.checkIn(id("b1"), id("s1"), "valid"));
            assertTrue(checkInHasBookingLock.await(5, TimeUnit.SECONDS));
            now.set(START.plusSeconds(901));
            var noShow = executor.submit(() -> timeoutLifecycle.handleNoShow(id("b1")));
            assertThrows(TimeoutException.class, () -> noShow.get(150, TimeUnit.MILLISECONDS));
            releaseCheckIn.countDown();
            assertEquals(CheckInResult.CHECKED_IN, checkIn.get(5, TimeUnit.SECONDS));
            assertEquals(TimeoutResult.ALREADY_RESOLVED, noShow.get(5, TimeUnit.SECONDS));
        }
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM m1_booking WHERE booking_id = ?",
                String.class, id("b1")));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM m1_booking WHERE booking_id = ? "
                + "AND checked_in_at IS NOT NULL", Integer.class, id("b1")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM m1_no_show_violation WHERE booking_id = ?",
                Integer.class, id("b1")));
    }

    @Test
    void lockedHandleCannotWriteAfterCallbackReturns() {
        insert("b1", "s1", "r1", "seat-1", START, START.plusSeconds(3600));
        LockedBooking escaped = store((room, start) -> code -> true,
                (booking, student, at) -> {}).withLockedBooking(id("b1"), LifecycleAction.CHECK_IN,
                        locked -> locked);
        assertThrows(IllegalStateException.class, () -> escaped.markCheckedIn(START));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM m1_booking WHERE booking_id = ? "
                + "AND checked_in_at IS NOT NULL", Integer.class, id("b1")));
    }

    private JdbcBookingLifecycleStore store(CodeFactLocker codeFacts, NoShowNoticeWriter notices) {
        return new JdbcBookingLifecycleStore(jdbc, transactionManager, locks, codeFacts, notices);
    }

    private void insert(String bookingId, String studentId, String roomId, String seatId,
                        Instant start, Instant end) {
        jdbc.update("INSERT INTO m1_booking (booking_id, student_id, room_id, seat_id, "
                        + "starts_at, ends_at, use_local_date, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id(bookingId), id(studentId), id(roomId), id(seatId), java.sql.Timestamp.from(start),
                java.sql.Timestamp.from(end), start.atZone(ZoneId.of("Asia/Singapore")).toLocalDate(),
                java.sql.Timestamp.from(START.minusSeconds(3600)));
    }

    private String id(String value) { return value + "--" + testSuffix; }
}
