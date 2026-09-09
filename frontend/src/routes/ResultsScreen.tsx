import { useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { useParams } from 'react-router'
import * as client from '../api/client'
import { pickBestMatch as selectBestMatch } from '../results/pickBestMatch'
import '../results/results.css'
import { useSessionSocket } from '../realtime/useSessionSocket'
import { useRouteGuard } from '../routing/useRouteGuard'
import { useBootstrap } from '../session/useBootstrap'
import { useDeck } from '../session/useDeck'
import { useSessionStatus } from '../session/useSessionStatus'
import { useSessionToken } from '../session/useSessionToken'
import { TMDB_IMAGE_BASE } from '../swipe/SwipeCard'

// The screen the entire application exists to produce (RSLT-02, D-11). Routing here is
// presentation only (D-04) -- reaching this URL early bounces the participant to the screen they
// belong on, and every underlying fetch remains membership-gated server-side regardless.
export function ResultsScreen() {
  const { code } = useParams<{ code: string }>()
  const token = useSessionToken()
  const queryClient = useQueryClient()

  const bootstrap = useBootstrap(code, token)
  const sessionId = bootstrap.data?.sessionId
  const status = useSessionStatus(sessionId, token)
  const deck = useDeck(sessionId, token)

  // Same reconnect-reconcile contract WaitScreen implements: a late joiner reopening voting after
  // this participant already reached results must move this screen off results automatically, so
  // the socket signal invalidates the two query keys resolveScreen depends on (P-04 -- the frame
  // body itself is never applied directly).
  const onFrame = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ['status', sessionId] })
    queryClient.invalidateQueries({ queryKey: ['bootstrap', code, token] })
  }, [queryClient, sessionId, code, token])

  useSessionSocket(sessionId, onFrame)

  useRouteGuard(code, 'results', {
    hasToken: Boolean(token),
    status: status.data ?? null,
    myVotedCount: bootstrap.data?.votedMovieIds.length ?? 0,
    deckPinned: bootstrap.data?.deckPinned ?? false,
    ready: !bootstrap.isLoading && !status.isLoading,
  })

  if (!token) {
    return null
  }

  if (bootstrap.isLoading) {
    return <p>Loading…</p>
  }

  if (bootstrap.isError) {
    const err = bootstrap.error
    const message = err instanceof client.ApiError ? err.message : 'Something went wrong loading your session.'
    return (
      <section>
        <p role="alert">{message}</p>
        <a href={`/s/${code}`}>Back to join</a>
      </section>
    )
  }

  if (status.isLoading || deck.isLoading || !status.data || !deck.data) {
    return <p>Loading…</p>
  }

  // The single audited selection call site (prohibition P-01) -- no ranking or reduction logic
  // lives in this component. Imported under a local alias so this remains the file's only
  // reference to the selection function, distinct from the import line itself.
  const best = selectBestMatch({
    matchedMovieIds: status.data.matchedMovieIds,
    likeCounts: status.data.likeCounts,
    movies: deck.data.movies,
  })

  // D-11: the zero-match case. No card, no ranked list, no runner-up, no retry action, no
  // like-count table -- that richer fallback is RSLT-05, explicitly v2.
  if (!best) {
    return (
      <section className="results-no-match">
        <h1>No match</h1>
        <p>There was no film everyone liked. Nothing from this deck works for the whole group.</p>
      </section>
    )
  }

  const releaseYear = best.releaseDate ? best.releaseDate.slice(0, 4) : null
  const hasAvailability = best.providers.length > 0 || best.watchLink !== null

  return (
    <section className="results-match">
      {best.posterPath ? (
        <img
          className="results-poster"
          src={`${TMDB_IMAGE_BASE}${best.posterPath}`}
          alt={best.title}
        />
      ) : (
        <div className="results-poster results-poster-placeholder">
          <span>{best.title}</span>
        </div>
      )}
      <h1>
        {best.title}
        {releaseYear && <span className="results-year"> ({releaseYear})</span>}
      </h1>
      <div className="results-availability">
        <h2>Where to watch</h2>
        {best.providers.length > 0 && (
          <ul className="results-providers">
            {best.providers.map((provider) => (
              <li key={provider.providerId}>
                {provider.logoPath && (
                  <img
                    className="results-provider-logo"
                    src={`${TMDB_IMAGE_BASE}${provider.logoPath}`}
                    alt=""
                  />
                )}
                <span>{provider.providerName}</span>
              </li>
            ))}
          </ul>
        )}
        {best.watchLink && (
          <a className="results-watch-link" href={best.watchLink} target="_blank" rel="noopener noreferrer">
            Watch now
          </a>
        )}
        {/* RSLT-02 names where-to-watch as a required element -- stated as unknown, never dropped. */}
        {!hasAvailability && <p>No streaming availability is listed for this title in your region.</p>}
      </div>
      {best.overview && <p className="results-overview">{best.overview}</p>}
    </section>
  )
}
