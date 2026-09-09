import { useQuery } from '@tanstack/react-query'
import { fetchBootstrap } from '../api/client'

// REST-first, WS-as-signal (Phase 5 D-04/Pattern 4): this hook is the SPA's REST fetch of current
// truth for "who am I in this session" -- always re-fetched on mount, never trusted from a cached
// or pushed value alone. No polling interval by default, same reasoning as useRoster:
// deck-pinning (unlike votes/status) broadcasts nothing over the socket (D-05 restricts
// broadcastStatus to VoteService.recordVote() alone), so `refetchIntervalMs` is an explicit
// opt-in for screens -- LobbyScreen -- that need to notice deckPinned flipping without a push.
export function useBootstrap(joinCode: string | undefined, token: string | null, refetchIntervalMs?: number) {
  return useQuery({
    queryKey: ['bootstrap', joinCode, token],
    queryFn: () => fetchBootstrap(joinCode!, token!),
    enabled: Boolean(joinCode && token),
    retry: false,
    refetchInterval: refetchIntervalMs,
  })
}
