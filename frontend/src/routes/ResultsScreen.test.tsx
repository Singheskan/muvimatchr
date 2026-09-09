import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DeckMovieResponse, DeckResponse, VoteStatusResponse } from '../api/types'
import type { ConnectionState } from '../realtime/useSessionSocket'
import { ResultsScreen } from './ResultsScreen'

// The socket is mocked here for the same reason WaitScreen's suite mocks it -- Task 3's
// human-verify checkpoint is what proves a real browser reacts to a real reconnect frame.
let mockConnectionState: ConnectionState = 'connected'

vi.mock('../realtime/useSessionSocket', () => ({
  useSessionSocket: () => mockConnectionState,
}))

const SESSION_ID = 'session-1'
const JOIN_CODE = 'ABC123'
const TOKEN = 'tok-123'

function bootstrapResponse(votedMovieIds: number[] = [10, 20]) {
  return {
    sessionId: SESSION_ID,
    joinCode: JOIN_CODE,
    participantId: 'p1',
    displayName: 'Alice',
    deckPinned: true,
    votedMovieIds,
  }
}

function statusResponse(overrides: Partial<VoteStatusResponse> = {}): VoteStatusResponse {
  return {
    sessionId: SESSION_ID,
    deckSize: 2,
    activeCount: 2,
    finishedCount: 2,
    isComplete: true,
    matchedMovieIds: [10],
    likeCounts: [{ movieId: 10, likeCount: 2 }],
    ...overrides,
  }
}

function movie(overrides: Partial<DeckMovieResponse> & { tmdbId: number }): DeckMovieResponse {
  return {
    title: `Movie ${overrides.tmdbId}`,
    posterPath: null,
    genreIds: [],
    voteAverage: 7,
    releaseDate: '2021-05-01',
    overview: 'A film about things.',
    providers: [],
    watchLink: null,
    ...overrides,
  }
}

function deckResponse(movies: DeckMovieResponse[]): DeckResponse {
  return {
    sessionId: SESSION_ID,
    status: 'ok',
    stale: false,
    fetchedAt: '2026-01-01T00:00:00Z',
    totalResults: movies.length,
    movies,
  }
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}

function renderResultsScreen(initialPath: string) {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/s/:code/results" element={<ResultsScreen />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ResultsScreen', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    mockConnectionState = 'connected'
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function mockFetch(options: { status?: VoteStatusResponse; deck?: DeckResponse; votedMovieIds?: number[] }) {
    const mockFetchFn = fetch as unknown as ReturnType<typeof vi.fn>
    mockFetchFn.mockImplementation(async (url: string) => {
      if (url.includes('/me')) {
        return jsonResponse(bootstrapResponse(options.votedMovieIds))
      }
      if (url.endsWith('/votes/status')) {
        return jsonResponse(options.status ?? statusResponse())
      }
      if (url.endsWith('/deck')) {
        return jsonResponse(options.deck ?? deckResponse([movie({ tmdbId: 10 }), movie({ tmdbId: 20 })]))
      }
      throw new Error(`Unexpected fetch: ${url}`)
    })
    return mockFetchFn
  }

  it('renders the matched film title, poster and provider names when a match exists', async () => {
    mockFetch({
      deck: deckResponse([
        movie({
          tmdbId: 10,
          title: 'The Match',
          posterPath: '/poster.jpg',
          providers: [{ providerId: 1, providerName: 'Streamflix', logoPath: '/logo.png' }],
        }),
        movie({ tmdbId: 20 }),
      ]),
    })
    renderResultsScreen(`/s/${JOIN_CODE}/results?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText('The Match')).toBeInTheDocument()
    })
    expect(screen.getByText('Streamflix')).toBeInTheDocument()
    expect(screen.getByAltText('The Match')).toHaveAttribute('src', expect.stringContaining('/poster.jpg'))
  })

  it('renders the no-match message and no movie card when matchedMovieIds is empty', async () => {
    mockFetch({ status: statusResponse({ matchedMovieIds: [], likeCounts: [] }) })
    renderResultsScreen(`/s/${JOIN_CODE}/results?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText(/no match/i)).toBeInTheDocument()
    })
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('renders an explicit "no listed availability" line when the matched film has no providers and no watch link', async () => {
    mockFetch({
      deck: deckResponse([movie({ tmdbId: 10, providers: [], watchLink: null }), movie({ tmdbId: 20 })]),
    })
    renderResultsScreen(`/s/${JOIN_CODE}/results?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText(/no streaming availability is listed/i)).toBeInTheDocument()
    })
  })

  it('RSLT-01: mounting directly at the results route with a token and a complete status renders the match with no user interaction between mount and render', async () => {
    mockFetch({
      deck: deckResponse([
        movie({ tmdbId: 10, title: 'The Match', posterPath: '/poster.jpg' }),
        movie({ tmdbId: 20 }),
      ]),
    })
    renderResultsScreen(`/s/${JOIN_CODE}/results?token=${TOKEN}`)

    // No fireEvent/userEvent call occurs anywhere in this test -- the match must render from the
    // cold mount alone, proving a never-connected-during-voting participant needs no manual
    // refresh or interaction to reach it.
    await waitFor(() => {
      expect(screen.getByText('The Match')).toBeInTheDocument()
    })
  })
})
