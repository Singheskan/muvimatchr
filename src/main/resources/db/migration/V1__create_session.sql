CREATE TABLE session (
    id UUID PRIMARY KEY,
    join_code VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_session_join_code ON session (join_code);
