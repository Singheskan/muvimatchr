import { useQuery } from '@tanstack/react-query'
import { fetchDeck } from '../api/client'

// staleTime: Infinity is deliberate, not an oversight: once pinned, the deck is immutable for the
// life of the session (Phase 4 D-01), so refetching it can only cost a round trip and can never
// produce different content. The first successful call is also what pins the deck server-side --
// that lazy trigger is the intended behavior, not a side effect to avoid.
export function useDeck(sessionId: string | undefined, token: string | null) {
  return useQuery({
    queryKey: ['deck', sessionId],
    queryFn: () => fetchDeck(sessionId!, token!),
    enabled: Boolean(sessionId && token),
    staleTime: Infinity,
    retry: 1,
  })
}
