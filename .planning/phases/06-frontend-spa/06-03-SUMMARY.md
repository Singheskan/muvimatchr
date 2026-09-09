---
phase: 06-frontend-spa
plan: 03
subsystem: frontend
tags: [react, typescript, motion, vitest, react-testing-library, tanstack-query, tdd]

# Dependency graph
requires:
  - phase: 06-frontend-spa
    provides: "06-01: single-JAR SPA scaffold, apiFetch/ApiError, useBootstrap, useSessionToken, D-04 route table"
  - phase: 04-vote-recording-match-aggregation
    provides: "VoteController.recordVote 400/401/409 semantics this screen surfaces verbatim"
  - phase: 03-tmdb-catalog
    provides: "DeckController's ok/insufficient_results/stale response shape"
provides:
  - "frontend/src/swipe/swipeDecision.ts, SwipeCard.tsx, CardStack.tsx -- the draggable card stack primitives and its unit-tested commit rule"
  - "frontend/src/routes/SwipeScreen.tsx -- real deck fetch, resume-from-server, server-confirmed vote commit, deck-exhaustion navigation (on-mount and post-vote)"
  - "frontend/src/session/useDeck.ts, client.fetchDeck/postVote"
affects: [06-04, 06-05]

# Actuals
actuals:
  tokens: 58000
  tasks: 3
  commits: 6

# Tech tracking
tech-stack:
  added: []
  patterns:
    - "D-05 token propagation: every internal navigate() that lands on another token-gated route must append ?token= explicitly -- the token lives only in the URL, never browser storage, so a bare pathname navigate silently de-authenticates the destination. Established as a real (not hypothetical) failure mode by this plan's checkpoint."
    - "Deck-exhaustion is one effect keyed on cursor >= remaining.length, not two separate code paths -- covers both the post-vote advance and the on-mount already-fully-voted resume case with a single source of truth."
    - "card-stack-slot keys off movie.tmdbId so a card promoted from index 1 to index 0 keeps its DOM node across a re-render; a plain CSS transition on that node's transform is enough to animate the promotion, no framer-motion layout animation needed."

key-files:
  created:
    - frontend/src/swipe/swipeDecision.ts
    - frontend/src/swipe/swipeDecision.test.ts
    - frontend/src/swipe/SwipeCard.tsx
    - frontend/src/swipe/CardStack.tsx
    - frontend/src/swipe/CardStack.test.tsx
    - frontend/src/swipe/swipe.css
    - frontend/src/routes/SwipeScreen.tsx
    - frontend/src/routes/SwipeScreen.test.tsx
    - frontend/src/session/useDeck.ts
  modified:
    - frontend/src/api/client.ts
    - frontend/src/api/types.ts
    - frontend/src/App.tsx

key-decisions:
  - "Task 3's blocking human-verify checkpoint was run against a live ./gradlew bootRun instance with the deck seeded directly via SQL insert into session.pinned_deck (same TMDB-unavailable workaround used in Phase 5), since TMDB_API_TOKEN is not available on this dev machine. Two seeded sessions were created and deleted (participant/vote/session rows) after verification -- no fixture data left in the dev database."
  - "The checkpoint surfaced a real bug, not just feel adjustments: the D-08 exhaustion navigate() to /wait omitted the ?token= query param, so the destination route saw no token and fell back to the join form instead of the welcome-back screen. Fixed by appending token to both the post-vote and on-mount exhaustion navigate calls, and both SwipeScreen.test.tsx exhaustion tests were strengthened with a probe route component that asserts the token round-trips, not just that the pathname changed -- the original tests only checked for destination text and would not have caught this."
  - "An earlier uncommitted fix (found already sitting in the working tree from a prior session, before this plan's own close-out ran) closed a related gap: reopening a link whose bootstrap already covers every deck movie left cursor at 0 with an empty remaining array, falling through to the deck-render branch with nothing in the CardStack. Folded the post-vote and on-mount exhaustion checks into one effect keyed on cursor >= remaining.length so there is a single source of truth, and added a regression test for the on-mount case."
  - "Two feel-level adjustments requested during the human check were implemented before closing the checkpoint: a hover state (background tint + 1px lift) on the like/pass buttons, and a 220ms transform transition on .card-stack-slot so the next card sliding into the top position animates instead of snapping."

patterns-established:
  - "Any navigate() call inside a token-gated route must be reviewed for whether the destination route is also token-gated -- if so, the token must be forwarded explicitly in the query string. This was not caught by unit tests until the tests were changed to assert the token itself, not just the destination pathname/text."

requirements-completed: [RSLT-01]

