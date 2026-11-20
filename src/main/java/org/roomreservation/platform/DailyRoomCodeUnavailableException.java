package org.roomreservation.platform;

import java.time.LocalDate;

/** D could not confirm the code fact; callers must not map this to INVALID_CODE. */
public final class DailyRoomCodeUnavailableException extends RuntimeException {
    public DailyRoomCodeUnavailableException(String roomId, LocalDate useDate) {
        super("daily room code unavailable for room " + roomId + " on " + useDate);
    }
}
