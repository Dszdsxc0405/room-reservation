package org.roomreservation.seatbooking.internal.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OwnNoShowViolationsTest {
    @Test
    void returnsOnlyAuthenticatedStudentsViolationsEvenIfReaderReturnsMixedRows() {
        Instant at = Instant.parse("2026-11-20T02:15:01Z");
        NoShowViolation mine = new NoShowViolation("booking-1", "student-1", at);
        NoShowViolation others = new NoShowViolation("booking-2", "student-2", at);
        OwnNoShowViolations query = new OwnNoShowViolations(studentId -> List.of(others, mine));

        assertEquals(List.of(mine), query.findMine("student-1"));
        assertEquals(List.of(others), query.findMine("student-2"));
        assertEquals(List.of(), new OwnNoShowViolations(studentId -> List.of())
                .findMine("student-1"));
        assertThrows(NullPointerException.class, () -> query.findMine(null));
    }
}
