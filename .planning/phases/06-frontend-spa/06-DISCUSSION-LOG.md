# Phase 6: Frontend SPA - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-06
**Phase:** 6-Frontend SPA
**Areas discussed:** Build & deploy integration, Session URL scheme & resume-token handling, Swipe deck interaction & visual feedback, Waiting & results screen content

---

## Build & deploy integration

| Option | Description | Selected |
|--------|-------------|----------|
| Single JAR, static resources | frontend/ builds via Gradle task, dist copied into static resources, one deployable JAR | ✓ |
| Two services, CORS | Frontend deployed separately, calls API cross-origin | |
| Dev-only proxy, deploy separately later | Vite dev proxy now, defer production serving decision | |

**User's choice:** Single JAR, static resources.
**Notes:** None — matched the recommendation and PROJECT.md's stated single small-instance hosting target.

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, delete now | Delete 7 Thymeleaf templates + thymeleaf dependency now | ✓ |
| Keep error.html only | Delete flow templates, keep error.html + thymeleaf dep | |
| Leave all for now | Defer cleanup to end of phase | |

**User's choice:** Yes, delete now.
**Notes:** Matches Phase 5's precedent (D-03 deleted legacy WebSocketConfig/Controller the same way).

| Option | Description | Selected |
|--------|-------------|----------|
| frontend/ at repo root | Vite-scaffolded app sibling to src/ | ✓ |
| You decide | Claude picks location during planning | |

**User's choice:** frontend/ at repo root.

---

## Session URL scheme & resume-token handling

| Option | Description | Selected |
|--------|-------------|----------|
| /s/{joinCode} | Short, typable, uses existing join code | ✓ |
| /session/{sessionId} (UUID) | Matches REST/WS path convention but not typable | |
| No routing, single view | Query params/component state only | |

**User's choice:** /s/{joinCode}.

| Option | Description | Selected |
|--------|-------------|----------|
| React Router, one route per screen | /s/:code, /swipe, /wait, /results, each fetches live status | ✓ |
| Single route, state-driven views | One URL, screen picked by component state | |

**User's choice:** React Router, one route per screen.
**Notes:** User first asked "can users mess with the session when it's client side?" — clarified that the backend is the sole authority for all real state (votes, completion, match result); routing only picks which component renders, and every route re-fetches/validates against the server, redirecting if the user is "ahead" of their real status. No new attack surface either way — same server-side checks apply regardless of URL. User confirmed React Router after this clarification.

| Option | Description | Selected |
|--------|-------------|----------|
| Read once, store in localStorage, scrub URL | Save token to localStorage on first load, remove from URL bar | |
| Keep token in URL permanently | Simpler, token re-read from query string on every load | ✓ |

**User's choice:** Keep token in URL permanently (against Claude's recommendation).
**Notes:** Consistent with the already-accepted Phase 2 risk (02-REVIEW.md flagged the raw token in resumeUrl); user opted for the simpler approach rather than adding localStorage handling.

---

## Swipe deck interaction & visual feedback

| Option | Description | Selected |
|--------|-------------|----------|
| Stacked deck, 2-3 cards visible | Tinder-style depth, top card interactive | ✓ |
| Single card at a time | Simpler, less visually busy | |

**User's choice:** Stacked deck, 2-3 cards visible.

| Option | Description | Selected |
|--------|-------------|----------|
| Color overlay + rotation | Card tilts + green/red tint grows with drag | ✓ |
| Icon badge only | Thumbs icon fades in, no tint/rotation | |

**User's choice:** Color overlay + rotation.

| Option | Description | Selected |
|--------|-------------|----------|
| Auto-transition to waiting screen | Deck exhaustion treated as finishing voting | ✓ |
| Explicit "You're done!" confirmation first | Interstitial before waiting screen | |

**User's choice:** Auto-transition to waiting screen.

| Option | Description | Selected |
|--------|-------------|----------|
| Yes — like/pass buttons alongside drag | Buttons + drag on all devices | (refined, see notes) |
| Drag-only, no buttons | No buttons anywhere | |

**User's choice (after two clarification rounds):** Like/pass buttons shown on desktop/PC viewports only; hidden on mobile, where drag is the sole interaction.
**Notes:** User's original answer was "we need two options for phones and for pcs," which Claude clarified in two follow-up single-select confirmations: (1) confirmed buttons exist alongside drag; (2) confirmed buttons are responsive — hidden below a mobile/tablet breakpoint, shown on desktop. Final answer: mobile is drag-only (uncluttered touch UI), desktop shows buttons (mouse-drag is less natural there).

---

## Waiting & results screen content

| Option | Description | Selected |
|--------|-------------|----------|
| Named/avatar list of who's done | Per-person done/waiting status using display names | ✓ |
| Bare progress count | "2 of 3 finished" with progress bar, no per-person breakdown | |

**User's choice:** Named/avatar list of who's done.

| Option | Description | Selected |
|--------|-------------|----------|
| Simple "no match" message | Plain message in place of match card, no v2 fallback UX | ✓ |
| You decide | Claude picks copy/handling during planning | |

**User's choice:** Simple "no match" message.
**Notes:** Full runner-up/retry fallback (RSLT-05) confirmed as already-deferred v2 scope in REQUIREMENTS.md — this decision only ensures v1 doesn't crash or dead-end on a genuine zero-match outcome.

---

## Claude's Discretion

- Exact Gradle task wiring for the frontend build step (task name/hook point).
- Component/state-management architecture within the SPA (Context vs. prop drilling; TanStack Query usage per STACK.md).
- Exact desktop breakpoint at which like/pass buttons appear.
- Results screen visual layout beyond required content (poster, title, where-to-watch) — phase has `UI hint: yes`, may get a dedicated UI design contract pass.
- Copy/wording for the "no match" message.

## Deferred Ideas

None — discussion stayed within phase scope. Richer "no mutual match" UX (ranked runner-up list, retry/re-vote flow) was confirmed as the already-documented v2 item RSLT-05 in REQUIREMENTS.md, not a new deferred idea raised in this session.
