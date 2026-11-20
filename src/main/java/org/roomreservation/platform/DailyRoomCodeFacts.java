package org.roomreservation.platform;

import java.time.Instant;

/** D-owned code fact for the room and the booking's use date.
 * Call after M1 coordination locks and before the M1 booking row lock.
 */
public interface DailyRoomCodeFacts {
    /**
     * Reads and locks the code row until the caller's transaction commits or rolls back.
     * The date is derived from bookingStart in the configured school time zone, even
     * when check-in occurs on the previous local day.
     *
     * If the fact is absent, returns a handle that reports unavailability only when
     * matches is called. This preserves M1's identity, status, and window checks.
     */
    LockedDailyRoomCode lockForBookingUseDate(String roomId, Instant bookingStart);
}
