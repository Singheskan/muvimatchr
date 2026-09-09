import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SessionBootstrapResponse, VoteStatusResponse } from '../api/types'
import { LobbyScreen } from './LobbyScreen'

const SESSION_ID = 'session-1'
const JOIN_CODE = 'ABC123'
const TOKEN = 'tok-123'

function bootstrapResponse(overrides: Partial<SessionBootstrapResponse> = {}): SessionBootstrapResponse {
  return {
    sessionId: SESSION_ID,
    joinCode: JOIN_CODE,
    participantId: 'p1',
    displayName: 'Alice',
    deckPinned: false,
    votedMovieIds: [],
    ...overrides,
  }
}

function statusResponse(overrides: Partial<VoteStatusResponse> = {}): VoteStatusResponse {
  return {
    sessionId: SESSION_ID,
    deckSize: 0,
    activeCount: 1,
    finishedCount: 0,
    isComplete: false,
    matchedMovieIds: [],
    likeCounts: [],
    ...overrides,
  }
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}

function SwipeProbe() {
  const [searchParams] = useSearchParams()
  return <p>swipe screen (token={searchParams.get('token')})</p>
}

function renderLobby(
  options: {
    bootstrap?: SessionBootstrapResponse
    status?: VoteStatusResponse
    roster?: { participantId: string; displayName: string; votedCount: number; isFinished: boolean; isActive: boolean }[]
  } = {},
) {
  // Stateful: once /deck has been called (the real backend's pin trigger), subsequent /me
  // refetches reflect deckPinned: true -- mirrors the real pin-then-invalidate sequence
  // LobbyScreen's "Start swiping" handler depends on.
  let deckPinnedAfterFetch = false
  const mockFetchFn = fetch as unknown as ReturnType<typeof vi.fn>
  mockFetchFn.mockImplementation(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    if (url.includes('/me')) {
      const base = options.bootstrap ?? bootstrapResponse()
      return jsonResponse(deckPinnedAfterFetch ? { ...base, deckPinned: true } : base)
    }
    if (url.endsWith('/votes/status')) {
      return jsonResponse(options.status ?? statusResponse())
    }
    if (url.endsWith('/filters') && method === 'GET') {
      return jsonResponse({ sessionId: SESSION_ID, region: 'DE', providerIds: [], genre: null })
    }
    if (url.endsWith('/votes/roster')) {
      return jsonResponse({
        sessionId: SESSION_ID,
        deckSize: 0,
        participants: options.roster ?? [
          { participantId: 'p1', displayName: 'Alice', votedCount: 0, isFinished: false, isActive: true },
        ],
      })
    }
    if (url === '/api/catalog/genres') {
      return jsonResponse([])
    }
    if (url.startsWith('/api/catalog/watch-providers')) {
      return jsonResponse([])
    }
    if (url.endsWith('/deck')) {
      deckPinnedAfterFetch = true
      return jsonResponse({
        sessionId: SESSION_ID,
        status: 'ok',
        stale: false,
        fetchedAt: '2026-01-01T00:00:00Z',
        totalResults: 1,
        movies: [],
      })
    }
    throw new Error(`Unexpected fetch: ${method} ${url}`)
  })

  const queryClient = new QueryClient()
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/s/${JOIN_CODE}/lobby?token=${TOKEN}`]}>
        <Routes>
          <Route path="/s/:code/lobby" element={<LobbyScreen />} />
          <Route path="/s/:code/swipe" element={<SwipeProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return mockFetchFn
}

describe('LobbyScreen', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows a tokenless share link and a start-swiping button', async () => {
    renderLobby()

    await waitFor(() => {
      expect(screen.getByLabelText(/share this link/i)).toBeInTheDocument()
    })
    const shareInput = screen.getByLabelText(/share this link/i) as HTMLInputElement
    expect(shareInput.value).not.toContain('token=')
    expect(screen.getByRole('button', { name: /start swiping/i })).toBeInTheDocument()
  })

  it('renders the filters form since the deck is not pinned yet', async () => {
    renderLobby()

    await waitFor(() => {
      expect(screen.getByLabelText(/region/i)).toBeInTheDocument()
    })
  })

  it('pins the deck and navigates to /swipe with the token when "Start swiping" is clicked', async () => {
    const mockFetchFn = renderLobby()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /start swiping/i })).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /start swiping/i }))

    await waitFor(() => {
      expect(screen.getByText(`swipe screen (token=${TOKEN})`)).toBeInTheDocument()
    })

    const deckCall = mockFetchFn.mock.calls.find((call) => (call[0] as string).endsWith('/deck'))
    expect(deckCall).toBeDefined()
  })

  it('redirects away to /swipe automatically once the deck is already pinned', async () => {
    renderLobby({ bootstrap: bootstrapResponse({ deckPinned: true }) })

    await waitFor(() => {
      expect(screen.getByText(`swipe screen (token=${TOKEN})`)).toBeInTheDocument()
    })
  })

  // Found live-testing: joining worked, but nothing showed who else had actually joined.
  it("shows who's in the session", async () => {
    renderLobby({
      roster: [
        { participantId: 'p1', displayName: 'Alice', votedCount: 0, isFinished: false, isActive: true },
        { participantId: 'p2', displayName: 'Bob', votedCount: 0, isFinished: false, isActive: true },
      ],
    })

    await waitFor(() => {
      expect(screen.getByText('Alice')).toBeInTheDocument()
    })
    expect(screen.getByText('Bob')).toBeInTheDocument()
  })
})
