import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { ApiError, fetchDeck } from '../api/client'
import { SessionFiltersForm } from '../filters/SessionFiltersForm'
import { useRouteGuard } from '../routing/useRouteGuard'
import { useBootstrap } from '../session/useBootstrap'
import { useRoster } from '../session/useRoster'
import { useSessionStatus } from '../session/useSessionStatus'
import { useSessionToken } from '../session/useSessionToken'
import './lobby.css'

// Joining doesn't broadcast anything over the socket (unlike votes/status) -- there is no
// server-side event to hook, so the roster polls on a short interval only on this screen.
const ROSTER_POLL_MS = 4000

// D-04's own routing comment always called /s/:code the "(join/lobby)" route, but no code ever
// actually kept a settled, bootstrapped participant there long enough to see it -- resolveScreen
// fell straight through to 'swipe' the instant a token existed. This screen is what that comment
// meant: a real stop where a fresh session's share link (SESH-01), roster (D-10 rendered ahead of
// the vote), and filters (CTLG-02/CTLG-03) are actually reachable, before the deck gets pinned.
// There is no host role (SessionController D-02) -- any participant may share the link, edit
// filters, or click "Start swiping"; the deck pins session-wide for whoever does it first, exactly
// like every other shared action here.
export function LobbyScreen() {
  const { code } = useParams<{ code: string }>()
  const token = useSessionToken()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const bootstrap = useBootstrap(code, token)
  const sessionId = bootstrap.data?.sessionId
  const status = useSessionStatus(sessionId, token)
  const roster = useRoster(sessionId, token, ROSTER_POLL_MS)

  useRouteGuard(code, 'lobby', {
    hasToken: Boolean(token),
    status: status.data ?? null,
    myVotedCount: bootstrap.data?.votedMovieIds.length ?? 0,
    deckPinned: bootstrap.data?.deckPinned ?? false,
    ready: !bootstrap.isLoading && !status.isLoading,
  })

  const [starting, setStarting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleStartSwiping() {
    if (!sessionId || !token || !code) {
      return
    }
    setStarting(true)
    setError(null)
    try {
      // The first successful call to this endpoint is what pins the deck server-side (Phase 4
      // D-01) -- idempotent if someone else already pinned it while this participant sat here.
      await fetchDeck(sessionId, token)
      // Without this, SwipeScreen mounts and reads the *same* cached bootstrap/status query keys
      // this component holds -- still showing deckPinned: false -- and its own route guard
      // immediately bounces back to /lobby. Awaiting the invalidation (not just firing it) is
      // what guarantees fresh data is in the cache before the navigate below.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['bootstrap', code, token] }),
        queryClient.invalidateQueries({ queryKey: ['status', sessionId] }),
      ])
      navigate(`/s/${code}/swipe?token=${encodeURIComponent(token)}`, { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Something went wrong. Please try again.')
      setStarting(false)
    }
  }

  if (!token) {
    return null
  }

  if (bootstrap.isLoading || !bootstrap.data) {
    return <p role="status">Loading…</p>
  }

  const data = bootstrap.data

  return (
    <section className="lobby">
      <p className="lobby-eyebrow">Session {data.joinCode}</p>
      <h1>Welcome, {data.displayName}</h1>

      <ShareLink joinCode={data.joinCode} />

      <div className="lobby-roster">
        <h2>Who&rsquo;s here</h2>
        {roster.isLoading || !roster.data ? (
          <p role="status">Loading…</p>
        ) : (
          <ul>
            {roster.data.participants.map((participant) => (
              <li key={participant.participantId}>{participant.displayName}</li>
            ))}
          </ul>
        )}
      </div>

      <SessionFiltersForm sessionId={sessionId!} token={token} />

      <div className="lobby-start">
        <button type="button" onClick={handleStartSwiping} disabled={starting} className="btn-primary">
          {starting ? 'Starting…' : 'Start swiping'}
        </button>
        <p className="lobby-start-note">Anyone can start. The whole group gets the same picks.</p>
        {error && <p role="alert">{error}</p>}
      </div>
    </section>
  )
}

// SESH-01: a real shareable link, not just a code shown as inert text. Built from the join code
// alone -- never the viewer's own bearer token (D-05/T-06-11) -- so a second person opening it
// lands on the join form and gets their own identity, never silently authenticated as whoever
// shared the link.
function ShareLink({ joinCode }: { joinCode: string }) {
  const url = `${window.location.origin}/s/${joinCode}`
  const [copied, setCopied] = useState(false)

  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(url)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="share-link">
      <label htmlFor="share-link">Share this link so others can join</label>
      <div className="share-link-row">
        <input id="share-link" type="text" readOnly value={url} onFocus={(event) => event.target.select()} />
        <button type="button" onClick={handleCopy} className="share-link-copy">
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
    </div>
  )
}
