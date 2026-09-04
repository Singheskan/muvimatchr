# Phase 4: Vote Recording & Match Aggregation - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-04
**Phase:** 4-Vote Recording & Match Aggregation
**Areas discussed:** Deck stability & filter locking, Abandoned participants, Vote editing

---

## Deck stability & filter locking

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, pin the deck | Snapshot the resolved movie list on first fetch; every participant votes on the same list | ✓ |
| No, always live | Recompute from the shared filter-combo cache on every fetch | |

**User's choice:** Pin the deck.
**Notes:** User's mental model, given directly: configuration (region, providers, genre) is set per session before the deck is created; once the deck exists, nothing in that session can be changed anymore. This resolved a follow-up question about whether filters stay editable post-pin (they don't — full lock) and surfaced that genre is currently a per-request query param, not a session field, which needs to change so it can be locked alongside region/providers. It also fully resolved the separately-flagged "filter changes mid-vote" question (deferred from Phase 3): there is no window after pinning in which filters can change, so the question doesn't arise.

---

## Abandoned participants

| Option | Description | Selected |
|--------|-------------|----------|
| Wait forever | No timeout, no exclusion mechanism | |
| Inactivity timeout | Auto-exclude a participant idle past N since last vote/join | ✓ |
| Manual exclude | Someone in the session can remove a non-responsive participant | |

**User's choice:** Inactivity timeout.
**Notes:** First pass suggested 24h since join or since last vote; user said "way shorter, like if someone did not vote for like a minute they should be kicked." This prompted a clarifying detour on what the actual deck/swipe UI looks like (fixed ~20-card deck, poster/title/genre/providers only, no trailers or synopsis — nothing that invites lingering) — once that was clear, user confirmed 1 minute is not aggressive for this app's use case. Final: timeout is 1 minute since last vote (or since joining, if zero votes cast yet). Follow-up question resolved that idle-out affects **both** the "everyone finished" completion check **and** the unanimity/match requirement (not completion display only) — user confirmed: already-cast votes stay counted, but the idle participant drops out of the "must have liked it" requirement while idle, and re-enters both checks immediately on their next vote (live, not a permanent kick).

---

## Vote editing

**Context:** Raised as a "Done" checkpoint option (not one of the originally-selected areas) after deck stability and abandoned-participants were resolved.

**User's choice:** Not allowed in v1 — no "go back and change a vote" capability.
**Notes:** Initial exploration considered whether a finished participant could revise a vote while waiting on others, and whether that should reopen completion/match state; user's own answer ("or you know what, changing is not allowed") simplified this to: no revise/undo path exists at all in v1. This matches the already-existing `VOTE-06` v2 deferral in REQUIREMENTS.md (undo last swipe) — not a new scope decision, just confirming the existing deferral applies here too. The upsert mechanism (`VoteRepository.upsertVote`) remains as an idempotency safeguard against duplicate submission of the same swipe (e.g. network retry), not a user-facing edit feature.

---

## Claude's Discretion

- Exact storage shape for the pinned deck (movie ID list) and the newly session-scoped genre field.
- Concurrency mechanism for the "has everyone finished" atomic check (optimistic locking vs. transactional count vs. `SELECT ... FOR UPDATE`) — flagged by prior research as the highest-risk logic in this phase; a technical implementation choice, not discussed with the user.
- Exact response shapes/status codes for vote-submission and status endpoints.

## Deferred Ideas

None — discussion stayed within phase scope.
