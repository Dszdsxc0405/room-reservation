package org.roomreservation.seatbooking.internal.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** The student ID is supplied by the authenticated principal at the eventual entry point. */
final class OwnNoShowViolations {
    private final ViolationReader reader;

    OwnNoShowViolations(ViolationReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    List<NoShowViolation> findMine(String authenticatedStudentId) {
        Objects.requireNonNull(authenticatedStudentId);
        return reader.findForStudent(authenticatedStudentId).stream()
                .filter(violation -> authenticatedStudentId.equals(violation.studentId()))
                .toList();
    }
}

record NoShowViolation(String bookingId, String studentId, Instant occurredAt) {
    NoShowViolation {
        Objects.requireNonNull(bookingId);
        Objects.requireNonNull(studentId);
        Objects.requireNonNull(occurredAt);
    }
}

interface ViolationReader {
    /** Return M1 violation facts for this student; B also enforces identity on the result. */
    List<NoShowViolation> findForStudent(String studentId);
}
