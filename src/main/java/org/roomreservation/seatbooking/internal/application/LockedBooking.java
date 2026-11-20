package org.roomreservation.seatbooking.internal.application;

import java.time.Instant;
import org.roomreservation.seatbooking.internal.domain.Booking;

public interface LockedBooking {
    Booking booking();

    void markCheckedIn(Instant at);

    /** Atomically change the booking, insert its unique violation, and persist a unique D notice. */
    void cancelForNoShowAndRecordViolationAndNotice(Instant at);
}
