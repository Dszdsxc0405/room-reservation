-- D owns the code fact. Publication and same-day replacement policy require review.
CREATE TABLE d_daily_room_code (
    room_id text NOT NULL,
    use_local_date date NOT NULL,
    code_value text NOT NULL CHECK (code_value <> ''),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    PRIMARY KEY (room_id, use_local_date)
);
