ALTER TABLE participant ADD COLUMN token_hash VARCHAR(64) NOT NULL;
CREATE UNIQUE INDEX uq_participant_token_hash ON participant (token_hash);
