package org.roomreservation.seatbooking.internal.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** BP-01 AC-05: a history item supplies only the original seat, never permission to book it. */
final class HistoricalRebooking {
    private final BookingHistoryReader history;
    private final FreshBookingSubmitter submitter;

    HistoricalRebooking(BookingHistoryReader history, FreshBookingSubmitter submitter) {
        this.history = Objects.requireNonNull(history);
        this.submitter = Objects.requireNonNull(submitter);
    }

    /** studentId comes from the authenticated principal at the eventual entry point. */
    List<HistoryBooking> findMine(String studentId) {
        Objects.requireNonNull(studentId);
        return history.findHistoryForStudent(studentId).stream()
                .filter(booking -> studentId.equals(booking.studentId()))
                .toList();
    }

    /** An empty result hides both nonexistent history and another student's history. */
    Optional<FreshBookingOutcome> rebook(String historyBookingId, String studentId,
                                        Instant newStart, Instant newEnd) {
        Objects.requireNonNull(historyBookingId);
        Objects.requireNonNull(studentId);
        Objects.requireNonNull(newStart);
        Objects.requireNonNull(newEnd);
        HistoryBooking source = history.findHistoryBooking(historyBookingId);
        if (source == null || !studentId.equals(source.studentId())) return Optional.empty();
        // A's normal new-booking command must recheck current eligibility, time and occupancy
        // under its own locks. The old booking's status or availability grants no exemption.
        return Optional.of(submitter.submit(studentId, source.seatId(), newStart, newEnd));
    }
}

record HistoryBooking(String bookingId, String studentId, String seatId,
                      Instant start, Instant end) {
    HistoryBooking {
        Objects.requireNonNull(bookingId);
        Objects.requireNonNull(studentId);
        Objects.requireNonNull(seatId);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
    }
}

/** A supplies persisted historical M1 facts; the history criterion remains to be agreed. */
interface BookingHistoryReader {
    List<HistoryBooking> findHistoryForStudent(String studentId);

    HistoryBooking findHistoryBooking(String bookingId);
}

/** B-facing adapter to A's normal new-booking command, with its current final checks. */
interface FreshBookingSubmitter {
    FreshBookingOutcome submit(String studentId, String seatId, Instant start, Instant end);
}

/** A's final decision is passed through without reinterpreting its rejection reason. */
record FreshBookingOutcome(boolean created, String bookingId, String rejectionCode) {
    FreshBookingOutcome {
        if (created == (bookingId == null) || created == (rejectionCode != null)) {
            throw new IllegalArgumentException("created requires bookingId; rejection requires a code");
        }
    }
}
