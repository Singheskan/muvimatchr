CREATE TABLE participant (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES session (id),
    display_name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_participant_session_id ON participant (session_id);
