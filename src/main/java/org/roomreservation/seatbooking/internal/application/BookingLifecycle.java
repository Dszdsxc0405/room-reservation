package org.roomreservation.seatbooking.internal.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.roomreservation.seatbooking.internal.domain.Booking;

/** M1's BP-02 state transitions. Persistence and the two D ports are supplied by their owners. */
final class BookingLifecycle {
    private final BookingLifecycleStore store;
    private final DailyRoomCodeVerifier codes;
    private final Clock clock;

    BookingLifecycle(BookingLifecycleStore store, DailyRoomCodeVerifier codes, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.codes = Objects.requireNonNull(codes);
        this.clock = Objects.requireNonNull(clock);
    }

    /** studentId must come from the authenticated server principal, never the request body. */
    CheckInResult checkIn(String bookingId, String studentId, String code) {
        Objects.requireNonNull(bookingId);
        Objects.requireNonNull(studentId);
        Objects.requireNonNull(code);
        return store.withLockedBooking(bookingId, LifecycleAction.CHECK_IN, locked -> {
            // The adapter enters this callback only after acquiring the booking row lock.
            Instant acceptedAt = clock.instant();
            Booking booking = locked.booking();
            if (booking == null) return CheckInResult.NOT_FOUND;
            if (!booking.studentId().equals(studentId)) return CheckInResult.NOT_FOUND;
            if (booking.cancelled()) return CheckInResult.CANCELLED;
            if (booking.checkedIn()) return CheckInResult.ALREADY_CHECKED_IN;

            // The confirmed boundary uses the server's accepted whole second. Do not
            // recheck opening policy: D can preserve a booking after immediate closure.
            Instant acceptedSecond = acceptedAt.truncatedTo(ChronoUnit.SECONDS);
            if (acceptedSecond.isBefore(booking.start().minusSeconds(5 * 60))
                    || !acceptedSecond.isBefore(booking.start().plusSeconds(15 * 60 + 1))) {
                return CheckInResult.OUTSIDE_WINDOW;
            }
            if (!codes.isValidForCheckIn(booking.roomId(), booking.start(), code, acceptedAt)) {
                return CheckInResult.INVALID_CODE;
            }
            locked.markCheckedIn(acceptedAt);
            return CheckInResult.CHECKED_IN;
        });
    }

    TimeoutResult handleNoShow(String bookingId) {
        Objects.requireNonNull(bookingId);
        return store.withLockedBooking(bookingId, LifecycleAction.NO_SHOW, locked -> {
            Instant checkedAt = clock.instant();
            Booking booking = locked.booking();
            if (booking == null) return TimeoutResult.NOT_FOUND;
            if (booking.cancelled() || booking.checkedIn()) return TimeoutResult.ALREADY_RESOLVED;
            // BP-02 explicitly names the first second after the inclusive deadline.
            if (checkedAt.truncatedTo(ChronoUnit.SECONDS)
                    .isBefore(booking.start().plusSeconds(15 * 60 + 1))) {
                return TimeoutResult.NOT_DUE;
            }
            // One transaction must cancel, record one violation, and enqueue one D notice.
            // Delivery happens after commit; delivery failure must not undo these facts.
            locked.cancelForNoShowAndRecordViolationAndNotice(checkedAt);
            return TimeoutResult.CANCELLED_FOR_NO_SHOW;
        });
    }

    int handleDueBatch(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        List<String> ids = store.findPotentialNoShows(clock.instant(), limit);
        int cancelled = 0;
        for (String id : ids) {
            if (handleNoShow(id) == TimeoutResult.CANCELLED_FOR_NO_SHOW) cancelled++;
        }
        return cancelled;
    }
}

enum CheckInResult {
    CHECKED_IN, ALREADY_CHECKED_IN, NOT_FOUND, CANCELLED, OUTSIDE_WINDOW, INVALID_CODE
}

enum TimeoutResult {
    CANCELLED_FOR_NO_SHOW, ALREADY_RESOLVED, NOT_DUE, NOT_FOUND
}

/** D validates the code for the booking's use date in the school time zone. */
interface DailyRoomCodeVerifier {
    boolean isValidForCheckIn(String roomId, Instant bookingStart,
                              String submittedCode, Instant decisionAt);
}
