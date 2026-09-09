import { useQuery } from '@tanstack/react-query'
import { fetchWatchProviders } from '../api/client'

// Provider availability is region-specific (PITFALLS.md) -- keyed on region so switching region
// in the filters form refetches rather than showing a stale list from a different country.
export function useWatchProviders(region: string, token: string | null) {
  return useQuery({
    queryKey: ['watchProviders', region],
    queryFn: () => fetchWatchProviders(region, token!),
    enabled: Boolean(token) && /^[A-Z]{2}$/.test(region),
    staleTime: Infinity,
    retry: false,
  })
}
