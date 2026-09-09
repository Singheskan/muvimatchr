import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ParticipantProgressResponse, SessionRosterResponse, VoteStatusResponse } from '../api/types'
import type { ConnectionState } from '../realtime/useSessionSocket'
import { WaitScreen } from './WaitScreen'

// The socket itself is mocked here -- Task 3's human-verify checkpoint is what proves a real
// browser recovers from a real network drop. This suite proves WaitScreen renders correctly for
// a given connection state and correctly wires onFrame into query invalidation, not that the
// socket really reconnects.
let mockConnectionState: ConnectionState = 'connected'
let capturedOnFrame: (() => void) | undefined

vi.mock('../realtime/useSessionSocket', () => ({
  useSessionSocket: (_sessionId: string | undefined, onFrame: () => void) => {
    capturedOnFrame = onFrame
    return mockConnectionState
  },
}))

function ResultsProbe() {
  const [searchParams] = useSearchParams()
  return <p>results screen (token={searchParams.get('token')})</p>
}

const SESSION_ID = 'session-1'
const JOIN_CODE = 'ABC123'
const TOKEN = 'tok-123'

function bootstrapResponse() {
  return {
    sessionId: SESSION_ID,
    joinCode: JOIN_CODE,
    participantId: 'p1',
    displayName: 'Alice',
    deckPinned: true,
    votedMovieIds: [10, 20, 30, 40, 50, 60],
  }
}

function statusResponse(overrides: Partial<VoteStatusResponse> = {}): VoteStatusResponse {
  return {
    sessionId: SESSION_ID,
    deckSize: 6,
    activeCount: 3,
    finishedCount: 1,
    isComplete: false,
    matchedMovieIds: [],
    likeCounts: [],
    ...overrides,
  }
}

function participant(overrides: Partial<ParticipantProgressResponse> = {}): ParticipantProgressResponse {
  return {
    participantId: 'p1',
    displayName: 'Alice',
    votedCount: 6,
    isFinished: true,
    isActive: true,
    ...overrides,
  }
}

function rosterResponse(participants: ParticipantProgressResponse[]): SessionRosterResponse {
  return { sessionId: SESSION_ID, deckSize: 6, participants }
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}

function renderWaitScreen(initialPath: string) {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route path="/s/:code/wait" element={<WaitScreen />} />
          <Route path="/s/:code/results" element={<ResultsProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('WaitScreen', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    mockConnectionState = 'connected'
    capturedOnFrame = undefined
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function mockFetch(options: { status?: VoteStatusResponse; roster?: SessionRosterResponse }) {
    const mockFetchFn = fetch as unknown as ReturnType<typeof vi.fn>
    mockFetchFn.mockImplementation(async (url: string) => {
      if (url.includes('/me')) {
        return jsonResponse(bootstrapResponse())
      }
      if (url.endsWith('/votes/status')) {
        return jsonResponse(options.status ?? statusResponse())
      }
      if (url.endsWith('/votes/roster')) {
        return jsonResponse(options.roster ?? rosterResponse([participant()]))
      }
      throw new Error(`Unexpected fetch: ${url}`)
    })
    return mockFetchFn
  }

  it('renders a done marker for a finished participant, a waiting marker for unfinished-and-active, and an away marker for inactive', async () => {
    mockFetch({
      roster: rosterResponse([
        participant({ participantId: 'p1', displayName: 'Alice', isFinished: true, isActive: true }),
        participant({ participantId: 'p2', displayName: 'Jordan', isFinished: false, isActive: true }),
        participant({ participantId: 'p3', displayName: 'Sam', isFinished: false, isActive: false }),
      ]),
    })
    renderWaitScreen(`/s/${JOIN_CODE}/wait?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText('Alice')).toBeInTheDocument()
    })
    expect(screen.getByText('Jordan')).toBeInTheDocument()
    expect(screen.getByText('Sam')).toBeInTheDocument()
    expect(screen.getByTestId('marker-p1')).toHaveTextContent(/done/i)
    expect(screen.getByTestId('marker-p2')).toHaveTextContent(/waiting/i)
    expect(screen.getByTestId('marker-p3')).toHaveTextContent(/away/i)
  })

  it('renders a "waiting on N of M" progress line built from finishedCount and activeCount', async () => {
    mockFetch({ status: statusResponse({ finishedCount: 2, activeCount: 5 }) })
    renderWaitScreen(`/s/${JOIN_CODE}/wait?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText(/2.*5/)).toBeInTheDocument()
    })
  })

  it('renders a reconnecting indicator whenever the connection state is not connected', async () => {
    mockConnectionState = 'reconnecting'
    mockFetch({})
    renderWaitScreen(`/s/${JOIN_CODE}/wait?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText(/reconnecting/i)).toBeInTheDocument()
    })
  })

  it('renders no reconnecting indicator when connected', async () => {
    mockConnectionState = 'connected'
    mockFetch({})
    renderWaitScreen(`/s/${JOIN_CODE}/wait?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText('Alice')).toBeInTheDocument()
    })
    expect(screen.queryByText(/reconnecting/i)).not.toBeInTheDocument()
  })

  it('invalidates status, roster and bootstrap queries when the socket signals a frame', async () => {
    mockFetch({})
    renderWaitScreen(`/s/${JOIN_CODE}/wait?token=${TOKEN}`)

    await waitFor(() => {
      expect(capturedOnFrame).toBeDefined()
    })

    const callsBefore = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.length
    capturedOnFrame!()

    await waitFor(() => {
      expect((fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(callsBefore)
    })
  })

  it('navigates to the results route with the token preserved when status.isComplete becomes true', async () => {
    mockFetch({ status: statusResponse({ isComplete: true }) })
    renderWaitScreen(`/s/${JOIN_CODE}/wait?token=${TOKEN}`)

    await waitFor(() => {
      expect(screen.getByText(`results screen (token=${TOKEN})`)).toBeInTheDocument()
    })
  })
})
