# Phase 2: Session & Lobby Flow - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-02
**Phase:** 2-Session & Lobby Flow
**Areas discussed:** Join code/link, Resume mechanism, Host role, Late joiners, Name collisions

---

## Join code/link

| Option | Description | Selected |
|--------|-------------|----------|
| Short typable code only | Host shares a code, participant enters it plus a display name. Simple, matches the `joinCode` field already built in Phase 1. | ✓ |
| Full shareable URL | e.g. muvimatchr.app/join/ABC123 — one tap from a shared link, no typing needed. | |
| Both, host's choice | Show the code prominently but also generate a copyable full URL. | |

**User's choice:** Short typable code only
**Notes:** None

---

## Resume mechanism

| Option | Description | Selected |
|--------|-------------|----------|
| Token embedded in a personal link | Each participant gets their own bookmarkable URL (session+token). Works across devices as long as saved/bookmarked. | ✓ |
| Browser-persisted only | Token in localStorage/cookie on the joining device. Zero friction same-device, breaks on device switch/storage clear. | |
| Both | Persist in-browser for convenience, plus a copyable personal resume link as fallback. | |

**User's choice:** Token embedded in a personal link
**Notes:** None

---

## Host role

| Option | Description | Selected |
|--------|-------------|----------|
| No special role | Host is just the first participant — no host-only actions. | ✓ |
| Host has a marker/flag | Track session creator even without granting powers, for future-proofing. | |

**User's choice:** No special role
**Notes:** None

---

## Late joiners

| Option | Description | Selected |
|--------|-------------|----------|
| Yes, join anytime | No cutoff — matches async voting model. | ✓ |
| No, session locks after first vote | Simpler "who counts as everyone" logic for Phase 4's match calculation, less flexible. | |

**User's choice:** Yes, join anytime
**Notes:** None

---

## Name collisions

| Option | Description | Selected |
|--------|-------------|----------|
| Allow duplicates | No uniqueness check — each participant already distinguished by token/ID. | ✓ |
| Reject duplicates within a session | Force a different name if already taken in that session. | |

**User's choice:** Allow duplicates
**Notes:** None

---

## Claude's Discretion

- Exact join-code generation strategy (charset, length, collision-retry approach)
- Token format and storage (hashed vs plaintext in DB)
- Exact resume-link URL shape

## Deferred Ideas

None — discussion stayed within phase scope. Host-only privileges were explicitly considered (see Host role above) and declined for this phase rather than deferred as a concrete future item.
