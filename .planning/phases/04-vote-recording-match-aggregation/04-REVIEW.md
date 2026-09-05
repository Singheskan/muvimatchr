---
phase: 04-vote-recording-match-aggregation
reviewed: 2026-09-05T00:00:00Z
depth: standard
files_reviewed: 17
files_reviewed_list:
  - src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt
  - src/main/kotlin/org/example/muvimatchr/session/Session.kt
  - src/main/kotlin/org/example/muvimatchr/session/SessionController.kt
  - src/main/kotlin/org/example/muvimatchr/session/SessionRepository.kt
  - src/main/kotlin/org/example/muvimatchr/session/SessionService.kt
  - src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt
  - src/main/kotlin/org/example/muvimatchr/voting/VoteController.kt
  - src/main/kotlin/org/example/muvimatchr/voting/VoteRepository.kt
  - src/main/kotlin/org/example/muvimatchr/voting/VoteService.kt
  - src/main/resources/application.properties
  - src/main/resources/db/migration/V8__add_session_deck_pin_and_genre.sql
  - src/test/kotlin/org/example/muvimatchr/RestartSurvivalTest.kt
  - src/test/kotlin/org/example/muvimatchr/catalog/DeckControllerTest.kt
  - src/test/kotlin/org/example/muvimatchr/session/SessionFiltersTest.kt
  - src/test/kotlin/org/example/muvimatchr/voting/MatchAggregationServiceTest.kt
  - src/test/kotlin/org/example/muvimatchr/voting/VoteControllerTest.kt
  - src/test/kotlin/org/example/muvimatchr/voting/VoteServiceConcurrencyTest.kt
findings:
  critical: 1
  warning: 3
  info: 2
  total: 6
status: issues_found
---

# Phase 04: Code Review Report

**Reviewed:** 2026-09-05T00:00:00Z
**Depth:** standard
**Files Reviewed:** 17
**Status:** issues_found

## Summary

Reviewed the vote-recording and match-aggregation phase: deck pinning (`Session`/`SessionService`/`DeckController`), filter locking (`SessionController`), vote recording (`VoteService`/`VoteController`/`VoteRepository`), and the read-model aggregation (`MatchAggregationService`), plus their tests and the `V8` migration.

The vote-recording path itself is solid: `VoteService.recordVote` correctly takes a `SELECT ... FOR UPDATE` row lock before the upsert, all aggregation queries are parameterized (no injection risk), and `VoteServiceConcurrencyTest` exercises the intended race with a `CountDownLatch` starting gun across ten iterations rather than a timing-dependent sleep.

However, the phase's other new state transition — first-successful-deck-read pinning (`SessionService.pinDeck`, called from `DeckController.getDeck`) — was built without the same row-locking discipline the authors clearly understood and applied to `VoteService.recordVote`. `pinDeck`'s own doc comment claims "first writer wins... a second call is a no-op that returns the session unchanged," but nothing in the implementation enforces that: no `SELECT ... FOR UPDATE`, and `Session` carries no `@Version` column for an optimistic-locking fallback either. Worse, `DeckController` builds its HTTP response from its own locally-fetched catalog result regardless of what `pinDeck` actually persisted, so a caller who loses a concurrent pin race gets back a response body that can diverge from the canonical pinned snapshot every later reader will see — a direct violation of this phase's own D-01 invariant ("that snapshot is the sole source of truth"). This is the review's one Critical finding; see CR-01 for detail and a concrete fix mirroring the pattern already used in `VoteService`.

A few smaller robustness/quality issues are noted as Warnings and Info below.

## Critical Issues

### CR-01: Deck-pin race — no row lock, and the HTTP response can diverge from the persisted pinned snapshot

**File:** `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt:80-89`, `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt:64-98`

**Issue:**

`SessionService.pinDeck` reads the session, checks `deckPinnedAt`, and — if null — writes `genre`/`pinnedDeckJson`/`deckPinnedAt` and saves, all inside a single `@Transactional` method:

```kotlin
@Transactional
fun pinDeck(sessionId: UUID, genreId: Int?, movies: List<MovieCatalogService.CachedMovie>): Session {
    val session = sessionRepository.findById(sessionId)
        .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No session with id $sessionId") }
    if (session.deckPinnedAt != null) return session
    session.genre = genreId
    session.pinnedDeckJson = objectMapper.writeValueAsString(movies)
    session.deckPinnedAt = Instant.now()
    return sessionRepository.save(session)
}
```

