CREATE TABLE deck_cache_entry (
    id UUID PRIMARY KEY,
    cache_key VARCHAR(128) NOT NULL,
    movies JSONB NOT NULL,
    total_results INT NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_deck_cache_entry_key ON deck_cache_entry (cache_key);
