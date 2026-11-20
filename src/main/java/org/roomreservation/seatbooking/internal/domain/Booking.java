package org.roomreservation.seatbooking.internal.domain;

import java.time.Instant;
import java.util.Objects;

public record Booking(String id, String studentId, String roomId, Instant start,
                      boolean cancelled, boolean checkedIn) {
    public Booking {
        Objects.requireNonNull(id);
        Objects.requireNonNull(studentId);
        Objects.requireNonNull(roomId);
        Objects.requireNonNull(start);
    }
}