Unlike `VoteService.recordVote`, which calls `sessionRepository.lockForUpdate(sessionId)` as its very first statement specifically to close this class of race (per its own comment: "Every concurrent recordVote() call for THIS session blocks here until the previous call's transaction commits"), `pinDeck` never acquires that lock, and `Session` has no `@Version` field either. Under Postgres's default READ COMMITTED isolation, two concurrent first-time `GET /{sessionId}/deck` calls (entirely plausible — two participants opening the deck screen at the same moment) can both read `deckPinnedAt == null` before either commits, so the doc comment's "first writer wins... a second call is a no-op" guarantee is not actually enforced by anything in this code.

Compounding this, `DeckController.getDeck` discards the `Session` that `pinDeck` returns and always builds the response from its own locally-fetched `result`:

```kotlin
sessionService.pinDeck(sessionId, genreId, result.movies)
DeckResponse(
    sessionId = sessionId,
    status = "ok",
    stale = result.stale,
    fetchedAt = result.fetchedAt,
    totalResults = result.totalResults,
    movies = result.movies.map { it.toDeckMovieResponse() },
)
```

So even in the (more common) case where `pinDeck` correctly no-ops because another request already pinned first, this caller's HTTP response is still built from its *own* freshly-fetched catalog data, not from the snapshot that was actually persisted and that every later reader (including this same caller, on their next request) will be served. Because the outbound TMDB round trip inside `movieCatalogService.getDeck(...)` gives ample wall-clock time for a concurrent request to complete its own pin first, this is a real, reachable window — not a theoretical one — and it directly contradicts this phase's own stated invariant that the pinned snapshot is "the sole source of truth from here on."

There is no test covering this scenario; `VoteServiceConcurrencyTest` proves the analogous vote-recording race is closed, but no equivalent test exists for the deck-pin race, despite `pinDeck`'s comment asserting the same "first writer wins" guarantee.

**Fix:**
```kotlin
// SessionService.kt
@Transactional
fun pinDeck(sessionId: UUID, genreId: Int?, movies: List<MovieCatalogService.CachedMovie>): Session {
    sessionRepository.lockForUpdate(sessionId) // same pattern as VoteService.recordVote
    val session = sessionRepository.findById(sessionId)
        .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "No session with id $sessionId") }
    if (session.deckPinnedAt != null) return session
    session.genre = genreId
    session.pinnedDeckJson = objectMapper.writeValueAsString(movies)
    session.deckPinnedAt = Instant.now()
    return sessionRepository.save(session)
}
```
```kotlin
// DeckController.kt — build the response from what pinDeck actually persisted, not the local fetch
val pinnedSession = sessionService.pinDeck(sessionId, genreId, result.movies)
val pinnedMovies = sessionService.pinnedMovies(pinnedSession)
DeckResponse(
    sessionId = sessionId,
    status = "ok",
    stale = false,
    fetchedAt = pinnedSession.deckPinnedAt!!,
    totalResults = pinnedMovies.size,
    movies = pinnedMovies.map { it.toDeckMovieResponse() },
)
```
Add a concurrency test analogous to `VoteServiceConcurrencyTest` (two simultaneous first-time deck reads via a `CountDownLatch` starting gun) asserting exactly one canonical pinned snapshot results and every caller's response matches it.

## Warnings

### WR-01: `replaceFilters` runs external provider/genre validation before checking whether the deck is already locked

**File:** `src/main/kotlin/org/example/muvimatchr/session/SessionController.kt:57-75`

**Issue:** `replaceFilters` validates `providerIds` shape, then calls `catalogReferenceService.requireKnownProviders(...)` and `catalogReferenceService.requireKnownGenre(...)` (both can trigger outbound TMDB calls on a cache miss), and only afterward calls `sessionService.replaceFilters(...)`, which is where the `deckPinnedAt != null` → 409 check actually lives. A request against an already-pinned (locked) session still pays for full reference-data validation before being told the write was never going to be accepted. This is wasted work on every rejected write, and on a cold reference cache it burns TMDB rate budget for a request that can never succeed.

