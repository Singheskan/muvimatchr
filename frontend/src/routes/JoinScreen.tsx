import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { ApiError, joinSession } from '../api/client'
import { useRouteGuard } from '../routing/useRouteGuard'
import { useBootstrap } from '../session/useBootstrap'
import { useSessionStatus } from '../session/useSessionStatus'
import { useSessionToken } from '../session/useSessionToken'

const MAX_DISPLAY_NAME_LENGTH = 100

export function JoinScreen() {
  const { code } = useParams<{ code: string }>()
  const token = useSessionToken()

  // RSLT-01 made literal: the no-token branch is unchanged -- resolveScreen returns 'join' for a
  // tokenless visitor regardless of session state (flagged assumption A-01), so a stranger holding
  // only a join code is offered the join form, never somebody else's results.
  if (token && code) {
    return <BootstrappedParticipant code={code} token={token} />
  }

  return <JoinForm code={code} />
}

// A settled, valid token never actually stays rendered here -- resolveScreen always resolves a
// bootstrapped participant to 'lobby'/'swipe'/'wait'/'results', never 'join' (see resolveScreen.ts
// D-04 comment), so this component's only real job is to load just enough to know where to send
// the user next. The loading/error states below are what's actually visible, briefly.
function BootstrappedParticipant({ code, token }: { code: string; token: string }) {
  const bootstrap = useBootstrap(code, token)

  // On a bootstrap 401/404, fall back to the no-token form and pass hasToken: false into the
  // guard below -- an invalidated token must never produce a redirect loop between the join
  // route and a session route.
  const isInvalidToken = bootstrap.isError && bootstrap.error instanceof ApiError && (bootstrap.error.status === 401 || bootstrap.error.status === 404)

  const sessionId = bootstrap.data?.sessionId
  const status = useSessionStatus(isInvalidToken ? undefined : sessionId, token)

  // `ready` becomes true only once both the bootstrap and status queries have settled, so a cold
  // load never flashes the join form before forwarding (this is the mechanism RSLT-01 depends on
  // at this route -- the join route is the one a shared or bookmarked resume link actually lands
  // on).
  useRouteGuard(code, 'join', {
    hasToken: !isInvalidToken,
    status: status.data ?? null,
    myVotedCount: bootstrap.data?.votedMovieIds.length ?? 0,
    deckPinned: bootstrap.data?.deckPinned ?? false,
    ready: !isInvalidToken && !bootstrap.isLoading && !status.isLoading,
  })

  if (bootstrap.isLoading) {
    return <p>Loading…</p>
  }

  if (bootstrap.isError) {
    if (isInvalidToken) {
      return <JoinForm code={code} invalidLinkMessage="That link is no longer valid." />
    }
    return <p role="alert">Something went wrong loading your session.</p>
  }

  return <p>Loading…</p>
}

function JoinForm({ code, invalidLinkMessage }: { code: string | undefined; invalidLinkMessage?: string }) {
  const navigate = useNavigate()
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!code) {
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      const result = await joinSession(code, displayName)
      // Never swallow the error and navigate anyway -- only reachable on a genuine success.
      navigate(`/s/${code}?token=${encodeURIComponent(result.token)}`, { replace: true })
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 404) {
          setError('No session with that code.')
        } else if (err.status === 400) {
          setError('Enter a display name.')
        } else {
          setError(err.message)
        }
      } else {
        setError('Something went wrong. Please try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section>
      <h1>Join session {code}</h1>
      {invalidLinkMessage && <p role="alert">{invalidLinkMessage}</p>}
      <form onSubmit={handleSubmit}>
        <label htmlFor="displayName">Display name</label>
        <input
          id="displayName"
          name="displayName"
          type="text"
          maxLength={MAX_DISPLAY_NAME_LENGTH}
          value={displayName}
          onChange={(event) => setDisplayName(event.target.value)}
          required
        />
        <button type="submit" disabled={submitting}>
          Join
        </button>
      </form>
      {error && <p role="alert">{error}</p>}
    </section>
  )
}
