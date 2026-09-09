import type { VoteStatusResponse } from '../api/types'

// D-04: the entire redirect mechanism behind RSLT-01. Routing is presentation only, never an
// access boundary (06-CONTEXT.md D-04) -- every screen's data still comes from token-authenticated
// endpoints that enforce membership server-side.
export type Screen = 'join' | 'swipe' | 'wait' | 'results'

// Prohibition P-02: completion is read from the server's isComplete flag alone, never derived from
// the participant-count fields, roster flags, or a local vote tally.
export function resolveScreen({
  hasToken,
  status,
  myVotedCount,
}: {
  hasToken: boolean
  status: VoteStatusResponse | null
  myVotedCount: number
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
  return 'swipe'
}