coverage:
  - id: D1
    description: "A 2-3 card stack renders with the top card fully draggable, tilts and grows a directional tint as it drags, and commits via resolveSwipe's threshold/velocity rule (D-06, D-07)"
    requirement: RSLT-01
    verification:
      - kind: unit
        ref: "frontend/src/swipe/swipeDecision.test.ts (10 cases: distance/velocity thresholds, the 119-vs-120 boundary, opposing-sign velocity, zero/zero)"
        status: pass
      - kind: unit
        ref: "frontend/src/swipe/CardStack.test.tsx (6 cases: stack depth for 5/2/0 remaining, null-poster placeholder, like-button vote path)"
        status: pass
      - kind: human
        ref: "Live walkthrough against a seeded session at /s/{code}/swipe -- stack depth, tilt/tint, commit feel all confirmed pass"
        status: pass
    human_judgment: true
  - id: D2
    description: "The swipe screen fetches the real deck, resumes at the participant's first server-recorded unvoted movie, commits votes through one shared path with server confirmation before advancing, and never fabricates or pads an insufficient_results deck"
    requirement: RSLT-01
    verification:
      - kind: unit
        ref: "frontend/src/routes/SwipeScreen.test.tsx (9 cases: loading state, resume position, insufficient_results branch, stale note, single-POST commit + body assertion, rejected-vote no-advance, exhaustion navigate with token, on-mount already-voted navigate with token, no-token redirect)"
        status: pass
      - kind: integration
        ref: "./gradlew build (full Phase 1-5 backend regression suite, unaffected by this frontend-only plan)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Deck exhaustion (both the post-vote and on-mount already-fully-voted cases) navigates to /wait carrying the session token forward, and the desktop-only like/pass buttons are visible/hoverable at >=768px and hidden below it"
    requirement: RSLT-01
    verification:
      - kind: unit
        ref: "frontend/src/routes/SwipeScreen.test.tsx exhaustion tests, asserting the destination route's own token query param via a probe component"
        status: pass
      - kind: human
        ref: "Live walkthrough: final card lands on the token-authenticated welcome-back screen (not the join form); desktop buttons hover-highlight and disappear below 768px; drag still works at narrow widths"
        status: pass
    human_judgment: true

# Metrics
duration: ~2h (including a paused/resumed session and the live human-verify checkpoint)
completed: 2026-09-09
status: complete
---

# Phase 6 Plan 3: Swipe Deck Summary

**Built the draggable swipe deck end to end -- decision rule, card stack, and screen wiring -- then closed out the plan's blocking human-verify checkpoint, which caught a real token-propagation bug in the exhaustion redirect that no unit test had been asserting against.**

## Performance

- **Duration:** ~2h across two sessions (Tasks 1-2 committed 2026-09-06/07; Task 3 checkpoint run and closed 2026-09-09)
- **Tasks:** 3/3 (2 automated TDD tasks + 1 human-verify checkpoint)
- **Commits:** 6

## Accomplishments

