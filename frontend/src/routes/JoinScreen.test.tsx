import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { JoinScreen } from './JoinScreen'

function renderAt(path: string) {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/s/:code" element={<JoinScreen />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('JoinScreen', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders and submits the join form when no token is present', async () => {
    const mockFetch = fetch as unknown as ReturnType<typeof vi.fn>
    mockFetch.mockResolvedValue(
      new Response(
        JSON.stringify({
          participantId: 'p1',
          sessionId: 's1',
          displayName: 'Alice',
          token: 'tok-123',
          resumeUrl: '/s/ABC123?token=tok-123',
        }),
        { status: 201 },
      ),
    )

    renderAt('/s/ABC123')

    expect(screen.getByLabelText(/display name/i)).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText(/display name/i), { target: { value: 'Alice' } })
    fireEvent.click(screen.getByRole('button', { name: /join/i }))

    await waitFor(() => {
      expect(mockFetch).toHaveBeenCalledWith(
        '/api/sessions/ABC123/participants',
        expect.objectContaining({ method: 'POST' }),
      )
    })
  })

  it('renders the server-returned display name when a token is present', async () => {
    const mockFetch = fetch as unknown as ReturnType<typeof vi.fn>
    mockFetch.mockResolvedValue(
      new Response(
        JSON.stringify({
          sessionId: 's1',
          joinCode: 'ABC123',
          participantId: 'p1',
          displayName: 'Alice',
          deckPinned: false,
          votedMovieIds: [],
        }),
        { status: 200 },
      ),
    )

    renderAt('/s/ABC123?token=tok-123')

    await waitFor(() => {
      expect(screen.getByText(/welcome back, alice/i)).toBeInTheDocument()
    })
  })
})
