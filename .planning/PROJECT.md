# MuviMatchr

## What This Is

MuviMatchr is a group movie-picking app: a host creates a session, shares a code/link, and everyone who joins (starting with a couple, extensible to friend groups) picks a display name and swipes left/right through a deck of movies filtered by genre and streaming availability. Voting is async — people can swipe whenever suits them, with a live-updating waiting screen once they're done. When everyone in the session has finished, the app surfaces the single best mutual match (with room to later show a ranked list of runner-up matches too).

## Core Value

Two (or more) people with different tastes can independently pick movies they'd watch and get a fast, confident answer to "what do we actually both want to watch tonight" — without the back-and-forth debate.

## Requirements

### Validated

(None yet — this is a rebuild; nothing from the prior attempt is considered validated)

### Active

- [ ] Host can create a session and get a shareable code/link
- [ ] Participants join a session via code/link and pick a display name (no account/signup required)
- [ ] Session supports 2+ participants (couple use case is primary, groups supported from v1)
- [ ] Movie deck can be filtered by genre
- [ ] Movie deck can be filtered by streaming availability (which services a title is on)
- [ ] Movie data (titles, posters, genres, streaming availability) comes from a real source (TMDB)
- [ ] Participant can swipe left (pass) / right (like) through the deck
- [ ] Voting is async — a participant can leave and resume; progress and votes persist
- [ ] Participant who finishes early sees a live-updating "waiting on N people" screen (via WebSocket), not a static page they must refresh
- [ ] When all participants finish, session transitions to a results view automatically for anyone watching the waiting screen, and shows results immediately to anyone who returns later
- [ ] Results correctly aggregate votes across all participants (the prior app never finished this — this is a hard requirement, not a nice-to-have)
- [ ] Results view shows the single best mutual match as the primary result
- [ ] Data survives an app/server restart (session state, votes, results are persisted, not in-memory only)

### Out of Scope

- Real user accounts / auth (email+password, OAuth) — code/link + display name is enough for v1; revisit if this becomes a product people return to across many separate sessions
- Ranked list of all mutual matches as the primary result — deferred to v2; v1 shows a single top pick but the data model should not preclude adding this later
- Live-simultaneous swiping UX (e.g. both swiping at the exact same moment with real-time deck sync) — async is the target UX, not simultaneous play
- Recommendation/personalization beyond genre + streaming filters (no ML-based taste modeling) — out of scope for v1

## Context

- This is a full rebuild of an earlier prototype in this same repo (Kotlin/Spring Boot + Thymeleaf + WebSocket, commits "Voting independently works, but adding together votes not yet"). That prototype is being replaced, not incrementally patched: it stored all state in-memory (lost on restart), never finished multi-user vote aggregation, and pulled movie data from a placeholder `MovieService` rather than a real catalog.
- Prior app's working pieces worth keeping conceptually: lobby/join flow, WebSocket-based live updates, swipe-per-movie voting loop. These ideas carry forward; the code will be substantially rewritten to add persistence and fix aggregation.

## Constraints

- **Tech stack**: Kotlin + Spring Boot backend, paired with a proper SPA frontend (not server-rendered Thymeleaf) — decided so the swipe UI can feel snappy and native-app-like. Frontend framework choice (React/Vue/etc.) still open, to be settled during stack research.
- **Movie data**: TMDB (The Movie Database) API for titles, posters, genres, and streaming/watch-provider availability — chosen for real, current data instead of a hardcoded list.
- **Persistence**: Needs a real database (not in-memory maps) — sessions, participants, votes, and results must survive a server restart. Specific DB choice open, to be settled during stack research.
- **Hosting**: Targeting a small public deployment (e.g. Railway/Fly.io/Render free tier) — reachable by a link for friends to join, not a scaled public product.
- **Real-time**: WebSocket (or equivalent) needed for the live-updating waiting screen.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Rebuild rather than patch existing code | Vote aggregation was never finished and state model is in-memory-only; core data model needs to change anyway | — Pending |
| Keep Kotlin/Spring Boot, add SPA frontend | User knows the backend stack; wants a real frontend instead of Thymeleaf for a better swipe UX | — Pending |
| TMDB for movie data | Real titles/posters/genres/streaming availability instead of a placeholder list | — Pending |
| Async voting with live waiting screen | Matches actual usage pattern (people swipe on their own time), while still feeling live when others are online | — Pending |
| Group sessions from v1, single-best-match result for v1 | Support couples and friend groups without over-scoping the results UI; ranked list deferred to v2 | — Pending |
| No accounts — code/link + display name | Keeps friction low for a casual "watch party" tool | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-09-01 after initialization*
