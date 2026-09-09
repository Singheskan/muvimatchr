import { useEffect } from 'react'
import { useLocation, useNavigate } from 'react-router'
import type { VoteStatusResponse } from '../api/types'
import { resolveScreen, type Screen } from './resolveScreen'

// D-04's redirect rule: every session route resolves its screen from server-computed status and
// redirects the user there, without dropping the token (D-05) or flashing during load.
export function useRouteGuard(
  code: string | undefined,
  currentScreen: Screen,
  input: {
    hasToken: boolean
    status: VoteStatusResponse | null
    myVotedCount: number
    ready: boolean
  },
) {
  const navigate = useNavigate()
  const location = useLocation()
  const { ready } = input
  const resolved = resolveScreen(input)

  useEffect(() => {
    // The `ready` guard exists so no screen redirects while its own data is still loading --
    // without it every route would flash through 'join' on first paint, because status starts
    // null. Not ready is a no-op for this effect, not a fallthrough to 'join'.
    if (!ready || !code) {
      return
    }
    if (resolved === currentScreen) {
      return
    }
    const path = resolved === 'join' ? `/s/${code}` : `/s/${code}/${resolved}`
    // D-05: the participant token lives only in the URL query string -- preserving it here is
    // load-bearing, a redirect that drops it logs the user out mid-flow.
    navigate({ pathname: path, search: location.search }, { replace: true })
    // Deliberately not depending on the whole status object or location.search: only the
    // resolved screen, currentScreen, code and ready should re-fire this effect, so a status
    // refetch that changes nothing does not re-trigger a navigation.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resolved, currentScreen, code, ready])
}
