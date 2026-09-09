import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useSearchParams } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CreateSessionScreen } from './CreateSessionScreen'

const JOIN_CODE = 'NEWCOD'
const SESSION_ID = 'session-new'
const TOKEN = 'tok-host-123'

// D-05: the token lives only in the URL query string -- this probe proves the create flow lands
// on the session route with the token actually attached, not just the right pathname.
function SessionScreenProbe() {
  const [searchParams] = useSearchParams()
  return <p>session screen (code=NEWCOD token={searchParams.get('token')})</p>
}

function renderCreateScreen() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <Routes>
        <Route path="/" element={<CreateSessionScreen />} />
        <Route path="/s/:code" element={<SessionScreenProbe />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('CreateSessionScreen', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function mockFetch(options: { createResult?: 'ok' | { status: number }; joinResult?: 'ok' | { status: number } } = {}) {
    const mockFetchFn = fetch as unknown as ReturnType<typeof vi.fn>
    mockFetchFn.mockImplementation(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      if (url === '/api/sessions' && method === 'POST') {
        if (options.createResult && options.createResult !== 'ok') {
          return new Response('create failed', { status: options.createResult.status })
        }
        return new Response(
          JSON.stringify({ sessionId: SESSION_ID, joinCode: JOIN_CODE, region: 'DE', providerIds: [], genre: null }),
          { status: 200 },
        )
      }
      if (url === `/api/sessions/${JOIN_CODE}/participants` && method === 'POST') {
        if (options.joinResult && options.joinResult !== 'ok') {
          return new Response('join failed', { status: options.joinResult.status })
        }
        return new Response(
          JSON.stringify({
            participantId: 'p1',
            sessionId: SESSION_ID,
            displayName: 'Alice',
            token: TOKEN,
            resumeUrl: `/s/${JOIN_CODE}?token=${TOKEN}`,
          }),
          { status: 200 },
        )
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    })
    return mockFetchFn
  }

  it('renders a display-name form with a create button', () => {
    mockFetch()
    renderCreateScreen()

    expect(screen.getByLabelText(/display name/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /start a session|create/i })).toBeInTheDocument()
  })

  it('creates a session, joins as the entered name, and navigates to the session route with the token', async () => {
    const mockFetchFn = mockFetch()
    renderCreateScreen()

    fireEvent.change(screen.getByLabelText(/display name/i), { target: { value: 'Alice' } })
    fireEvent.click(screen.getByRole('button', { name: /start a session|create/i }))

    await waitFor(() => {
      expect(screen.getByText(`session screen (code=NEWCOD token=${TOKEN})`)).toBeInTheDocument()
    })

    const createCall = mockFetchFn.mock.calls.find((call) => call[0] === '/api/sessions')
    expect(createCall).toBeDefined()
    const joinCall = mockFetchFn.mock.calls.find((call) => call[0] === `/api/sessions/${JOIN_CODE}/participants`)
    expect(joinCall).toBeDefined()
    expect(JSON.parse((joinCall![1] as RequestInit).body as string)).toEqual({ displayName: 'Alice' })
  })

  it('shows an error and does not navigate when session creation fails', async () => {
    mockFetch({ createResult: { status: 500 } })
    renderCreateScreen()

    fireEvent.change(screen.getByLabelText(/display name/i), { target: { value: 'Alice' } })
    fireEvent.click(screen.getByRole('button', { name: /start a session|create/i }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.queryByText(/session screen/)).not.toBeInTheDocument()
  })

  it('shows an error and does not navigate when the subsequent join fails', async () => {
    mockFetch({ joinResult: { status: 400 } })
    renderCreateScreen()

    fireEvent.change(screen.getByLabelText(/display name/i), { target: { value: 'Alice' } })
    fireEvent.click(screen.getByRole('button', { name: /start a session|create/i }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
    expect(screen.queryByText(/session screen/)).not.toBeInTheDocument()
  })
})
