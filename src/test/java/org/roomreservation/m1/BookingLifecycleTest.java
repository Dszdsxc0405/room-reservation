package org.roomreservation.m1;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class BookingLifecycleTest {
    private static final Instant START = Instant.parse("2026-11-20T02:00:00Z");
    private final MutableClock clock = new MutableClock();
    private final InMemoryStore store = new InMemoryStore();
    private final BookingLifecycle lifecycle = new BookingLifecycle(store,
            (room, start, code, at) -> room.equals("room-1") && code.equals("today"), clock);

    @Test
    void inclusiveCheckInWindowAndCodeAndIdentity() {
        clock.set(START.minusSeconds(301));
        assertEquals(CheckInResult.OUTSIDE_WINDOW, lifecycle.checkIn("booking-1", "student-1", "today"));
        clock.set(START.minusSeconds(300));
        assertEquals(CheckInResult.NOT_FOUND, lifecycle.checkIn("booking-1", "other-student", "today"));
        assertEquals(CheckInResult.INVALID_CODE, lifecycle.checkIn("booking-1", "student-1", "yesterday"));
        assertEquals(CheckInResult.CHECKED_IN, lifecycle.checkIn("booking-1", "student-1", "today"));
        assertEquals(CheckInResult.ALREADY_CHECKED_IN, lifecycle.checkIn("booking-1", "student-1", "today"));
        assertEquals(1, store.checkIns);

        store.reset();
        clock.set(START.plusSeconds(900));
        assertEquals(CheckInResult.CHECKED_IN, lifecycle.checkIn("booking-1", "student-1", "today"));
        assertEquals(TimeoutResult.ALREADY_RESOLVED, lifecycle.handleNoShow("booking-1"));
        assertEquals(0, store.violations);

        store.reset();
        clock.set(START.plusSeconds(900).plusNanos(999_999_999));
        assertEquals(CheckInResult.CHECKED_IN, lifecycle.checkIn("booking-1", "student-1", "today"));

        store.reset();
        clock.set(START.plusSeconds(901));
        assertEquals(CheckInResult.OUTSIDE_WINDOW, lifecycle.checkIn("booking-1", "student-1", "today"));
    }

    @Test
    void firstSecondAfterDeadlineCancelsExactlyOnceAndReleasesBooking() {
        clock.set(START.plusSeconds(900));
        assertEquals(TimeoutResult.NOT_DUE, lifecycle.handleNoShow("booking-1"));
        clock.set(START.plusSeconds(901));
        assertEquals(1, lifecycle.handleDueBatch(20));
        assertEquals(0, lifecycle.handleDueBatch(20));
        assertEquals(TimeoutResult.ALREADY_RESOLVED, lifecycle.handleNoShow("booking-1"));
        assertEquals(CheckInResult.CANCELLED, lifecycle.checkIn("booking-1", "student-1", "today"));
        assertTrue(store.cancelled);
        assertEquals(1, store.violations);
        assertEquals(1, store.queuedNotices);
        assertThrows(RuntimeException.class, store::deliverQueuedNotices);
        assertTrue(store.cancelled);
        assertEquals(1, store.violations);
        assertEquals(1, store.queuedNotices);
    }

    @Test
    void repairCancellationDoesNotAllowCheckInOrCreateViolation() {
        store.cancelled = true;
        clock.set(START.plusSeconds(901));
        assertEquals(CheckInResult.CANCELLED, lifecycle.checkIn("booking-1", "student-1", "today"));
        assertEquals(TimeoutResult.ALREADY_RESOLVED, lifecycle.handleNoShow("booking-1"));
        assertEquals(0, store.violations);
    }

    @Test
    void existingBookingCanCheckInAfterImmediateRoomClosure() {
        // The existing booking survives a D closure; D's current opening policy is
        // deliberately absent from this transition. Only the room's daily code matters.
        store.roomNowClosed = true;
        clock.set(START.plusSeconds(600));
        assertEquals(CheckInResult.CHECKED_IN, lifecycle.checkIn("booking-1", "student-1", "today"));
        assertTrue(store.roomNowClosed);
    }

    @Test
    void midnightBookingUsesItsUseDateCodeDuringPreviousDayCheckInWindow() {
        store.start = Instant.parse("2026-11-20T16:00:00Z"); // 21 Nov 00:00 in Singapore
        clock.set(store.start.minusSeconds(300)); // 20 Nov 23:55 in Singapore
        BookingLifecycle withDateCode = new BookingLifecycle(store,
                (room, bookingStart, code, at) -> code.equals(bookingStart
                        .atZone(ZoneId.of("Asia/Singapore")).toLocalDate().toString()), clock);

        assertEquals(CheckInResult.INVALID_CODE,
                withDateCode.checkIn("booking-1", "student-1", "2026-11-20"));
        assertEquals(CheckInResult.CHECKED_IN,
                withDateCode.checkIn("booking-1", "student-1", "2026-11-21"));
    }

    @Test
    void checkInThatHoldsTheRowLockWinsOverConcurrentTimeout() throws Exception {
        CountDownLatch verifierEntered = new CountDownLatch(1);
        CountDownLatch releaseVerifier = new CountDownLatch(1);
        BookingLifecycle blocking = new BookingLifecycle(store, (room, start, code, at) -> {
            verifierEntered.countDown();
            try {
                if (!releaseVerifier.await(5, TimeUnit.SECONDS)) throw new AssertionError("verifier timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return true;
        }, clock);
        clock.set(START.plusSeconds(900));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var checkIn = executor.submit(() -> blocking.checkIn("booking-1", "student-1", "today"));
            assertTrue(verifierEntered.await(5, TimeUnit.SECONDS));
            clock.set(START.plusSeconds(901));
            var timeout = executor.submit(() -> lifecycle.handleNoShow("booking-1"));
            releaseVerifier.countDown();
            assertEquals(CheckInResult.CHECKED_IN, checkIn.get(5, TimeUnit.SECONDS));
            assertEquals(TimeoutResult.ALREADY_RESOLVED, timeout.get(5, TimeUnit.SECONDS));
        }
        assertEquals(0, store.violations);
    }

    @Test
    void timeoutThatHoldsTheRowLockWinsOverConcurrentCheckIn() throws Exception {
        CountDownLatch cancellationEntered = new CountDownLatch(1);
        CountDownLatch releaseCancellation = new CountDownLatch(1);
        store.beforeCancel = () -> {
            cancellationEntered.countDown();
            try {
                if (!releaseCancellation.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("cancellation timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        };
        clock.set(START.plusSeconds(901));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var timeout = executor.submit(() -> lifecycle.handleNoShow("booking-1"));
            assertTrue(cancellationEntered.await(5, TimeUnit.SECONDS));
            var checkIn = executor.submit(() -> lifecycle.checkIn("booking-1", "student-1", "today"));
            releaseCancellation.countDown();
            assertEquals(TimeoutResult.CANCELLED_FOR_NO_SHOW, timeout.get(5, TimeUnit.SECONDS));
            assertEquals(CheckInResult.CANCELLED, checkIn.get(5, TimeUnit.SECONDS));
        }
        assertEquals(1, store.violations);
        assertEquals(1, store.queuedNotices);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(START);

        void set(Instant at) { instant.set(at); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }

    private static final class InMemoryStore implements BookingLifecycleStore {
        private final ReentrantLock lock = new ReentrantLock();
        private boolean checkedIn;
        private boolean cancelled;
        private int checkIns;
        private int violations;
        private int queuedNotices;
        private boolean roomNowClosed;
        private Instant start = START;
        private Runnable beforeCancel = () -> {};

        void reset() {
            checkedIn = false;
            cancelled = false;
            checkIns = 0;
            violations = 0;
            queuedNotices = 0;
        }

        void deliverQueuedNotices() {
            if (queuedNotices > 0) throw new RuntimeException("D delivery unavailable");
        }

        @Override
        public <T> T withLockedBooking(String bookingId, LifecycleAction action,
                                      Instant enteredAt, Function<LockedBooking, T> work) {
            lock.lock();
            try {
                return work.apply(new LockedBooking() {
                    @Override public Booking booking() {
                        return bookingId.equals("booking-1")
                                ? new Booking("booking-1", "student-1", "room-1", start,
                                        cancelled, checkedIn)
                                : null;
                    }
                    @Override public void markCheckedIn(Instant at) {
                        checkedIn = true;
                        checkIns++;
                    }
                    @Override public void cancelForNoShowAndRecordViolationAndNotice(Instant at) {
                        beforeCancel.run();
                        cancelled = true;
                        violations++;
                        queuedNotices++;
                    }
                });
            } finally {
                lock.unlock();
            }
        }

        @Override public List<String> findPotentialNoShows(Instant at, int limit) {
            return !cancelled && !checkedIn && !at.isBefore(start.plusSeconds(901))
                    ? List.of("booking-1") : List.of();
        }
    }
}
