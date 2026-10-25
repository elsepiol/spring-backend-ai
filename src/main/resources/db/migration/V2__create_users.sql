-- Users and their role. We store a password HASH, never the password itself.
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Emails are stored lower-cased by the service, so this also makes them case-insensitively unique.
    CONSTRAINT uq_users_email UNIQUE (email)
);
