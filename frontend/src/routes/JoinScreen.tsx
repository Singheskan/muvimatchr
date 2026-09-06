import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { ApiError, joinSession } from '../api/client'
import { useBootstrap } from '../session/useBootstrap'
import { useSessionToken } from '../session/useSessionToken'

const MAX_DISPLAY_NAME_LENGTH = 100

export function JoinScreen() {
  const { code } = useParams<{ code: string }>()
  const token = useSessionToken()

  if (token && code) {
    return <BootstrappedParticipant code={code} token={token} />
  }

  return <JoinForm code={code} />
}

function BootstrappedParticipant({ code, token }: { code: string; token: string }) {
  const bootstrap = useBootstrap(code, token)

  if (bootstrap.isLoading) {
    return <p>Loading…</p>
  }

  if (bootstrap.isError) {
    const error = bootstrap.error
    if (error instanceof ApiError && (error.status === 401 || error.status === 404)) {
      return <JoinForm code={code} invalidLinkMessage="That link is no longer valid." />
    }
    return <p role="alert">Something went wrong loading your session.</p>
  }

  const data = bootstrap.data!
  return (
    <section>
      <h1>Welcome back, {data.displayName}</h1>
      <p>Session: {data.joinCode}</p>
      <p>{data.deckPinned ? 'Your deck is ready.' : 'Waiting for the deck to be ready.'}</p>
    </section>
  )
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
