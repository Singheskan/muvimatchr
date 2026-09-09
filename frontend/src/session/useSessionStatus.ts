import { useQuery } from '@tanstack/react-query'
import { fetchStatus } from '../api/client'

// REST-first (Phase 5 D-04/Pattern 4): the sole source resolveScreen reads status.isComplete
// from. No polling interval -- live updates arrive through the socket (Task 2), which invalidates
// this query key on every frame and every reconnect; a poll on top of that would just duplicate
// work.
export function useSessionStatus(sessionId: string | undefined, token: string | null) {
  return useQuery({
    queryKey: ['status', sessionId],
    queryFn: () => fetchStatus(sessionId!, token!),
    enabled: Boolean(sessionId && token),
    retry: false,
  })
}
