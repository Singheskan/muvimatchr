import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionFiltersForm } from './SessionFiltersForm'

const SESSION_ID = 'session-1'
const TOKEN = 'tok-123'

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}

function renderForm() {
  const queryClient = new QueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <SessionFiltersForm sessionId={SESSION_ID} token={TOKEN} />
    </QueryClientProvider>,
  )
}

function mockFetch(options: { putResult?: 'ok' | { status: number; body: string } } = {}) {
  const mockFetchFn = fetch as unknown as ReturnType<typeof vi.fn>
  mockFetchFn.mockImplementation(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET'
    if (url.endsWith('/filters') && method === 'GET') {
      return jsonResponse({ sessionId: SESSION_ID, region: 'DE', providerIds: [], genre: null })
    }
    if (url === '/api/catalog/genres') {
      return jsonResponse([
        { id: 28, name: 'Action' },
        { id: 35, name: 'Comedy' },
      ])
    }
    if (url.startsWith('/api/catalog/watch-providers')) {
      return jsonResponse([
        { id: 8, name: 'Netflix', logoPath: null, displayPriority: 1 },
        { id: 9, name: 'Prime Video', logoPath: null, displayPriority: 2 },
      ])
    }
    if (url.endsWith('/filters') && method === 'PUT') {
      if (options.putResult && options.putResult !== 'ok') {
        return new Response(options.putResult.body, { status: options.putResult.status })
      }
      const body = JSON.parse(init!.body as string)
      return jsonResponse({ sessionId: SESSION_ID, ...body })
    }
    throw new Error(`Unexpected fetch: ${method} ${url}`)
  })
  return mockFetchFn
}

describe('SessionFiltersForm', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads current filters and renders genre options and provider checkboxes for the region', async () => {
    mockFetch()
    renderForm()

    await waitFor(() => {
      expect(screen.getByRole('option', { name: 'Comedy' })).toBeInTheDocument()
    })
    await waitFor(() => {
      expect(screen.getByLabelText(/netflix/i)).toBeInTheDocument()
    })
    expect(screen.getByLabelText(/prime video/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/region/i)).toHaveValue('DE')
  })

  it('submits the selected genre and providers via PUT', async () => {
    const mockFetchFn = mockFetch()
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(/netflix/i)).toBeInTheDocument()
    })

    fireEvent.change(screen.getByLabelText(/genre/i), { target: { value: '28' } })
    fireEvent.click(screen.getByLabelText(/netflix/i))
    fireEvent.click(screen.getByRole('button', { name: /save filters/i }))

    await waitFor(() => {
      expect(screen.getByText(/filters saved/i)).toBeInTheDocument()
    })

    const putCall = mockFetchFn.mock.calls.find(
      (call) => (call[0] as string).endsWith('/filters') && (call[1] as RequestInit)?.method === 'PUT',
    )
    expect(putCall).toBeDefined()
    expect(JSON.parse((putCall![1] as RequestInit).body as string)).toEqual({
      region: 'DE',
      genre: 28,
      providerIds: [8],
    })
  })

  // TMDB's own displayPriority ranking doesn't reliably put every mainstream service (especially
  // smaller-market ones like RTL+) inside the default top-10 cut -- pinning keeps them visible
  // without a click regardless of where TMDB ranks them.
  it('surfaces pinned mainstream providers first even when TMDB ranks them below the default cap', async () => {
    const mockFetchFn = fetch as unknown as ReturnType<typeof vi.fn>
    mockFetchFn.mockImplementation(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      if (url.endsWith('/filters') && method === 'GET') {
        return jsonResponse({ sessionId: SESSION_ID, region: 'DE', providerIds: [], genre: null })
      }
      if (url === '/api/catalog/genres') {
        return jsonResponse([])
      }
      if (url.startsWith('/api/catalog/watch-providers')) {
        // 11 low-priority filler providers ranked ahead of RTL+ by TMDB's own displayPriority --
        // a plain "top 10" slice would cut RTL+ off entirely.
        const filler = Array.from({ length: 11 }, (_, i) => ({
          id: 100 + i,
          name: `Filler ${i + 1}`,
          logoPath: null,
          displayPriority: i + 1,
        }))
        return jsonResponse([...filler, { id: 999, name: 'RTL+', logoPath: null, displayPriority: 12 }])
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(/rtl\+/i)).toBeInTheDocument()
    })
  })

  // Live-verified against real TMDB DE data: "HBO Max Amazon Channel" (an addon bundle) ranks
  // ahead of plain "HBO Max" (displayPriority 11 vs 28). A naive keyword-only match would pin the
  // bundle instead of the actual service -- the exclusion list must win regardless of ranking.
  it('pins the canonical service, not a same-brand bundle/addon variant ranked ahead of it', async () => {
    const mockFetchFn = fetch as unknown as ReturnType<typeof vi.fn>
    mockFetchFn.mockImplementation(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? 'GET'
      if (url.endsWith('/filters') && method === 'GET') {
        return jsonResponse({ sessionId: SESSION_ID, region: 'DE', providerIds: [], genre: null })
      }
      if (url === '/api/catalog/genres') {
        return jsonResponse([])
      }
      if (url.startsWith('/api/catalog/watch-providers')) {
        const filler = Array.from({ length: 10 }, (_, i) => ({
          id: 100 + i,
          name: `Filler ${i + 1}`,
          logoPath: null,
          displayPriority: i + 1,
        }))
        // Ascending displayPriority order, matching how the real backend actually returns it --
        // the addon bundle (priority 11) ranks between the fillers (1-10) and canonical HBO Max
        // (28), not before everything.
        return jsonResponse([
          ...filler,
          { id: 500, name: 'HBO Max Amazon Channel', logoPath: null, displayPriority: 11 },
          { id: 501, name: 'HBO Max', logoPath: null, displayPriority: 28 },
        ])
      }
      throw new Error(`Unexpected fetch: ${method} ${url}`)
    })
    renderForm()

    // "HBO Max" (canonical) must be visible without expanding, even though it's ranked 12th by
    // TMDB and the default cap is 10 -- the bundle variant ranked ahead of it must not "use up"
    // the pin instead.
    await waitFor(() => {
      expect(screen.getByLabelText(/hbo max/i)).toBeInTheDocument()
    })
    expect(screen.queryByLabelText(/amazon channel/i)).not.toBeInTheDocument()
  })

  it('shows a locked message on 409 instead of a generic error', async () => {
    mockFetch({ putResult: { status: 409, body: 'Session filters are locked once the deck is pinned' } })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(/netflix/i)).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /save filters/i }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/locked/i)
    })
  })
})
