package org.roomreservation.seatbooking.internal.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Function;
import org.roomreservation.seatbooking.internal.domain.Booking;

/** BP-02 reminder decisions. The store owns the transaction and unique notice event. */
final class BookingReminders {
    private final ReminderStore store;
    private final Clock clock;

    BookingReminders(ReminderStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    ReminderResult trigger(String bookingId, ReminderKind kind) {
        Objects.requireNonNull(bookingId);
        Objects.requireNonNull(kind);
        return store.withLockedReminderBooking(bookingId, locked -> {
            Instant now = clock.instant(); // Only after the booking lock is acquired.
            Booking booking = locked.booking();
            if (booking == null) return ReminderResult.NOT_FOUND;
            if (booking.cancelled() || booking.checkedIn()) return ReminderResult.NO_LONGER_ELIGIBLE;

            Instant second = now.truncatedTo(ChronoUnit.SECONDS);
            Instant due = kind == ReminderKind.BEFORE_START
                    ? booking.start().minusSeconds(15 * 60)
                    : booking.start().plusSeconds(10 * 60);
            if (second.isBefore(due)) return ReminderResult.NOT_DUE;
            // A pre-start reminder is only useful before the start; the second reminder
            // belongs to the still-open check-in window.
            if ((kind == ReminderKind.BEFORE_START && !second.isBefore(booking.start()))
                    || (kind == ReminderKind.TEN_MINUTES_AFTER_START
                    && !second.isBefore(booking.start().plusSeconds(15 * 60 + 1)))) {
                return ReminderResult.NO_LONGER_ELIGIBLE;
            }
            return locked.recordAndEnqueue(kind, now)
                    ? ReminderResult.QUEUED : ReminderResult.ALREADY_QUEUED;
        });
    }
}

enum ReminderKind { BEFORE_START, TEN_MINUTES_AFTER_START }

enum ReminderResult { QUEUED, ALREADY_QUEUED, NOT_FOUND, NOT_DUE, NO_LONGER_ELIGIBLE }

/** B-facing contract; A's storage adapter and F's transactional notice adapter are separate work. */
interface ReminderStore {
    <T> T withLockedReminderBooking(String bookingId, Function<LockedReminderBooking, T> work);
}

interface LockedReminderBooking {
    Booking booking();

    /** Persist one reminder and its D notice in this transaction, uniquely by booking and kind. */
    boolean recordAndEnqueue(ReminderKind kind, Instant at);
}
