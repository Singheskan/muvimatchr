import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { SessionBootstrapResponse, VoteStatusResponse } from '../api/types'
import { JoinScreen } from './JoinScreen'

const SESSION_ID = 'session-1'
const JOIN_CODE = 'ABC123'
const TOKEN = 'tok-123'

function bootstrapResponse(overrides: Partial<SessionBootstrapResponse> = {}): SessionBootstrapResponse {
  return {
    sessionId: SESSION_ID,
    joinCode: JOIN_CODE,
    participantId: 'p1',
    displayName: 'Alice',
    deckPinned: true,
    votedMovieIds: [],
    ...overrides,
  }
}

function statusResponse(overrides: Partial<VoteStatusResponse> = {}): VoteStatusResponse {
  return {
    sessionId: SESSION_ID,
    deckSize: 3,
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

// D-05 probes: each destination route reads the token back out of its own URL, so a redirect that
// silently dropped the query string would fail these assertions, not just the pathname ones.
function ResultsProbe() {
  const [searchParams] = useSearchParams()
  return <p>results screen (token={searchParams.get('token')})</p>
}
function WaitProbe() {
  const [searchParams] = useSearchParams()
  return <p>wait screen (token={searchParams.get('token')})</p>
}
function SwipeProbe() {
  const [searchParams] = useSearchParams()
  return <p>swipe screen (token={searchParams.get('token')})</p>
}
function LobbyProbe() {
  const [searchParams] = useSearchParams()
  return <p>lobby screen (token={searchParams.get('token')})</p>
}

function renderAt(path: string) {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/s/:code" element={<JoinScreen />} />
          <Route path="/s/:code/lobby" element={<LobbyProbe />} />
          <Route path="/s/:code/swipe" element={<SwipeProbe />} />
          <Route path="/s/:code/wait" element={<WaitProbe />} />
          <Route path="/s/:code/results" element={<ResultsProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function mockFetch(options: {
  bootstrap?: SessionBootstrapResponse
  bootstrapStatus?: number
  status?: VoteStatusResponse
}) {
  const mockFetchFn = fetch as unknown as ReturnType<typeof vi.fn>
  mockFetchFn.mockImplementation(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    if (url.includes('/me')) {
      if (options.bootstrapStatus && options.bootstrapStatus !== 200) {
        return new Response('unauthorized', { status: options.bootstrapStatus })
      }
      return jsonResponse(options.bootstrap ?? bootstrapResponse())
    }
    if (url.endsWith('/votes/status')) {
      return jsonResponse(options.status ?? statusResponse())
    }
    if (url.endsWith('/participants') && method === 'POST') {
      return jsonResponse(
        {
          participantId: 'p1',
          sessionId: SESSION_ID,
          displayName: 'Alice',
          token: TOKEN,
          resumeUrl: `/s/${JOIN_CODE}?token=${TOKEN}`,
        },
        201,
      )
    }
    throw new Error(`Unexpected fetch: ${method} ${url}`)
  })
  return mockFetchFn
}

describe('JoinScreen', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders and submits the join form when no token is present', async () => {
    mockFetch({})
    renderAt(`/s/${JOIN_CODE}`)

    expect(screen.getByLabelText(/display name/i)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/display name/i), { target: { value: 'Alice' } })
    fireEvent.click(screen.getByRole('button', { name: /join/i }))

    await waitFor(() => {
      expect(fetch).toHaveBeenCalledWith(
        `/api/sessions/${JOIN_CODE}/participants`,
        expect.objectContaining({ method: 'POST' }),
      )
    })
  })

  // D-04's routing comment always called /s/:code the "(join/lobby)" route, but a settled,
  // bootstrapped participant never actually stays here -- resolveScreen now sends a not-yet-
  // pinned session on to /lobby (LobbyScreen.tsx owns the share link and filters that used to
  // live, unreachably, in this component).
  it('redirects to /s/{code}/lobby with the token preserved when the deck is not yet pinned', async () => {
    mockFetch({ bootstrap: bootstrapResponse({ deckPinned: false }), status: statusResponse({ deckSize: 0 }) })
    renderAt(`/s/${JOIN_CODE}?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText(`lobby screen (token=${TOKEN})`)).toBeInTheDocument()
    })
  })

  it('RSLT-01: mounting cold at /s/{code}?token= when the session status reports isComplete true redirects to /s/{code}/results with the token preserved', async () => {
    mockFetch({ status: statusResponse({ isComplete: true }) })
    renderAt(`/s/${JOIN_CODE}?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText(`results screen (token=${TOKEN})`)).toBeInTheDocument()
    })
  })

  it('redirects to /s/{code}/wait when the session is not complete and the participant has finished their own deck', async () => {
    mockFetch({
      bootstrap: bootstrapResponse({ votedMovieIds: [1, 2, 3] }),
      status: statusResponse({ isComplete: false, deckSize: 3 }),
    })
    renderAt(`/s/${JOIN_CODE}?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText(`wait screen (token=${TOKEN})`)).toBeInTheDocument()
    })
  })

  it('redirects to /s/{code}/swipe when the session is not complete and the participant has unvoted cards', async () => {
    mockFetch({
      bootstrap: bootstrapResponse({ votedMovieIds: [1] }),
      status: statusResponse({ isComplete: false, deckSize: 3 }),
    })
    renderAt(`/s/${JOIN_CODE}?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText(`swipe screen (token=${TOKEN})`)).toBeInTheDocument()
    })
  })

  it('renders the display-name form and performs no redirect when no token is present, even when the session is already complete', async () => {
    mockFetch({ status: statusResponse({ isComplete: true }) })
    renderAt(`/s/${JOIN_CODE}`)

    expect(screen.getByLabelText(/display name/i)).toBeInTheDocument()
    expect(
      (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.some((call) =>
        (call[0] as string).endsWith('/votes/status'),
      ),
    ).toBe(false)
  })

  it('falls back to the join form with an explanatory message and no redirect loop when the token is invalid', async () => {
    mockFetch({ bootstrapStatus: 401 })
    renderAt(`/s/${JOIN_CODE}?token=invalid-tok`)

    await waitFor(() => {
      expect(screen.getByText(/no longer valid/i)).toBeInTheDocument()
    })
    expect(screen.getByLabelText(/display name/i)).toBeInTheDocument()
  })
})
