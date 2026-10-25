-- Level 5. The transactional outbox: work to be done AFTER a transaction commits (send an email,
-- tell the warehouse) is written as a row IN that transaction. The order and its events commit
-- together or not at all, so no event is lost and none is sent for an order that rolled back.
-- A background worker (OutboxWorker) picks the rows up and runs them.
CREATE TABLE outbox_events (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type         VARCHAR(50)  NOT NULL,              -- e.g. OrderPlaced
    aggregate_id BIGINT       NOT NULL,              -- which order (or other thing) it is about
    payload      TEXT         NOT NULL,              -- JSON
    status       VARCHAR(10)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'DEAD')),
    attempts     INTEGER      NOT NULL DEFAULT 0,
    -- Not before this time. Used for retry backoff AND as the lease: claiming an event pushes
    -- this into the future, so if the worker dies mid-way the event becomes available again.
    available_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error   VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);

-- A PARTIAL index: only PENDING rows are in it. Done events pile up by the million, but the
-- worker's "what is due?" query only ever looks at the few pending ones, and so does this index.
CREATE INDEX idx_outbox_due ON outbox_events (available_at, id) WHERE status = 'PENDING';

-- In-app notifications ("Your order #12 was placed"), written by an outbox handler.
CREATE TABLE notifications (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    -- The outbox event that produced it. UNIQUE makes the handler idempotent: the worker delivers
    -- "at least once", and a second delivery of the same event inserts nothing.
    event_id   BIGINT       NOT NULL UNIQUE,
    message    VARCHAR(300) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_user ON notifications (user_id, id DESC);
