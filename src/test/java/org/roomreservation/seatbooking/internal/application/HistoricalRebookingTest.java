package org.roomreservation.seatbooking.internal.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HistoricalRebookingTest {
    private static final Instant OLD_START = Instant.parse("2026-11-18T02:00:00Z");
    private static final Instant OLD_END = Instant.parse("2026-11-18T03:00:00Z");
    private static final Instant NEW_START = Instant.parse("2026-11-22T02:00:00Z");
    private static final Instant NEW_END = Instant.parse("2026-11-22T03:00:00Z");
    private final HistoryBooking mine = new HistoryBooking(
            "old-1", "student-1", "seat-7", OLD_START, OLD_END);
    private final HistoryBooking someoneElses = new HistoryBooking(
            "old-2", "student-2", "seat-8", OLD_START, OLD_END);
    private final FakeHistory history = new FakeHistory(mine, someoneElses);
    private final FakeFreshBooking fresh = new FakeFreshBooking();
    private final HistoricalRebooking rebooking = new HistoricalRebooking(history, fresh);

    @Test
    void historyListContainsOnlyTheAuthenticatedStudentsRows() {
        // A's reader accidentally returns mixed rows; B still does not expose them.
        assertEquals(List.of(mine), rebooking.findMine("student-1"));
        assertEquals(List.of(someoneElses), rebooking.findMine("student-2"));
        assertEquals(List.of(), rebooking.findMine("student-3"));
    }

    @Test
    void rebookingUsesOriginalSeatAndChosenNewPeriodThroughNormalFreshSubmission() {
        Optional<FreshBookingOutcome> result = rebooking.rebook(
                "old-1", "student-1", NEW_START, NEW_END);

        assertEquals(Optional.of(new FreshBookingOutcome(true, "new-1", null)), result);
        assertEquals(List.of(new Attempt("student-1", "seat-7", NEW_START, NEW_END)),
                fresh.attempts);
        assertEquals(mine, history.rows.get("old-1")); // The old booking remains history.
    }

    @Test
    void anotherStudentsHistoryAndMissingHistoryCannotStartSubmission() {
        assertEquals(Optional.empty(), rebooking.rebook(
                "old-2", "student-1", NEW_START, NEW_END));
        assertEquals(Optional.empty(), rebooking.rebook(
                "missing", "student-1", NEW_START, NEW_END));
        assertEquals(List.of(), fresh.attempts);
    }

    @Test
    void currentBookingRejectionIsPassedThroughAndCreatesNothing() {
        // The historical seat is known, but A's current facts now deny the new period.
        fresh.currentlyBookable = false;
        assertEquals(Optional.of(new FreshBookingOutcome(false, null, "CURRENTLY_UNAVAILABLE")),
                rebooking.rebook("old-1", "student-1", NEW_START, NEW_END));
        assertEquals(List.of(new Attempt("student-1", "seat-7", NEW_START, NEW_END)),
                fresh.attempts);
        assertFalse(fresh.created);
    }

    @Test
    void requiredIdentityAndPeriodMustBeSuppliedByTheServerEntry() {
        assertThrows(NullPointerException.class, () -> rebooking.findMine(null));
        assertThrows(NullPointerException.class, () -> rebooking.rebook(
                "old-1", null, NEW_START, NEW_END));
        assertThrows(NullPointerException.class, () -> rebooking.rebook(
                "old-1", "student-1", null, NEW_END));
        assertEquals(List.of(), fresh.attempts);
    }

    private static final class FakeHistory implements BookingHistoryReader {
        private final Map<String, HistoryBooking> rows;

        FakeHistory(HistoryBooking first, HistoryBooking second) {
            rows = Map.of(first.bookingId(), first, second.bookingId(), second);
        }

        @Override public List<HistoryBooking> findHistoryForStudent(String studentId) {
            return List.copyOf(rows.values());
        }

        @Override public HistoryBooking findHistoryBooking(String bookingId) {
            return rows.get(bookingId);
        }
    }

    private record Attempt(String studentId, String seatId, Instant start, Instant end) {}

    private static final class FakeFreshBooking implements FreshBookingSubmitter {
        private final java.util.ArrayList<Attempt> attempts = new java.util.ArrayList<>();
        private boolean currentlyBookable = true;
        private boolean created;

        @Override public FreshBookingOutcome submit(String studentId, String seatId,
                                                    Instant start, Instant end) {
            attempts.add(new Attempt(studentId, seatId, start, end));
            if (!currentlyBookable) return new FreshBookingOutcome(false, null, "CURRENTLY_UNAVAILABLE");
            created = true;
            return new FreshBookingOutcome(true, "new-1", null);
        }
    }
}
