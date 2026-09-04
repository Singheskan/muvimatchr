# Phase 3 — TMDB API Coverage Matrix

**Produced:** 2026-09-04 (plan-phase, API Coverage Decision Checkpoint)
**External API:** TMDB v3 (`https://api.themoviedb.org/3`)
**Default posture:** INTEGRATE. Every `OPT-OUT` below carries an explicit one-line reason.

Scope of enumeration: TMDB capabilities a movie-swiping catalog integration could plausibly
need — not TMDB's entire API surface (no TV, people, lists, account, or auth-session
endpoints, none of which a movie deck can consume).

## Matrix

| capability | TMDB endpoint / param | decision | reason (required for OPT-OUT) |
|---|---|---|---|
| discover / browse movies | `GET /discover/movie` | INTEGRATE | — |
| popularity / rating ordering | `sort_by=popularity.desc` on `/discover/movie` | INTEGRATE | — |
| genre filtering | `with_genres` on `/discover/movie` | INTEGRATE | — |
| genre reference list | `GET /genre/movie/list` | INTEGRATE | — |
| watch-provider filtering (region-aware) | `with_watch_providers` + `watch_region` on `/discover/movie` | INTEGRATE | — |
| watch-provider reference list | `GET /watch/providers/movie?watch_region=` | INTEGRATE | — |
| per-movie watch providers (display, not just filter) | `GET /movie/{id}/watch/providers` | INTEGRATE | — (locked by D-10) |
| watch-provider deep link ("where to watch" URL) | `results.{region}.link` inside `/movie/{id}/watch/providers` | INTEGRATE | — free in a response already fetched; directly serves RSLT-02 |
| poster / backdrop image paths | `poster_path`, `backdrop_path` on `/discover/movie` results | INTEGRATE | — stored raw as TMDB returns them |
| monetization-type scoping of the provider filter | `with_watch_monetization_types` on `/discover/movie` | OPT-OUT | Official TMDB docs are silent on the omitted-parameter default (RESEARCH Assumption A1); sending nothing is the broadest match. Revisit only after the live-token confirmation in Plan 03-04 (Task 3's `<human-check>`) shows the default is narrower than "any streaming availability". |
| image base-URL / size configuration | `GET /configuration` | OPT-OUT | Poster URL composition (`secure_base_url` + a `poster_sizes` entry + `poster_path`) is a Phase 6 frontend concern; resolving and storing a sized URL now would force a data migration when the UI picks a different size (RESEARCH Anti-Pattern: "storing full TMDB image URLs"). |
| per-title full details | `GET /movie/{id}` | OPT-OUT | `/discover/movie` results already carry every field CTLG-01 names (title, poster, genre ids, vote average, release date, overview). A second per-title call would add runtime/budget/tagline that nothing in CTLG-01..05 or RSLT-02 renders, while doubling the per-refresh call count on top of D-10's existing 20 provider calls. |
| search by title | `GET /search/movie` | OPT-OUT | The deck is discover-driven by construction (D-03/D-05: one `/discover/movie` page per filter combo). No v1 requirement lets a participant type a title — CTLG-02/CTLG-03 are the only filtering requirements, and both are structured filters. |
| trending / now-playing / popular / top-rated lists | `GET /trending/movie/{window}`, `/movie/now_playing`, `/movie/popular`, `/movie/top_rated` | OPT-OUT | Redundant second sourcing path: D-03 locks `/discover/movie` ordered by TMDB's own popularity signal as *the* deck source. Adding a parallel list endpoint would create two competing definitions of "what's in the deck" with no requirement asking for either. |
| movie credits / cast | `GET /movie/{id}/credits` | OPT-OUT | No v1 requirement displays cast — CTLG-01 enumerates titles/posters/genres/providers, and RSLT-02's results view shows poster, title, and where to watch. |
| TMDB's own similar / recommended movies | `GET /movie/{id}/similar`, `/movie/{id}/recommendations` | OPT-OUT | PROJECT.md excludes ML-based recommendations / taste modeling from v1, and D-03 explicitly resolves "how do we pick interesting movies" as "TMDB popularity ordering, no custom or recommendation-derived logic". Integrating a recommendation endpoint would reopen a closed out-of-scope call. |
| reviews | `GET /movie/{id}/reviews` | OPT-OUT | Not referenced by any v1 requirement; the swipe card shows poster + title + providers, and there is no review surface anywhere in the six-phase roadmap. |
| videos / trailers | `GET /movie/{id}/videos` | OPT-OUT | Not in v1 requirements; would add a third per-title call per cache refresh for a surface no phase renders. |
| release dates / certifications (age rating) | `GET /movie/{id}/release_dates`, `certification_country` on `/discover/movie` | OPT-OUT | Rating-based filtering is CTLG-06, explicitly deferred to v2 in REQUIREMENTS.md. |
| runtime / release-year filtering | `with_runtime.gte`, `primary_release_year` on `/discover/movie` | OPT-OUT | CTLG-06 ("additional filters — runtime, release year, minimum rating") is explicitly a v2 requirement. |
| pagination beyond page 1 | `page` on `/discover/movie` | OPT-OUT | D-05 locks the deck at one fixed page (~20 movies) per filter combination — no multi-page pre-fetching. |
| TMDB account / auth-session endpoints | `/authentication/*`, `/account/*` | OPT-OUT | This app never acts on behalf of a TMDB user; CTLG-05 requires application-level (Bearer read-access-token) server-side calls only, and REQUIREMENTS.md excludes real user accounts entirely. |
| TV / series catalog | `/discover/tv`, `/tv/*` | OPT-OUT | PROJECT.md scopes the product to movies; nothing in the roadmap swipes on series. |

## Summary

- **INTEGRATE:** 9 capabilities, all delivered by this phase's plan set — discover/browse, popularity ordering,
  genre filtering and poster/backdrop paths in Plan 03-01; the genre and watch-provider reference lists in
  Plan 03-03; and watch-provider filtering, per-movie watch providers and the deep link in Plan 03-04.
- **OPT-OUT:** 14 capabilities — every one carries a reason grounded in a locked decision
  (D-03, D-05, D-10), an explicit v2 deferral (CTLG-06), a PROJECT.md out-of-scope call
  (ML recommendations, accounts, TV), or a later-phase ownership boundary (Phase 6 image
  URL composition).

No capability was left undecided.
