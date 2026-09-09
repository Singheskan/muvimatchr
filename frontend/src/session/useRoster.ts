import { useQuery } from '@tanstack/react-query'
import { fetchRoster } from '../api/client'

// D-10's named waiting roster. Same REST-first shape as useSessionStatus -- no polling interval,
// the socket (Task 2) invalidates this query key on every frame and every reconnect.
export function useRoster(sessionId: string | undefined, token: string | null) {
  return useQuery({
    queryKey: ['roster', sessionId],
    queryFn: () => fetchRoster(sessionId!, token!),
    enabled: Boolean(sessionId && token),
    retry: false,
  })
}
