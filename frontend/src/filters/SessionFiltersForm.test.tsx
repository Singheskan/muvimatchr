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
      expect(screen.getByLabelText('Netflix')).toBeInTheDocument()
    })
    expect(screen.getByLabelText('Prime Video')).toBeInTheDocument()
    expect(screen.getByLabelText(/region/i)).toHaveValue('DE')
  })

  it('submits the selected genre and providers via PUT', async () => {
    const mockFetchFn = mockFetch()
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText('Netflix')).toBeInTheDocument()
    })

    fireEvent.change(screen.getByLabelText(/genre/i), { target: { value: '28' } })
    fireEvent.click(screen.getByLabelText('Netflix'))
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

  it('shows a locked message on 409 instead of a generic error', async () => {
    mockFetch({ putResult: { status: 409, body: 'Session filters are locked once the deck is pinned' } })
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText('Netflix')).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /save filters/i }))

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/locked/i)
    })
  })
})
