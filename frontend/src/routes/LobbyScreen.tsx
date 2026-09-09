import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { ApiError, fetchDeck, updateSessionFilters } from '../api/client'
import { SessionFiltersForm } from '../filters/SessionFiltersForm'
import { useRouteGuard } from '../routing/useRouteGuard'
import { useBootstrap } from '../session/useBootstrap'
import { useRoster } from '../session/useRoster'
import { useSessionFilters } from '../session/useSessionFilters'
import { useSessionStatus } from '../session/useSessionStatus'
import { useSessionToken } from '../session/useSessionToken'
import './lobby.css'

// Joining doesn't broadcast anything over the socket (unlike votes/status) -- there is no
// server-side event to hook, so the roster polls on a short interval only on this screen.
const ROSTER_POLL_MS = 4000

// Auto-saves shortly after the last edit so filters never depend on a separate, easy-to-forget
// "Save" click -- found live: a provider toggled but not explicitly saved before "Start swiping"
// silently pinned the deck with the *old* filters, so the selection had no effect at all.
const FILTERS_AUTOSAVE_DEBOUNCE_MS = 500

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

  // Filters live here, not inside SessionFiltersForm, specifically so "Start swiping" can flush
  // the current values with one direct, awaited call instead of racing a debounce timer it has no
  // way to reach into a child component and cancel/complete early.
  const filters = useSessionFilters(sessionId, token)
  const [region, setRegion] = useState('')
  const [genre, setGenre] = useState<number | ''>('')
  const [providerIds, setProviderIds] = useState<number[]>([])
  const [filtersInitialized, setFiltersInitialized] = useState(false)
  const [filtersSaving, setFiltersSaving] = useState(false)
  const [filtersSaved, setFiltersSaved] = useState(false)
  const [filtersError, setFiltersError] = useState<string | null>(null)

  useEffect(() => {
    if (filters.data && !filtersInitialized) {
      setRegion(filters.data.region)
      setGenre(filters.data.genre ?? '')
      setProviderIds(filters.data.providerIds)
      setFiltersInitialized(true)
    }
  }, [filters.data, filtersInitialized])

  const saveFilters = useCallback(async () => {
    if (!sessionId || !token) {
      return
    }
    setFiltersSaving(true)
    setFiltersSaved(false)
    setFiltersError(null)
    try {
      await updateSessionFilters(sessionId, token, {
        region,
        genre: genre === '' ? null : genre,
        providerIds,
      })
      setFiltersSaved(true)
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setFiltersError('Filters are locked -- the deck has already been pinned.')
      } else {
        setFiltersError(err instanceof ApiError ? err.message : 'Could not save filters.')
      }
      throw err
    } finally {
      setFiltersSaving(false)
    }
  }, [sessionId, token, region, genre, providerIds])

  // Auto-save shortly after the participant stops editing -- not on every keystroke/click.
  useEffect(() => {
    if (!filtersInitialized) {
      return
    }
    const timeout = setTimeout(() => {
      saveFilters().catch(() => {
        // Surfaced via filtersError already; nothing further to do on an autosave failure.
      })
    }, FILTERS_AUTOSAVE_DEBOUNCE_MS)
    return () => clearTimeout(timeout)
    // Deliberately excludes saveFilters from deps: it's recreated every render (region/genre/
    // providerIds change every edit), and including it would re-arm the same debounce redundantly
    // on the exact renders this effect already re-runs for.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [region, genre, providerIds, filtersInitialized])

  function toggleProvider(id: number) {
    setProviderIds((current) => {
      if (current.includes(id)) {
        return current.filter((p) => p !== id)
      }
      return [...current, id]
    })
  }

  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState<string | null>(null)

  async function handleStartSwiping() {
    if (!sessionId || !token || !code) {
      return
    }
    setStarting(true)
    setStartError(null)
    try {
      // Flush whatever is currently selected, awaited, before pinning -- this is what actually
      // fixes the bug: without it, a change made in the last FILTERS_AUTOSAVE_DEBOUNCE_MS could
      // still be in flight (or not yet fired at all) when the deck gets pinned below.
      await saveFilters()
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
      // A failed saveFilters() already set filtersError with the specific reason -- this generic
      // message only covers the fetchDeck/invalidate path.
      if (!(err instanceof ApiError)) {
        setStartError('Something went wrong. Please try again.')
      }
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

      {filtersInitialized && (
        <SessionFiltersForm
          token={token}
          region={region}
          genre={genre}
          providerIds={providerIds}
          onRegionChange={setRegion}
          onGenreChange={setGenre}
          onToggleProvider={toggleProvider}
          saving={filtersSaving}
          saved={filtersSaved}
          error={filtersError}
        />
      )}

      <div className="lobby-start">
        <button type="button" onClick={handleStartSwiping} disabled={starting} className="btn-primary">
          {starting ? 'Starting…' : 'Start swiping'}
        </button>
        <p className="lobby-start-note">Anyone can start. The whole group gets the same picks.</p>
        {startError && <p role="alert">{startError}</p>}
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
