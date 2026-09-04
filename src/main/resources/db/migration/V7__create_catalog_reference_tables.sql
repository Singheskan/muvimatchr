CREATE TABLE genre (
    id UUID PRIMARY KEY,
    tmdb_id INT NOT NULL,
    name VARCHAR(64) NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_genre_tmdb_id ON genre (tmdb_id);

CREATE TABLE watch_provider (
    id UUID PRIMARY KEY,
    tmdb_id INT NOT NULL,
    region VARCHAR(2) NOT NULL,
    name VARCHAR(128) NOT NULL,
    logo_path VARCHAR(255),
    display_priority INT,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_watch_provider_region_tmdb_id ON watch_provider (region, tmdb_id);
