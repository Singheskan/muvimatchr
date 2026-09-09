import { useQuery } from '@tanstack/react-query'
import { fetchRoster } from '../api/client'

// D-10's named waiting roster. Same REST-first shape as useSessionStatus -- no polling interval
// by default, the socket (WaitScreen) invalidates this query key on every frame and every
// reconnect. `refetchIntervalMs` is an explicit opt-in for screens with no WS coverage of their
// own event (LobbyScreen: a participant joining doesn't broadcast anything -- there is no
// server-side event for it to hook, unlike votes/status).
export function useRoster(sessionId: string | undefined, token: string | null, refetchIntervalMs?: number) {
  return useQuery({
    queryKey: ['roster', sessionId],
    queryFn: () => fetchRoster(sessionId!, token!),
    enabled: Boolean(sessionId && token),
    retry: false,
    refetchInterval: refetchIntervalMs,
  })
}
