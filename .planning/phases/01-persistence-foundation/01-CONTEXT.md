# Phase 1: Persistence Foundation - Context

**Gathered:** 2026-09-01
**Status:** Ready for planning

<domain>
## Phase Boundary

Session, Participant, and Vote data is durably persisted to a real database via a correct, constraint-enforced schema, proven correct in isolation before any endpoint or UI is built on top of it. No REST endpoints, session lifecycle logic, TMDB integration, or WebSocket layer belong to this phase — those are Phases 2-6. This phase is entities + Flyway migrations + repository-level tests only.

</domain>

<decisions>
## Implementation Decisions

### Framework upgrade scope
- **D-01:** Bump Spring Boot 3.3.3 → 4.1.x and Kotlin 1.9.25 → 2.3.20 as part of this phase, before/alongside adding JPA, Flyway, and the Postgres driver. Do not add the persistence stack on top of the current 3.3.3/1.9.25 baseline. — **Reversibility:** costly — downgrading after later phases have written code against Boot 4/Kotlin 2.3-era APIs (JSpecify nullability annotations, Jakarta EE 11, Jackson 3 as default) would mean re-touching every phase built on top of it, not just this one.
- Watch for the known Boot 4 migration gotchas already flagged in `.planning/research/STACK.md`: JSpecify (`org.jspecify.annotations`) replacing Spring's own `@Nullable`/`@NonNull` can surface new Kotlin nullability mismatches on code that compiled fine under Boot 3.x.

### Local dev database setup
- **D-02:** Add a `docker-compose.yml` with a local Postgres service in this phase, even though there are no endpoints yet to manually exercise via `bootRun`. — **Reversibility:** reversible — a compose file is easy to add, remove, or change later with no schema/contract impact.
- Local Postgres version in compose should match the version targeted for Testcontainers and production (Postgres 17 or 18 per STACK.md) to avoid dialect surprises.

### Restart-survival test rigor
- **D-03:** The success-criteria test for "data survives a restart" (ROADMAP Phase 1, success criterion #1) must be a literal full-context restart: write rows via one Spring context wired to a Testcontainers Postgres container, shut that context down, bring up a fresh Spring context pointed at the *same* container, then read the rows back. A repository-level round-trip (write, `EntityManager.clear()`, read in the same running app) is explicitly NOT sufficient proof for this phase — it only proves the JPA first-level cache isn't masking a bug, not that the app process itself can restart.
- This is the direct test-level fix for the prior prototype's actual failure mode (in-memory `HttpSession`/`Map`-based state, which by definition cannot survive a process restart).

### Claude's Discretion
- Exact Flyway migration file granularity (one migration for all three tables vs. one per table).
- Primary key strategy details (UUID vs bigint) beyond what `.planning/research/ARCHITECTURE.md` already specifies (UUID for Session/Participant ids, raw `Long` TMDB id for `Vote.movieId`, no local `Movie`/`CachedMovie` entity referenced from `Vote` — that's Phase 3's `catalog/` package).
- Join code column type/length (Phase 2 owns actual code-generation logic; this phase only needs a unique, indexed column to satisfy success criterion #3).
- Package structure — follow the package-by-feature layout already specified in `.planning/research/ARCHITECTURE.md` (`session/`, `voting/`, not `controller/`/`service/`/`model/`).

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Stack & version decisions
- `.planning/research/STACK.md` — Database Decision (PostgreSQL over SQLite), Supporting Libraries table (Spring Data JPA, Flyway, Testcontainers, `org.postgresql:postgresql`), "What NOT to Use" table (no `ddl-auto=update`/`create-drop`, Flyway owns all schema changes), Version Compatibility table (Kotlin ≥2.2 requirement for Boot 4, Boot 4.1.x / Kotlin 2.3.20 pairing)

### Architecture & schema patterns
- `.planning/research/ARCHITECTURE.md` §"Pattern 1: Database-as-source-of-truth aggregation" — `Vote` entity shape, unique constraint on `(session_id, participant_id, movie_id)`, `movieId: Long` as a raw TMDB id (no local FK)
- `.planning/research/ARCHITECTURE.md` §"Pattern 2: Upsert-on-conflict vote writes" — native `INSERT ... ON CONFLICT DO UPDATE` upsert pattern for the vote write path (relevant to Phase 4, but the unique constraint it depends on is created here)
- `.planning/research/ARCHITECTURE.md` §"Package Structure" — package-by-feature layout (`session/`, `voting/`, `catalog/`, `realtime/`, `common/`) instead of the prior prototype's layer-based packages
- `.planning/research/ARCHITECTURE.md` §"Scaling Priorities" — indexes to create at schema-design time: `session_id` on Participant/Vote, unique index on join code, unique constraint on `(session_id, participant_id, movie_id)`
- `.planning/research/ARCHITECTURE.md` §"Anti-Pattern 1" — in-memory maps as the vote/session store is the prior prototype's documented root-cause bug; this phase exists specifically to make that structurally impossible

### Requirements & roadmap
- `.planning/ROADMAP.md` §"Phase 1: Persistence Foundation" — goal and the 4 success criteria this phase's plans must satisfy
- `.planning/REQUIREMENTS.md` — RELI-01 ("Session/participant/vote data lives in a real database and survives a server restart")

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- None. The existing `Lobby.kt`, `LobbyService.kt`, `MovieService.kt`, and `MovieVoteController.kt` are the prior in-memory prototype being replaced, not built upon (see `.planning/PROJECT.md` Context section). `MovieService.kt` also calls OMDb, not TMDB — fully superseded by Phase 3's catalog work.

### Established Patterns
- None worth carrying forward. `LobbyService.kt`'s commented-out `mutableMapOf` fields and `MovieVoteController.kt`'s `HttpSession`-attribute voting logic are exactly the anti-pattern this phase replaces (see `.planning/research/ARCHITECTURE.md` "Anti-Pattern 1").

### Integration Points
- `build.gradle.kts` currently declares `kotlin("jvm") version "1.9.25"`, `id("org.springframework.boot") version "3.3.3"`, and only `thymeleaf`/`web`/`websocket`/`jackson-module-kotlin` dependencies — no JPA, Flyway, Postgres driver, or Testcontainers yet. This phase's first plan touches this file to add the version bumps (D-01) and the persistence dependency set from `.planning/research/STACK.md`.
- `src/main/resources/application.properties` is currently a single line (`spring.application.name=MuviMatchr`) — this phase adds datasource, JPA (`ddl-auto=validate`), and Flyway config here (or a test-scoped equivalent for Testcontainers).

</code_context>

<specifics>
## Specific Ideas

No specific ideas beyond the three decisions above — this is an infrastructure phase with no UI/UX surface.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope. (Note: participant "active"/"left" status modeling was intentionally NOT discussed here — `.planning/STATE.md` already flags it as an explicit product decision for Phase 2/4 planning, not Phase 1's schema-only scope.)

</deferred>

---

*Phase: 1-Persistence Foundation*
*Context gathered: 2026-09-01*
