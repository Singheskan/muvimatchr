-- Nullable by design: null deck_pinned_at is the "not yet pinned" state (D-04's lazy trigger);
-- null genre means "no genre filter was set at pin time" (same nullable-optional-filter shape
-- CatalogReferenceService.requireKnownGenre(genreId: Int?) already expects).
ALTER TABLE session ADD COLUMN genre INT;
ALTER TABLE session ADD COLUMN pinned_deck JSONB;
ALTER TABLE session ADD COLUMN deck_pinned_at TIMESTAMPTZ;
