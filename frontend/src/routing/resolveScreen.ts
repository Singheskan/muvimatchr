import type { VoteStatusResponse } from '../api/types'

// D-04: the entire redirect mechanism behind RSLT-01. Routing is presentation only, never an
// access boundary (06-CONTEXT.md D-04) -- every screen's data still comes from token-authenticated
// endpoints that enforce membership server-side.
//
// 'lobby' closes a gap found live-testing the "finished" phase: D-04's own routing comment always
// called /s/:code the "(join/lobby)" route, but no code ever actually kept a settled, bootstrapped
// participant there -- resolveScreen fell straight through to 'swipe' the instant a token existed,
// so a fresh session's share link and filters (SESH-01/CTLG-02/CTLG-03) were unreachable in
// practice. A participant now stays in the lobby until the deck is pinned (session-wide, by
// anyone's "Start swiping" -- there is no host role, D-02), matching the same async,
// no-synchronization-required model every other shared action in this app already uses.
export type Screen = 'join' | 'lobby' | 'swipe' | 'wait' | 'results'

// Prohibition P-02: completion is read from the server's isComplete flag alone, never derived from
// the participant-count fields, roster flags, or a local vote tally.
export function resolveScreen({
  hasToken,
  status,
  myVotedCount,
  deckPinned,
}: {
  hasToken: boolean
  status: VoteStatusResponse | null
  myVotedCount: number
  deckPinned: boolean
}): Screen {
  if (!hasToken) {
    return 'join'
  }
  if (status === null) {
    return 'join'
  }
  if (status.isComplete) {
    return 'results'
  }
  // The deckSize > 0 conjunct mirrors MatchAggregationService.computeStatus's own guard: a
  // never-pinned session must not read as finished.
  if (status.deckSize > 0 && myVotedCount >= status.deckSize) {
    return 'wait'
  }
  if (!deckPinned) {
    return 'lobby'
  }
  return 'swipe'
}
