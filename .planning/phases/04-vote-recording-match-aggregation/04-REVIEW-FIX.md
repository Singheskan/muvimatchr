---
phase: 04-vote-recording-match-aggregation
fixed_at: 2026-09-05T20:30:00Z
review_path: .planning/phases/04-vote-recording-match-aggregation/04-REVIEW.md
iteration: 1
findings_in_scope: 4
fixed: 4
skipped: 0
status: all_fixed
---

# Phase 04: Code Review Fix Report

**Fixed at:** 2026-09-05T20:30:00Z
**Source review:** .planning/phases/04-vote-recording-match-aggregation/04-REVIEW.md
**Iteration:** 1

**Summary:**
- Findings in scope: 4 (fix_scope=critical_warning: CR-01, WR-01, WR-02, WR-03; IN-01/IN-02 excluded as Info-tier)
- Fixed: 4
- Skipped: 0

**Verification environment note:** All edits were made and committed inside an isolated git
worktree (`.claude/worktrees/rf-04-*`, on temp branch `gsd-reviewfix/04-*`), fast-forwarded onto
`main` afterward. Tier 2 syntax verification via `./gradlew compileKotlin` failed in this
environment on a Gradle/JDK-version incompatibility unrelated to any of the edits below (same
failure reproduces on unmodified `main` at this JDK version) — Kotlin is not in the verification
table's supported-language list, and the fallback compile attempt itself hit a pre-existing
tooling mismatch, not a code error. Every fix below was therefore verified with **Tier 1** (careful
re-read of the modified section, cross-checked against every test in
`DeckControllerTest.kt`/`SessionFiltersTest.kt` that exercises the changed code path) plus manual
review of call sites; none were run against the actual Postgres integration/concurrency suite.

**Human verification pass (post-fix, orchestrator):** `./gradlew test` was run with the project's
required JDK 21 + Testcontainers/Docker environment variables and found 2 regressions the Tier-1
fixer pass could not have caught without executing the suite:

1. **`stale` hardcoded to `false` (CR-01 fix regression).** The CR-01 fix's own note ("`stale` is
   now hardcoded to `false`... for consistency") was wrong: pre-fix, the "ok" branch reported
   `stale = result.stale` (this caller's own fetch outcome), and `DeckControllerTest`'s *"a stale
   cache row served after an upstream outage surfaces as a 200 with stale true"* test depends on
   that propagation for its unpinned second session. Fixed in `DeckController.kt` by restoring
   `stale = result.stale` — the pinned `Session` row carries no stale flag of its own, so the
   caller's own fetch outcome remains the only signal available, exactly as before CR-01.
2. **Raw-string JSON comparison in `DeckPinConcurrencyTest` (WR-03 test bug, not a CR-01 bug).**
   The new test asserted `pinnedDeckJson` string equality across racing callers. Postgres's `jsonb`
   column type does not preserve object-key order or whitespace on round-trip (Postgres docs: "the
   jsonb data type does not preserve white space, does not preserve the order of object keys") — so
   a winning writer's own in-process return value (the literal string it just serialized, never
   round-tripped through the column) reliably differs textually from a losing caller's freshly
   `findById`-read value, even though both describe the identical persisted movies. Debug
   instrumentation confirmed the underlying locking is correct (exactly one thread ever wrote; the
   other two correctly observed `deckPinnedAt` already set and no-opped) — only the test's
   assertion was wrong. Fixed by parsing both sides into `List<CachedMovie>` before comparing
   (structural equality), rather than comparing raw JSON text.

After both fixes, `./gradlew test` passes in full (all suites, including `DeckPinConcurrencyTest`
re-run 3x to rule out flakiness) and `DeckPinConcurrencyTest`/`VoteServiceConcurrencyTest` confirm
the row-lock fixes (CR-01, WR-02) actually close the races they claim to. The three
`requires human verification` statuses below are superseded by this pass — treat them as
`verified` for phase-completion purposes.

## Fixed Issues

### CR-01: Deck-pin race — no row lock, and the HTTP response can diverge from the persisted pinned snapshot

**Files modified:** `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt`, `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt`
**Commit:** `addb27c`
**Status:** fixed: requires human verification (concurrency/locking behavior — needs the Postgres-backed test suite to confirm the race is actually closed)
**Applied fix:** Added `sessionRepository.lockForUpdate(sessionId)` as the first statement in `SessionService.pinDeck` (mirroring `VoteService.recordVote`'s existing pattern), closing the window where two concurrent first-time deck reads could both observe `deckPinnedAt == null`. In `DeckController.getDeck`'s "ok" branch, the response is now built from the `Session` and `pinnedMovies` that `pinDeck` actually returned/persisted (`pinnedSession.deckPinnedAt!!`, `pinnedMovies.size`, `pinnedMovies.map { ... }`), rather than from the caller's own freshly-fetched `result` — so a caller who loses the pin race now sees the canonical persisted snapshot in its own response, matching D-01. `stale` is now hardcoded to `false` in this branch, for consistency with the already-pinned branch above it (lines 44-53), which has no persisted staleness field to report from either.
**Verified against existing tests:** Confirmed no test in `DeckControllerTest.kt` exercises the ">= MINIMUM_DECK_SIZE movies served via a stale-cache outage fallback" combination (the only scenario where the `stale=false` hardcoding could diverge from prior behavior); the one stale-fallback test present (`a stale cache row served after an upstream outage surfaces as a 200 with stale true`) resolves only 1 movie, taking the untouched `insufficient_results` branch. `two different participants of the same session receive identical deck filtering` already anticipated `totalResults` being derived from the pinned snapshot's movie count for the second reader — the fix now makes the first reader consistent with that too.

### WR-01: `replaceFilters` runs external provider/genre validation before checking whether the deck is already locked

**Files modified:** `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt`
**Commit:** `3d76f12`
**Status:** fixed
**Applied fix:** Added a `participant.session.deckPinnedAt != null` check (409 Conflict) as the first statement in `SessionController.replaceFilters`, before `validateProviderIds`/`catalogReferenceService.requireKnownProviders`/`requireKnownGenre` run. `SessionService.replaceFilters` keeps its own equivalent check for callers that bypass the controller. Verified against `SessionFiltersTest`'s `filters are freely replaceable by any participant before the deck is pinned and refused together with a 409 once it is...` test: the post-pin 409 assertion in that test enqueues no additional TMDB fixtures for the second (post-pin) call, meaning that call was already not expected to reach the network — reordering the check earlier changes nothing observable there.

### WR-02: `replaceFilters` and the deck-pin lazy trigger are not mutually locked, so a filter change can race a first deck read

**Files modified:** `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt`
**Commit:** `4d50d7e`
**Status:** fixed: requires human verification (concurrency/locking behavior — needs the Postgres-backed test suite to confirm the two transactions can no longer interleave)
**Applied fix:** Added `sessionRepository.lockForUpdate(sessionId)` as the first statement in `SessionService.replaceFilters`, using the same row-lock pattern now present in `pinDeck` (CR-01) and `VoteService.recordVote`, so a concurrent filter replacement and first-time deck-pin transaction can no longer interleave on the same session row.

### WR-03: No test coverage for `SessionService.pinDeck` concurrency, despite an equivalent suite existing for `VoteService.recordVote`

**Files modified:** `src/test/kotlin/org/example/muvimatchr/session/DeckPinConcurrencyTest.kt` (new file)
**Commit:** `47099e4`
**Status:** fixed: requires human verification (new concurrency test — could not be executed in this environment; needs a real run against Postgres/Testcontainers to confirm it compiles and passes)
**Applied fix:** Added `DeckPinConcurrencyTest`, mirroring `VoteServiceConcurrencyTest`'s structure: a `CountDownLatch` starting gun releases `racingThreadCount` (3) threads simultaneously, each calling `sessionService.pinDeck` with a distinct candidate movie list against the same never-pinned session, repeated across 10 independently created sessions. Includes the same Hikari `maximumPoolSize` sanity check used in `VoteServiceConcurrencyTest` to rule out the connection pool as an accidental serialiser. Asserts exactly one distinct `pinnedDeckJson` is returned across all racing calls, and that it matches the session row's actually-persisted snapshot — directly proving CR-01's invariant.

## Skipped Issues

None — all in-scope findings were fixed.

---

_Fixed: 2026-09-05T20:30:00Z_
_Fixer: Claude (gsd-code-fixer)_
_Iteration: 1_
