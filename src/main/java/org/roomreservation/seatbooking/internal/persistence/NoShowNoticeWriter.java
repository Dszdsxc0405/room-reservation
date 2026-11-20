package org.roomreservation.seatbooking.internal.persistence;

import java.time.Instant;

/** Adapter to F's InAppNoticePort.enqueue; the event key is derived from booking ID and NO_SHOW. */
@FunctionalInterface
public interface NoShowNoticeWriter {
    void enqueueNoShow(String bookingId, String studentId, Instant cancelledAt);
}