- `resolveSwipe` implements the D-07 commit rule as a pure, independently unit-tested function: distance threshold, velocity+min-intent-distance guard against correction flicks, symmetric for both directions.
- `SwipeCard`/`CardStack` (motion/react drag) render a 2-3 card stack (D-06) with tilt and two directional tints (D-07) derived from one motion value, and desktop-only like/pass buttons (D-09) sharing the identical `onVote` path as the drag gesture.
- `SwipeScreen` wires the stack to the real API: fetches the deck once (`staleTime: Infinity`, since a pinned deck is immutable for the session's life), derives the resume position from the server-issued `votedMovieIds` (never a local record), commits votes through one `commitVote` function used by both the drag and button paths, and never reads the vote response's session-level completion flag (P-02).
- Deck exhaustion is a single effect keyed on `cursor >= remaining.length`, covering both the post-vote advance and a reopened link that was already fully voted on mount -- the latter case was an uncommitted fix already sitting in the working tree at the start of this session, folded into this plan's close-out along with a new regression test.
- The Task 3 human-verify checkpoint was run live against a seeded session (deck seeded directly into `session.pinned_deck` via SQL, bypassing the unavailable TMDB API on this dev machine) and found a real defect: the exhaustion `navigate()` to `/wait` dropped the `?token=` query param, so the destination route fell back to the join form instead of the welcome-back screen. Fixed, and both exhaustion tests were strengthened with a probe route component asserting the token itself round-trips.
- Two feel adjustments requested during the checkpoint were implemented and re-verified: a hover state on the like/pass buttons, and a 220ms transition on the card stack's per-slot transform so promotion to the top card animates instead of snapping.

## Task Commits

1. **Task 1: Swipe decision rule and the draggable card stack**
   - `67ad762` (test, RED) -- failing tests for `swipeDecision`/`CardStack`
   - `28a1722` (feat, GREEN) -- `swipeDecision.ts`, `SwipeCard.tsx`, `CardStack.tsx`, `swipe.css`
2. **Task 2: Swipe screen wiring -- deck fetch, resume, server-confirmed votes, deck exhaustion**
   - `b2a1384` (test, RED) -- failing tests for `SwipeScreen`
   - `6c2bdc6` (feat, GREEN) -- `SwipeScreen.tsx`, `useDeck.ts`, `client.fetchDeck`/`postVote`, App.tsx route
3. **Task 3: Human check -- drag feel, tint feedback, mobile/desktop split** (checkpoint, this close-out)
   - `f9a0e58` (fix) -- on-mount exhaustion redirect for an already-fully-voted reopened link (folded in from a prior uncommitted session), plus a regression test
   - `a61f820` (fix) -- carry the session token forward on the exhaustion redirect (bug found live during the checkpoint), hover state on the action buttons, transform transition on the card stack slot

## Files Created/Modified

- `frontend/src/swipe/swipeDecision.ts` (new) -- commit-rule constants and `resolveSwipe`
- `frontend/src/swipe/SwipeCard.tsx` (new) -- drag/tilt/tint card
- `frontend/src/swipe/CardStack.tsx` (new) -- stack depth, action buttons
- `frontend/src/swipe/swipe.css` (new, later extended) -- layout, 768px breakpoint, button hover, slot transition
- `frontend/src/routes/SwipeScreen.tsx` (new, later fixed) -- deck fetch, resume, commit, exhaustion
- `frontend/src/session/useDeck.ts` (new) -- `useDeck(sessionId, token)`
- `frontend/src/api/client.ts`, `frontend/src/api/types.ts` -- `fetchDeck`, `postVote`, `VoteRequest`
- `frontend/src/App.tsx` -- `/s/:code/swipe` routed to `SwipeScreen`
- `frontend/src/swipe/swipeDecision.test.ts`, `CardStack.test.tsx`, `frontend/src/routes/SwipeScreen.test.tsx` (new/extended)

## Human Verification (Task 3 checkpoint)

Run live against `./gradlew bootRun` with two seeded test sessions (deck rows inserted directly, participant/vote rows deleted after verification -- no fixture data left in the dev database):

1. **Stack depth (D-06):** PASS -- three cards visible, back ones smaller/offset.
2. **Tilt and tint (D-07):** PASS.
3. **Commit feel:** PASS, with a requested adjustment -- smoother/faster transition for the next card sliding into the top slot. Implemented (220ms CSS transition on `.card-stack-slot`'s transform) and re-verified.
4. **Desktop buttons (D-09):** PASS, with a requested adjustment -- buttons had no hover feedback. Implemented (background tint + 1px lift on hover) and re-verified.
5. **Deck exhaustion (D-08):** Initially FAILED -- landed on the join form, not the welcome-back screen, because the exhaustion `navigate()` dropped the token. Fixed and re-verified PASS on a fresh seeded session.
6. **Resume:** Not independently re-tested after the fix (the exhausted-deck-on-reload case is what item 5's retest exercised instead), but is covered by an automated test (`resumes at the first unvoted movie`) that was green throughout.

## Decisions Made

- Ran the checkpoint against directly-seeded `session.pinned_deck` rows rather than a real TMDB fetch, matching the Phase 5 precedent for this same dev-machine gap (no `TMDB_API_TOKEN` available locally).
- Treated the token-propagation bug as a plan-scope fix, not a deferred follow-up, since it directly breaks D-08's "flows straight into the waiting flow" success criterion for any real user with the token-in-URL scheme (D-05) already locked in.
- Clarified with the developer that the placeholder `/wait` destination screen's content (a `JoinScreen`-rendered "Welcome back" message) is 06-04's scope, not a 06-03 defect -- only the token-carrying navigation itself belonged to this plan.

## Deviations from Plan

- The plan's Task 3 verify step expected a single pass/fail per item; item 5 required a fix-then-retest cycle within the checkpoint rather than a single confirmation, and two additional CSS polish changes were made based on checkpoint feedback. Both are within the plan's own stated allowance ("Any requested tuning was applied ... and tests re-run green afterwards").

## Issues Encountered

- The dev machine's port 8080 was already occupied by a stale `bootRun` process from 2026-09-06 (before this session's fixes existed); killed and restarted so the live checkpoint exercised current code.
- Browser automation (claude-in-chrome) was not connected in this session, so the checkpoint's visual/feel confirmation was performed by the developer directly rather than assisted by screenshots.

## User Setup Required

None.

## Next Phase Readiness

- RSLT-01 remains **not yet marked complete** in REQUIREMENTS.md by design -- 06-04 and 06-05 also declare it; the shared-ID gate withholds `Complete` until every declaring plan has a `*-SUMMARY.md`.
- `/s/:code/wait` and `/s/:code/results` still resolve to the `JoinScreen` placeholder -- 06-04 and 06-05 replace them with the real waiting and results screens.
- No blockers for 06-04.

---
*Phase: 06-frontend-spa*
*Completed: 2026-09-09*
