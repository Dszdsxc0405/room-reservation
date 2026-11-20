package org.roomreservation.platform;

import java.time.LocalDate;

/** A D code fact protected by the caller's still-open database transaction. */
public interface LockedDailyRoomCode {
    String roomId();

    LocalDate useDate();

    long revision();

    /** Compare only while the row lock is held. Missing facts are never treated as a bad code. */
    boolean matches(String submittedCode);
}
