package org.roomreservation.seatbooking.internal.persistence;

import java.time.Instant;

/** Adapter to E's DailyRoomCodeFacts; lock must be held in the same transaction. */
@FunctionalInterface
public interface CodeFactLocker {
    LockedCodeFact lockForBookingUseDate(String roomId, Instant bookingStart);
}
