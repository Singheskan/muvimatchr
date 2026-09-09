import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DeckResponse } from '../api/types'
import { SwipeScreen } from './SwipeScreen'

// D-05: the token lives only in the URL query string -- this probe proves a navigation actually
// carried it forward, not just that the pathname changed.
function WaitScreenProbe() {
  const [searchParams] = useSearchParams()
  return <p>waiting screen (token={searchParams.get('token')})</p>
}

const SESSION_ID = 'session-1'
const JOIN_CODE = 'ABC123'
const TOKEN = 'tok-123'

function bootstrapResponse(votedMovieIds: number[] = []) {
  return {
    sessionId: SESSION_ID,
    joinCode: JOIN_CODE,
    participantId: 'p1',
    displayName: 'Alice',
    deckPinned: true,
    votedMovieIds,
  }
}

function deckResponse(overrides: Partial<DeckResponse> = {}): DeckResponse {
  return { ...baseDeck(), ...overrides }
}

function baseDeck(): DeckResponse {
  return {
    sessionId: SESSION_ID,
    status: 'ok',
    stale: false,
    fetchedAt: '2026-01-01T00:00:00Z',
    totalResults: 4,
    movies: [10, 20, 30, 40].map((id) => ({
      tmdbId: id,
      title: `Movie ${id}`,
      posterPath: null,
      genreIds: [],
      voteAverage: 7,
      releaseDate: '2020-01-01',
      overview: null,
      providers: [],
      watchLink: null,
    })),
  }
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}

function renderSwipeScreen(initialPath: string) {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/s/:code" element={<p>join screen</p>} />
          <Route path="/s/:code/swipe" element={<SwipeScreen />} />
          <Route path="/s/:code/wait" element={<WaitScreenProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('SwipeScreen', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function mockFetch(options: {
    votedMovieIds?: number[]
    deck?: DeckResponse
    voteResult?: 'ok' | { status: number; body: string }
  }) {
    const mockFetchFn = fetch as unknown as ReturnType<typeof vi.fn>
    mockFetchFn.mockImplementation(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      if (url.includes('/me')) {
        return jsonResponse(bootstrapResponse(options.votedMovieIds ?? []))
      }
      if (url.endsWith('/deck')) {
        return jsonResponse(options.deck ?? deckResponse())
      }
      if (url.endsWith('/votes/status')) {
        return jsonResponse({
          sessionId: SESSION_ID,
          deckSize: 4,
          activeCount: 1,
          finishedCount: 0,
          isComplete: false,
          matchedMovieIds: [],
          likeCounts: [],
        })
      }
      if (url.endsWith('/votes') && method === 'POST') {
        if (options.voteResult && options.voteResult !== 'ok') {
          return new Response(options.voteResult.body, { status: options.voteResult.status })
        }
        return jsonResponse({
          sessionId: SESSION_ID,
          deckSize: 4,
          activeCount: 1,
          finishedCount: 0,
          isComplete: false,
          matchedMovieIds: [],
          likeCounts: [],
        })
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    })
    return mockFetchFn
  }

  it('renders a loading state while bootstrap/deck are in flight, then the deck', async () => {
    mockFetch({})
    renderSwipeScreen(`/s/${JOIN_CODE}/swipe?token=${TOKEN}`)

    expect(screen.getByText(/loading/i)).toBeInTheDocument()

    await waitFor(() => {
      expect(screen.getByText('Movie 10')).toBeInTheDocument()
    })
  })

  it('resumes at the first unvoted movie', async () => {
    mockFetch({ votedMovieIds: [10, 30] })
    renderSwipeScreen(`/s/${JOIN_CODE}/swipe?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText('Movie 20')).toBeInTheDocument()
    })
    expect(screen.queryByText('Movie 10')).not.toBeInTheDocument()
    expect(screen.queryByText('Movie 30')).not.toBeInTheDocument()
  })

  it('renders an explicit message and no card stack when the deck is insufficient_results', async () => {
    mockFetch({ deck: deckResponse({ status: 'insufficient_results', movies: [], totalResults: 2 }) })
    renderSwipeScreen(`/s/${JOIN_CODE}/swipe?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText(/2/)).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: /like/i })).not.toBeInTheDocument()
  })

  it('renders a stale note when the deck response is stale', async () => {
    mockFetch({ deck: deckResponse({ stale: true }) })
    renderSwipeScreen(`/s/${JOIN_CODE}/swipe?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText(/cached/i)).toBeInTheDocument()
    })
  })

  it('issues exactly one POST with the correct body when a vote is committed and advances the front card', async () => {
    const mockFetchFn = mockFetch({})
    renderSwipeScreen(`/s/${JOIN_CODE}/swipe?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText('Movie 10')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /^like$/i }))

    await waitFor(() => {
      expect(screen.getByText('Movie 20')).toBeInTheDocument()
    })

    const voteCalls = mockFetchFn.mock.calls.filter(
      (call) => (call[0] as string).endsWith('/votes') && (call[1] as RequestInit | undefined)?.method === 'POST',
    )
    expect(voteCalls).toHaveLength(1)
    const init = voteCalls[0][1] as RequestInit
    expect(JSON.parse(init.body as string)).toEqual({ movieId: 10, choice: 'LIKE' })
  })

  it('does not advance the cursor and shows an error when the vote is rejected', async () => {
    mockFetch({ voteResult: { status: 400, body: 'not in deck' } })
    renderSwipeScreen(`/s/${JOIN_CODE}/swipe?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText('Movie 10')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /^like$/i }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.getByText('Movie 10')).toBeInTheDocument()
  })

  it('navigates to the waiting route with replace semantics after the final card', async () => {
    mockFetch({ votedMovieIds: [10, 20, 30] })
    renderSwipeScreen(`/s/${JOIN_CODE}/swipe?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText('Movie 40')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /^like$/i }))

    await waitFor(() => {
      expect(screen.getByText(`waiting screen (token=${TOKEN})`)).toBeInTheDocument()
    })
  })

  it('redirects to the waiting route on mount when every movie is already voted', async () => {
    mockFetch({ votedMovieIds: [10, 20, 30, 40] })
    renderSwipeScreen(`/s/${JOIN_CODE}/swipe?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText(`waiting screen (token=${TOKEN})`)).toBeInTheDocument()
    })
  })

  it('redirects to /s/{code} when no token is present in the URL', async () => {
    mockFetch({})
    renderSwipeScreen(`/s/${JOIN_CODE}/swipe`)

    await waitFor(() => {
      expect(screen.getByText('join screen')).toBeInTheDocument()
    })
  })
})
