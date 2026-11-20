-- D-owned, transactional notice outbox. Delivery must begin only after commit.
CREATE TABLE d_in_app_notice (
    notice_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    business_operation_id text NOT NULL,
    step_id text NOT NULL,
    event_type text NOT NULL,
    subject_type text NOT NULL,
    subject_id text NOT NULL,
    recipient_id text NOT NULL,
    content text NOT NULL,
    triggered_at timestamptz NOT NULL,
    queued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    delivered_at timestamptz,
    delivery_attempts integer NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    last_attempt_at timestamptz,
    last_delivery_error text,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_d_notice_event UNIQUE (
        business_operation_id, step_id, event_type, subject_type, subject_id, recipient_id
    ),
    CONSTRAINT ck_d_notice_delivery CHECK (delivered_at IS NULL OR delivery_attempts > 0)
);

CREATE INDEX ix_d_notice_pending ON d_in_app_notice (next_attempt_at, notice_id)
    WHERE delivered_at IS NULL;

CREATE TABLE d_in_app_inbox (
    notice_id bigint PRIMARY KEY REFERENCES d_in_app_notice(notice_id),
    recipient_id text NOT NULL,
    content text NOT NULL,
    visible_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_d_in_app_inbox_recipient ON d_in_app_inbox (recipient_id, visible_at DESC);

CREATE TABLE d_in_app_notice_attempt (
    notice_id bigint NOT NULL REFERENCES d_in_app_notice(notice_id),
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    attempted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    succeeded boolean NOT NULL,
    failure_reason text,
    PRIMARY KEY (notice_id, attempt_number),
    CONSTRAINT ck_d_notice_attempt_result CHECK (succeeded = (failure_reason IS NULL))
);
