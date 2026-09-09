# MuviMatchr

A group movie-picking app: create a session, share a code or link, and everyone swipes through a deck of movies filtered by genre and streaming service. When everyone's done, MuviMatchr surfaces the one movie you'll actually both watch tonight — no back-and-forth debate required.

No accounts, no signup. Join with a code and a display name, swipe on your own time, and get a live-updating result as soon as the group finishes.

## How it works

1. **Host creates a session** and gets a shareable join code / link.
2. **Participants join** with the code, pick a display name — no account needed.
3. Everyone **swipes left/right** through a deck of movies, filtered by genre and by which streaming services the group actually has.
4. Voting is **async** — swipe now, finish later, come back and resume. A live waiting screen shows who's still deciding.
5. Once everyone's finished, the app computes the **best mutual match** and shows it to everyone in real time.

## Tech stack

**Backend** — Kotlin + Spring Boot 4, PostgreSQL (Flyway migrations), STOMP over WebSocket for live updates, [TMDB](https://www.themoviedb.org/) for movie data (titles, posters, genres, watch-provider availability).

**Frontend** — React 19 + TypeScript SPA (Vite), TanStack Query, `@stomp/stompjs` for the live waiting-room/results view.

**Testing** — Kotlin backend tests run against a real Postgres via Testcontainers (including dedicated concurrency tests — simultaneous-finish races, vote recording, deck pinning); frontend tests via Vitest + Testing Library.

## Why it exists

This is a full rebuild of an earlier prototype that never quite worked: it kept everything in memory (state was lost on every restart) and never correctly finished aggregating votes across participants. The rebuild fixes both — real persistence, and vote aggregation proven correct under concurrent finishes with a real database, not just mocked in tests.

A few decisions worth calling out:

- **Pessimistic row-level locking** (`SELECT ... FOR UPDATE`, written directly rather than relying on an ORM annotation that's silently ignored on native queries) serializes vote recording and deck pinning — this is the exact class of bug that broke the prototype's vote aggregation.
- **Zero cached "completion" state.** Who's finished, who's still active, and what the match is are recomputed from the database on every read — no in-JVM counter that can drift from reality.
- **Deck and filters are pinned once**, on a session's first read, so "unanimous match" always means every participant voted against the exact same deck.
- **Retry → stale-cache → 503 degradation** for TMDB outages, so a transient upstream hiccup doesn't take the app down if a usable cached deck already exists.

## Running it locally

Requires Docker (bundles Postgres + the app) and a free [TMDB API read-access token](https://www.themoviedb.org/settings/api).

```bash
git clone <this-repo>
cd MuviMatchr
echo "TMDB_API_TOKEN=your-token-here" > .env.local
docker compose up --build
```

The app is served at `http://localhost:8080`.

### Running without Docker

```bash
# Backend (needs a local Postgres — see application.properties for expected creds)
TMDB_API_TOKEN=your-token-here ./gradlew bootRun

# Frontend, in a second terminal
cd frontend
npm install
npm run dev
```

## Project status

Actively developed. Core flow — sessions, join, filtered swiping, persistent async voting, correct match aggregation — is built and tested end-to-end. The real-time waiting-room and results screens are the current focus.

Out of scope for v1: real accounts/auth, a ranked list of runner-up matches, and any ML-based recommendation — see `.planning/PROJECT.md` for the full requirements and decision log.

## License

Dual-licensed — MIT for open-source use; a commercial license is available for proprietary use. See [LICENSE.md](LICENSE.md) for details.
