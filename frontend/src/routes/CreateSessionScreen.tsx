import { useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError, createSession, joinSession } from '../api/client'

const MAX_DISPLAY_NAME_LENGTH = 100

// D-04's route table never allocated a create-session screen -- only /s/:code and its children --
// so the root route was left as static text with no way to actually start a session through the
// SPA, even though the backend has supported POST /api/sessions since Phase 2. This closes that
// gap: create a blank session, then join it exactly like anyone else opening the resulting link,
// so there is still no host-role concept anywhere (SessionController D-02).
export function CreateSessionScreen() {
  const navigate = useNavigate()
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      const session = await createSession()
      const result = await joinSession(session.joinCode, displayName)
      // Never swallow either error and navigate anyway -- only reachable on genuine success of
      // both calls.
      navigate(`/s/${session.joinCode}?token=${encodeURIComponent(result.token)}`, { replace: true })
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 400) {
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
      <h1>MuviMatchr</h1>
      <p>Start a new session and share the link, or open a session link you were given to join one.</p>
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
          Start a session
        </button>
      </form>
      {error && <p role="alert">{error}</p>}
    </section>
  )
}