**Fix:** Check lock state before validating:
```kotlin
@PutMapping("/{sessionId}/filters")
fun replaceFilters(...): SessionFiltersResponse {
    if (participant.session.id != sessionId) {
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such participant in this session")
    }
    if (participant.session.deckPinnedAt != null) {
        throw ResponseStatusException(HttpStatus.CONFLICT, "Session filters are locked once the deck is pinned")
    }
    // ... existing validation + sessionService.replaceFilters(...)
}
```
(`SessionService.replaceFilters` can keep its own check too, for callers that don't go through the controller.)

### WR-02: `replaceFilters` and the deck-pin lazy trigger are not mutually locked, so a filter change can race a first deck read

**File:** `src/main/kotlin/org/example/muvimatchr/session/SessionService.kt:59-73`, `src/main/kotlin/org/example/muvimatchr/catalog/DeckController.kt:64-89`

**Issue:** `DeckController.getDeck` reads `session.genre`/`providerIds`/`region` once, up front, then performs a (potentially slow) catalog fetch, and only afterward calls `pinDeck`. Nothing prevents a concurrent `PUT /{sessionId}/filters` from committing a filter change in between: the deck ends up pinned using the *stale* filter values captured before the PUT, even though the PUT itself succeeded (session wasn't pinned yet when it ran) and the participant who issued it believes their new filters are now in effect for the group. This shares its root cause with CR-01 (no locking around the pin-time read) and would be closed by the same `lockForUpdate` fix, provided `replaceFilters` is also changed to take the lock before checking `deckPinnedAt`.

**Fix:** Once CR-01's `lockForUpdate` call is added to `pinDeck`, also add it as the first statement in `SessionService.replaceFilters`, so the two transactions can't interleave on the same session row.

### WR-03: No test coverage for `SessionService.pinDeck` concurrency, despite an equivalent suite existing for `VoteService.recordVote`

**File:** `src/test/kotlin/org/example/muvimatchr/voting/VoteServiceConcurrencyTest.kt` (present), no analogous file for deck pinning (absent)

**Issue:** The phase invested in a rigorous, non-sleep-based concurrency test for vote recording (`VoteServiceConcurrencyTest`, ten iterations, `CountDownLatch` starting gun, explicit pool-size sanity check) precisely because "a two-thread test that merely happens to overlap can pass for the wrong reason." The identical race exists for `pinDeck` (see CR-01) but has zero test coverage, so the regression this fix addresses would not have been caught by the existing suite and could silently reappear.

**Fix:** Add a `DeckPinConcurrencyTest` mirroring `VoteServiceConcurrencyTest`'s structure: N threads released by a single latch, each performing a first-time deck GET (or a direct `pinDeck` call) against the same never-pinned session, asserting exactly one persisted snapshot results and that it matches what every thread's response reported.

## Info

### IN-01: Redundant conjunct in `MatchAggregationService.computeStatus`

**File:** `src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt:55`

**Issue:** `val isComplete = deckSize > 0 && activeIds.isNotEmpty() && finishedCount == activeIds.size`. By this point in the method, the function has already returned early when `activeIds.isEmpty()` (lines 40-50), so `activeIds.isNotEmpty()` is always true here and is dead weight — it reads as if it's still guarding against an empty roster when that case can no longer reach this line.

**Fix:** Remove the redundant conjunct (or, if kept intentionally for readability/defensiveness, add a comment noting it's belt-and-suspenders given the earlier guard):
```kotlin
val isComplete = deckSize > 0 && finishedCount == activeIds.size
```

### IN-02: `@Value`-injected mutable `var` field where constructor injection would be more idiomatic

**File:** `src/main/kotlin/org/example/muvimatchr/voting/MatchAggregationService.kt:24-25`

**Issue:** `inactivityTimeoutSeconds` is a `var` populated via field injection (`@Value` on a property, not a constructor parameter). It's never reassigned after Spring populates it, so a `val` via constructor injection (`@Value("\${voting.inactivity-timeout-seconds:60}") private val inactivityTimeoutSeconds: Int`) would express the same immutability the rest of the class already relies on (the class doc comment states it "holds no mutable state").

**Fix:**
```kotlin
@Service
class MatchAggregationService(
    private val sessionRepository: SessionRepository,
    private val sessionService: SessionService,
    private val voteRepository: VoteRepository,
    @Value("\${voting.inactivity-timeout-seconds:60}") private val inactivityTimeoutSeconds: Int = 60,
) {
```

---

_Reviewed: 2026-09-05T00:00:00Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
