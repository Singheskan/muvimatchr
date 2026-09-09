import { useQuery } from '@tanstack/react-query'
import { fetchGenres } from '../api/client'

// PITFALLS.md: genre/provider reference data is effectively static for weeks -- staleTime:
// Infinity avoids refetching it every time a filters form mounts within the same page load.
export function useGenres(token: string | null) {
  return useQuery({
    queryKey: ['genres'],
    queryFn: () => fetchGenres(token!),
    enabled: Boolean(token),
    staleTime: Infinity,
    retry: false,
  })
}
