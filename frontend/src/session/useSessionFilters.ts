import { useQuery } from '@tanstack/react-query'
import { fetchSessionFilters } from '../api/client'

export function useSessionFilters(sessionId: string | undefined, token: string | null) {
  return useQuery({
    queryKey: ['filters', sessionId],
    queryFn: () => fetchSessionFilters(sessionId!, token!),
    enabled: Boolean(sessionId && token),
    retry: false,
  })
}
