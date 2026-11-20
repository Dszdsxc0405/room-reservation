CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE m1_booking (
    booking_id text PRIMARY KEY,
    student_id text NOT NULL,
    room_id text NOT NULL,
    seat_id text NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    use_local_date date NOT NULL,
    created_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'ACTIVE',
    checked_in_at timestamptz,
    cancelled_at timestamptz,
    cancel_reason text,
    cancel_source_type text,
    cancel_source_id text,
    CONSTRAINT m1_booking_range_valid CHECK (starts_at < ends_at),
    CONSTRAINT m1_booking_status_valid CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT m1_booking_cancel_consistent CHECK (
        (status = 'ACTIVE' AND cancelled_at IS NULL AND cancel_reason IS NULL
            AND cancel_source_type IS NULL AND cancel_source_id IS NULL)
        OR
        (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL
            AND ((cancel_source_type IS NULL AND cancel_source_id IS NULL)
                OR (cancel_source_type IS NOT NULL AND cancel_source_id IS NOT NULL)))
    ),
    CONSTRAINT m1_booking_no_show_unchecked CHECK (
        cancel_reason IS DISTINCT FROM 'NO_SHOW' OR checked_in_at IS NULL
    ),
    CONSTRAINT m1_booking_seat_no_overlap EXCLUDE USING gist (
        room_id WITH =, seat_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status = 'ACTIVE'),
    CONSTRAINT m1_booking_student_no_overlap EXCLUDE USING gist (
        student_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (status = 'ACTIVE')
);

CREATE INDEX m1_booking_due_idx ON m1_booking (starts_at, booking_id)
    WHERE status = 'ACTIVE' AND checked_in_at IS NULL;
CREATE INDEX m1_booking_student_history_idx ON m1_booking (student_id, use_local_date DESC, starts_at DESC);
CREATE INDEX m1_booking_room_use_date_idx ON m1_booking (room_id, use_local_date, starts_at);

CREATE TABLE m1_no_show_violation (
    booking_id text PRIMARY KEY REFERENCES m1_booking (booking_id),
    occurred_at timestamptz NOT NULL
);

CREATE TABLE m1_room_coord_lock (room_id text PRIMARY KEY);
CREATE TABLE m1_student_coord_lock (student_id text PRIMARY KEY);
CREATE TABLE m1_seat_coord_lock (
    room_id text NOT NULL,
    seat_id text NOT NULL,
    PRIMARY KEY (room_id, seat_id)
);

CREATE FUNCTION m1_booking_identity_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.booking_id, OLD.student_id, OLD.room_id, OLD.seat_id,
        OLD.starts_at, OLD.ends_at, OLD.use_local_date, OLD.created_at)
       IS DISTINCT FROM
       (NEW.booking_id, NEW.student_id, NEW.room_id, NEW.seat_id,
        NEW.starts_at, NEW.ends_at, NEW.use_local_date, NEW.created_at) THEN
        RAISE EXCEPTION 'm1_booking identity and time fields are immutable'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER m1_booking_identity_immutable_before_update
    BEFORE UPDATE ON m1_booking
    FOR EACH ROW EXECUTE FUNCTION m1_booking_identity_immutable();
