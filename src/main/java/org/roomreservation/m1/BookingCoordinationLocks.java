package org.roomreservation.m1;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** PostgreSQL coordination rows used before business rows in a single local transaction. */
public final class BookingCoordinationLocks {
    private final JdbcTemplate jdbc;

    public BookingCoordinationLocks(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public void roomShared(String roomId) {
        requireTransaction();
        jdbc.update("INSERT INTO m1_room_coord_lock (room_id) VALUES (?) ON CONFLICT DO NOTHING", roomId);
        jdbc.queryForObject("SELECT room_id FROM m1_room_coord_lock WHERE room_id = ? FOR SHARE",
                String.class, roomId);
    }

    public void roomExclusive(String roomId) {
        requireTransaction();
        jdbc.update("INSERT INTO m1_room_coord_lock (room_id) VALUES (?) ON CONFLICT DO NOTHING", roomId);
        jdbc.queryForObject("SELECT room_id FROM m1_room_coord_lock WHERE room_id = ? FOR UPDATE",
                String.class, roomId);
    }

    public void studentExclusive(String studentId) {
        requireTransaction();
        jdbc.update("INSERT INTO m1_student_coord_lock (student_id) VALUES (?) ON CONFLICT DO NOTHING",
                studentId);
        jdbc.queryForObject("SELECT student_id FROM m1_student_coord_lock WHERE student_id = ? FOR UPDATE",
                String.class, studentId);
    }

    public void seatExclusive(String roomId, String seatId) {
        requireTransaction();
        jdbc.update("INSERT INTO m1_seat_coord_lock (room_id, seat_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                roomId, seatId);
        jdbc.queryForObject("SELECT seat_id FROM m1_seat_coord_lock WHERE room_id = ? AND seat_id = ? FOR UPDATE",
                String.class, roomId, seatId);
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("M1 coordination locks require an active transaction");
        }
    }
}
