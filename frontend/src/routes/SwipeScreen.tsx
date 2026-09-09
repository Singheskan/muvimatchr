import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import * as client from '../api/client'
import type { VoteChoice } from '../api/types'
import { useBootstrap } from '../session/useBootstrap'
import { useDeck } from '../session/useDeck'
import { useSessionToken } from '../session/useSessionToken'
import { CardStack } from '../swipe/CardStack'
import '../swipe/swipe.css'

// D-08/resume half of SESH-05, as seen through the SPA. Routing here is presentation only (D-04)
// -- the backend remains the sole access boundary; every branch below is a convenience redirect
// or message, never a security control.
export function SwipeScreen() {
  const { code } = useParams<{ code: string }>()
  const token = useSessionToken()
  const navigate = useNavigate()

  const bootstrap = useBootstrap(code, token)
  const deck = useDeck(bootstrap.data?.sessionId, token)

  const [cursor, setCursor] = useState(0)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!token && code) {
      navigate(`/s/${code}`, { replace: true })
    }
  }, [token, code, navigate])

  // Reading the resume position from the server-issued votedMovieIds -- never from any local
  // record -- is what makes a reopened link resume rather than restart.
  const votedSet = useMemo(() => new Set(bootstrap.data?.votedMovieIds ?? []), [bootstrap.data])
  const remaining = useMemo(
    () => (deck.data ? deck.data.movies.filter((m) => !votedSet.has(m.tmdbId)) : []),
    [deck.data, votedSet],
  )

  // D-08's exhaustion transition, as a single source of truth: this fires both when a reopened
  // link's bootstrap already covers every movie in the deck (cursor 0, remaining already empty --
  // nothing committed this session) and when a vote committed just now advances the cursor to the
  // end of `remaining`. Without this on-mount case, reopening a fully-voted link would fall through
  // to "otherwise render the deck" with an empty CardStack and no redirect -- a bare set of
  // like/pass buttons over nothing to vote on.
  useEffect(() => {
    if (deck.data && deck.data.status === 'ok' && cursor >= remaining.length) {
      // D-05: the token lives only in the URL query string, never browser storage -- every
      // internal navigation must carry it forward explicitly or the destination route sees an
      // unauthenticated load and falls back to the join form.
      navigate(`/s/${code}/wait?token=${encodeURIComponent(token ?? '')}`, { replace: true })
    }
  }, [deck.data, cursor, remaining, navigate, code, token])

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

  if (deck.isLoading || !deck.data) {
    return <p>Loading…</p>
  }

  // D-06: the backend deliberately refuses to substitute content the filter excluded -- this
  // branch renders an explicit explanation and no card stack, never an empty/fabricated deck.
  if (deck.data.status === 'insufficient_results') {
    return (
      <section>
        <h1>Not enough movies matched your filters</h1>
        <p>
          Only {deck.data.totalResults} movies matched the current genre, region and provider
          filters. Try widening one of them.
        </p>
      </section>
    )
  }

  const stack = remaining.slice(cursor)
  const currentMovie = stack[0]

  // The single vote path both the drag gesture and the desktop buttons funnel through --
  // CardStack's onVote calls this exact function either way.
  async function commitVote(choice: VoteChoice) {
    if (pending || !currentMovie || !bootstrap.data || !token) {
      return
    }
    setPending(true)
    try {
      // P-02: the response carries a full VoteStatusResponse, including the session-level
      // completion flag -- this screen deliberately never reads or branches on it. That decision
      // belongs to the waiting route alone.
      await client.postVote(bootstrap.data.sessionId, token, currentMovie.tmdbId, choice)
      setError(null)
      setPending(false)
      // D-08: advancing the cursor to the end of `remaining` here re-renders with
      // `cursor >= remaining.length`, which the exhaustion effect above picks up and turns into
      // the replace-navigate to /wait -- one source of truth for "deck is done, go to wait" shared
      // with the on-mount already-fully-voted case.
      setCursor((current) => current + 1)
    } catch (err) {
      // P-03: never advance the cursor on a failed vote, and never retry automatically -- a
      // silently-dropped vote is worse than a visible error.
      setPending(false)
      if (err instanceof client.ApiError) {
        if (err.status === 409) {
          setError('The deck is not ready yet - reload the page.')
        } else if (err.status === 400) {
          setError("That film is not part of this session's deck.")
        } else if (err.status === 401) {
          setError('Your link is no longer valid.')
        } else {
          setError(err.message)
        }
      } else {
        setError('Something went wrong. Please try again.')
      }
    }
  }

  return (
    <section>
      {deck.data.stale && <p role="status">Showing cached movie data while we refresh in the background.</p>}
      {error && <p role="alert">{error}</p>}
      <CardStack movies={stack} onVote={commitVote} disabled={pending} />
    </section>
  )
}
