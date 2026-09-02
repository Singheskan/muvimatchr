# Phase 2: Session & Lobby Flow - Context

**Gathered:** 2026-09-02
**Status:** Ready for planning

<domain>
## Phase Boundary

A host can create a session and share it via a short join code; any number of participants (2+) can join with just a display name (no account), each receives a server-issued unguessable token that identifies them for all future requests, and a participant can leave and come back later via a personal resume link without losing their progress or being treated as a new participant. This phase delivers identity and session/participant lifecycle only — no movie catalog (Phase 3), no voting or match logic (Phase 4), no WebSocket live updates (Phase 5).

</domain>

<decisions>
## Implementation Decisions

### Join code & sharing
- **D-01:** Host shares a short typable join code only (e.g. `ABC123`) — no full shareable URL in this phase. Participant enters the code plus a display name on a join screen. Matches the `joinCode` field already built on the `Session` entity in Phase 1.

### Resume mechanism (SESH-05)
- **D-02:** Each participant is given a personal, bookmarkable resume link containing their session ID and unguessable token. Returning via that link (any device/browser) re-identifies them as the same participant with their existing votes attached — no login, no browser-local-storage dependency. — **Reversibility:** costly — the resume link's token *is* the participant's identity credential; once real participants are holding saved links, changing the token scheme (format, where it lives) breaks everyone's existing saved link.

### Host role
- **D-03:** The host has no special role or privileges — they are just the first participant to join the session they created. No host-only actions exist in this phase or are implied by current requirements. Do not add a host/owner flag to `Participant` or `Session` speculatively.

### Late joiners
- **D-04:** Participants can join a session at any time, including after other participants have already started swiping/voting — no lock, no cutoff. Matches the async voting model in PROJECT.md (people swipe whenever suits them). This phase does not need a "session started" or "locked" state.

### Display name collisions
- **D-05:** Duplicate display names within the same session are allowed (no uniqueness check). Each participant is distinguished by their own token/ID under the hood; a duplicate name is cosmetic only.

### Claude's Discretion
- Exact join-code generation strategy (charset, length within the existing `VARCHAR(16)` column, collision-retry approach) — implementation detail, not discussed with the user.
- Token format (opaque random string vs signed) and storage (hashed vs plaintext in DB) — standard credential-handling practice, treated as a technical implementation decision, not a product/vision question.
- Exact resume-link URL shape (path structure, query param vs path segment for the token) — implementation detail.

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Requirements & roadmap
- `.planning/REQUIREMENTS.md` — SESH-01 through SESH-05 (this phase's requirement set)
- `.planning/ROADMAP.md` §"Phase 2: Session & Lobby Flow" — goal, success criteria, dependency on Phase 1
- `.planning/PROJECT.md` — Core Value, Active Requirements, Constraints (no-accounts constraint directly shapes D-01–D-05), Key Decisions table

### Phase 1 artifacts this phase builds directly on top of
- `.planning/phases/01-persistence-foundation/01-01-SUMMARY.md`, `01-02-SUMMARY.md`, `01-03-SUMMARY.md` — what already exists: toolchain, `Session`/`Participant`/`Vote` entities and repositories, Flyway migrations V1–V3, local Postgres via docker-compose
- `src/main/kotlin/org/example/muvimatchr/session/Session.kt` — existing entity (`id`, `joinCode`, `createdAt`); has no participant-facing token field yet
- `src/main/kotlin/org/example/muvimatchr/session/Participant.kt` — existing entity (`id`, `session` FK, `displayName`, `createdAt`); has no token/credential field yet — this phase must add one
- `src/main/resources/db/migration/` (V1__create_session.sql, V2__create_participant.sql) — existing schema this phase's new migration(s) must extend, not replace

No external specs beyond the above — requirements fully captured in decisions above.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `Session`/`SessionRepository`, `Participant`/`ParticipantRepository` (Phase 1) — this phase's endpoints sit directly on top of these; `Participant` needs a new token column (or a related table) added via a new Flyway migration (V4+), following the established per-table-migration pattern from Phase 1.
- `PostgresTestSupport.kt` (Phase 1, `src/test/kotlin/org/example/muvimatchr/support/`) — shared singleton Testcontainers Postgres fixture, reusable for any new repository tests this phase adds.

### Established Patterns
- Flyway owns all DDL; `spring.jpa.hibernate.ddl-auto=validate` — any new columns/tables (e.g. a participant token) must ship as a new Flyway migration, never as a Hibernate auto-generated change.
- JPA entities are plain Kotlin classes (not `data class`), `@Id @GeneratedValue(strategy = GenerationType.UUID)`, relying on `kotlin("plugin.jpa")` for the no-arg constructor/all-open behavior — no manual `allOpen` block. Follow this shape for any new entity/columns.
- Native queries use named `@Param` bindings exclusively, never string interpolation (established by `VoteRepository.upsertVote` in Phase 1) — apply the same discipline to any token-lookup query this phase adds.

### Integration Points
- Old prototype's `LobbyController.kt`/`Lobby.kt` (`src/main/kotlin/org/example/muvimatchr/controller/`, `model/`) hold the *conceptual* lobby/join flow (join a lobby, track participants) referenced in PROJECT.md as "worth keeping conceptually" — but they use in-memory `HttpSession`-backed maps with no persistence, no tokens, and a `data class` participant model. **Not directly reusable code** — this phase's controllers/services are new, built on the Phase 1 entities, not adapted from these files. They remain in the repo as legacy prototype code; whether/when to delete them is out of this phase's scope.

</code_context>

<specifics>
## Specific Ideas

No specific UI/UX references given beyond the decisions above — open to standard approaches for the join screen and resume-link presentation.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope. (Host-only privileges were explicitly considered and declined for this phase — see D-03 — rather than deferred as a concrete future item; revisit only if a later phase's requirements actually need a host role.)

</deferred>

---

*Phase: 2-Session & Lobby Flow*
*Context gathered: 2026-09-02*
