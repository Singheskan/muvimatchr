# Requirements: MuviMatchr

**Defined:** 2026-09-01
**Core Value:** Two (or more) people with different tastes can independently pick movies they'd watch and get a fast, confident answer to "what do we actually both want to watch tonight" — without the back-and-forth debate.

## v1 Requirements

Requirements for initial release. Each maps to roadmap phases.

### Session & Identity

- [x] **SESH-01**: Host can create a session and get a unique shareable join code/link
- [x] **SESH-02**: Participant joins via code/link by picking a display name — no account required
- [x] **SESH-03**: Each participant gets a server-issued unguessable token for the session (not just their display name), so votes can't be spoofed via a guessed code
- [x] **SESH-04**: A session supports 2+ participants
- [x] **SESH-05**: A participant can leave and resume the same session later via their retained link/token without losing votes

### Movie Catalog

- [x] **CTLG-01**: Deck is sourced from TMDB (titles, posters, genres, streaming providers)
- [x] **CTLG-02**: Participant can filter the deck by genre
- [x] **CTLG-03**: Participant can filter the deck by streaming availability (region-aware)
- [x] **CTLG-04**: TMDB responses are cached server-side (not re-fetched per deck load)
- [ ] **CTLG-05**: TMDB API key never reaches the frontend (backend-proxied)

### Voting & Matching

- [ ] **VOTE-01**: Participant swipes right (like) / left (pass) on each movie
- [ ] **VOTE-02**: Every vote is persisted immediately (survives disconnect/refresh/restart)
- [ ] **VOTE-03**: Re-voting on a movie updates the existing vote rather than duplicating it
- [ ] **VOTE-04**: A movie is a "match" only if every currently-joined participant liked it (unanimous)
- [ ] **VOTE-05**: "Has everyone finished?" is computed live against currently-joined participants, not a snapshot from session start

### Real-Time Status

- [ ] **RTIME-01**: A participant who finishes early sees a live "waiting on N of M" screen that updates as others finish
- [ ] **RTIME-02**: When the last person finishes, connected participants auto-transition to results
- [ ] **RTIME-03**: A reconnecting participant always sees correct current status (reconciled via the server, never stale)

### Results & Reliability

- [ ] **RSLT-01**: Anyone opening the link after everyone's done sees results immediately, even if they weren't connected when voting finished
- [ ] **RSLT-02**: Results screen shows the single best mutual match (poster, title, where to watch)
- [ ] **RSLT-03**: Vote data is stored per-movie (counts, not just a boolean) so a ranked list can be added later without a data model change
- [ ] **RELI-01**: Session/participant/vote data lives in a real database and survives a server restart

## v2 Requirements

Deferred to future release. Tracked but not in current roadmap.

### Results

- **RSLT-04**: Ranked list of all mutual matches shown alongside the top pick
- **RSLT-05**: Explicit "no unanimous match" fallback (e.g. show the best runner-up by vote count)

### Catalog

- **CTLG-06**: Additional filters — runtime, release year, minimum rating

### Voting

- **VOTE-06**: Undo last swipe
- **VOTE-07**: Majority/threshold matching for groups larger than 2 (instead of strict unanimity)

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Real user accounts / auth (email+password, OAuth) | Code/link + display name is enough for a casual watch-party tool; revisit only if this becomes a product people return to across many separate sessions |
| True-simultaneous real-time swiping (synced live decks) | Async is the target UX, not simultaneous play |
| ML-based recommendations / taste modeling | No data or need for this at v1 scale |
| Multi-instance / multi-region WebSocket fan-out | Single-instance small deployment target; no scaling need |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| SESH-01 | Phase 2 | Complete |
| SESH-02 | Phase 2 | Complete |
| SESH-03 | Phase 2 | Complete |
| SESH-04 | Phase 2 | Complete |
| SESH-05 | Phase 2 | Complete |
| CTLG-01 | Phase 3 | Complete |
| CTLG-02 | Phase 3 | Complete |
| CTLG-03 | Phase 3 | Complete |
| CTLG-04 | Phase 3 | Complete |
| CTLG-05 | Phase 3 | Pending |
| VOTE-01 | Phase 4 | Pending |
| VOTE-02 | Phase 4 | Pending |
| VOTE-03 | Phase 4 | Pending |
| VOTE-04 | Phase 4 | Pending |
| VOTE-05 | Phase 4 | Pending |
| RTIME-01 | Phase 5 | Pending |
| RTIME-02 | Phase 5 | Pending |
| RTIME-03 | Phase 5 | Pending |
| RSLT-01 | Phase 6 | Pending |
| RSLT-02 | Phase 6 | Pending |
| RSLT-03 | Phase 4 | Pending |
| RELI-01 | Phase 1 | Pending |

**Coverage:**

- v1 requirements: 22 total
- Mapped to phases: 22
- Unmapped: 0 ✓ (all v1 requirements mapped to a phase)

---
*Requirements defined: 2026-09-01*
*Last updated: 2026-09-01 after roadmap creation (traceability mapped)*
