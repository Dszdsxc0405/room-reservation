package org.roomreservation.seatbooking.internal.persistence;

/** matches must be an in-memory comparison and may report unavailable code facts. */
@FunctionalInterface
public interface LockedCodeFact {
    boolean matches(String submittedCode);
}
