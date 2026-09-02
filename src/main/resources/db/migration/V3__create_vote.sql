CREATE TABLE vote (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES session (id),
    participant_id UUID NOT NULL REFERENCES participant (id),
    movie_id BIGINT NOT NULL,
    choice VARCHAR(10) NOT NULL,
    voted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_vote_session_participant_movie UNIQUE (session_id, participant_id, movie_id)
);
CREATE INDEX idx_vote_session_id ON vote (session_id);
