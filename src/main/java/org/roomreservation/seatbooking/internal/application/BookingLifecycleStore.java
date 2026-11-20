package org.roomreservation.seatbooking.internal.application;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/** Every callback runs in one database transaction with the booking row locked to commit.
 * Acquire coordination and required D fact locks before the booking row lock.
 */
public interface BookingLifecycleStore {
    <T> T withLockedBooking(String bookingId, LifecycleAction action,
                            Function<LockedBooking, T> work);

    /** Candidates are hints only; handleNoShow rechecks under the booking lock. */
    List<String> findPotentialNoShows(Instant at, int limit);
}
