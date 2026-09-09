import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SessionFiltersForm } from './SessionFiltersForm'

const TOKEN = 'tok-123'

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}

function renderForm(
  props: Partial<{
    region: string
    genre: number | ''
    providerIds: number[]
    saving: boolean
    saved: boolean
    error: string | null
  }> = {},
) {
  const onRegionChange = vi.fn()
  const onGenreChange = vi.fn()
  const onToggleProvider = vi.fn()
  const queryClient = new QueryClient()
  render(
    <QueryClientProvider client={queryClient}>
      <SessionFiltersForm
        token={TOKEN}
        region={props.region ?? 'DE'}
        genre={props.genre ?? ''}
        providerIds={props.providerIds ?? []}
        onRegionChange={onRegionChange}
        onGenreChange={onGenreChange}
        onToggleProvider={onToggleProvider}
        saving={props.saving ?? false}
        saved={props.saved ?? false}
        error={props.error ?? null}
      />
    </QueryClientProvider>,
  )
  return { onRegionChange, onGenreChange, onToggleProvider }
}

function mockCatalogFetch(watchProviders: unknown[] = [
  { id: 8, name: 'Netflix', logoPath: null, displayPriority: 1 },
  { id: 9, name: 'Prime Video', logoPath: null, displayPriority: 2 },
]) {
  const mockFetchFn = fetch as unknown as ReturnType<typeof vi.fn>
  mockFetchFn.mockImplementation(async (url: string) => {
    if (url === '/api/catalog/genres') {
      return jsonResponse([
        { id: 28, name: 'Action' },
        { id: 35, name: 'Comedy' },
      ])
    }
    if (url.startsWith('/api/catalog/watch-providers')) {
      return jsonResponse(watchProviders)
    }
    throw new Error(`Unexpected fetch: ${url}`)
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

  it('renders genre options and provider tiles for the given region', async () => {
    mockCatalogFetch()
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

  it('calls onRegionChange/onGenreChange/onToggleProvider without owning any save logic itself', async () => {
    mockCatalogFetch()
    const { onRegionChange, onGenreChange, onToggleProvider } = renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(/netflix/i)).toBeInTheDocument()
    })

    fireEvent.change(screen.getByLabelText(/region/i), { target: { value: 'us' } })
    expect(onRegionChange).toHaveBeenCalledWith('US')

    fireEvent.change(screen.getByLabelText(/genre/i), { target: { value: '28' } })
    expect(onGenreChange).toHaveBeenCalledWith(28)

    fireEvent.click(screen.getByLabelText(/netflix/i))
    expect(onToggleProvider).toHaveBeenCalledWith(8)

    // No fetch to /filters (GET or PUT) should ever originate from this component -- that's
    // LobbyScreen's job now.
    const fetchCalls = (fetch as unknown as ReturnType<typeof vi.fn>).mock.calls.map((c) => c[0] as string)
    expect(fetchCalls.some((url) => url.includes('/filters'))).toBe(false)
  })

  it('renders the saving/saved/error status passed in as props', async () => {
    mockCatalogFetch()
    renderForm({ saving: true })
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(/saving/i))
  })

  it('shows a locked message when passed as the error prop', async () => {
    mockCatalogFetch()
    renderForm({ error: 'Filters are locked -- the deck has already been pinned.' })
    expect(screen.getByRole('alert')).toHaveTextContent(/locked/i)
  })

  // TMDB's own displayPriority ranking doesn't reliably put every mainstream service (especially
  // smaller-market ones like RTL+) inside the default top-10 cut -- pinning keeps them visible
  // without a click regardless of where TMDB ranks them.
  it('surfaces pinned mainstream providers first even when TMDB ranks them below the default cap', async () => {
    const filler = Array.from({ length: 11 }, (_, i) => ({
      id: 100 + i,
      name: `Filler ${i + 1}`,
      logoPath: null,
      displayPriority: i + 1,
    }))
    mockCatalogFetch([...filler, { id: 999, name: 'RTL+', logoPath: null, displayPriority: 12 }])
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(/rtl\+/i)).toBeInTheDocument()
    })
  })

  // Live-verified against real TMDB DE data: "HBO Max Amazon Channel" (an addon bundle) ranks
  // ahead of plain "HBO Max" (displayPriority 11 vs 28). A naive keyword-only match would pin the
  // bundle instead of the actual service -- the exclusion list must win regardless of ranking.
  it('pins the canonical service, not a same-brand bundle/addon variant ranked ahead of it', async () => {
    const filler = Array.from({ length: 10 }, (_, i) => ({
      id: 100 + i,
      name: `Filler ${i + 1}`,
      logoPath: null,
      displayPriority: i + 1,
    }))
    // Ascending displayPriority order, matching how the real backend actually returns it -- the
    // addon bundle (priority 11) ranks between the fillers (1-10) and canonical HBO Max (28).
    mockCatalogFetch([
      ...filler,
      { id: 500, name: 'HBO Max Amazon Channel', logoPath: null, displayPriority: 11 },
      { id: 501, name: 'HBO Max', logoPath: null, displayPriority: 28 },
    ])
    renderForm()

    await waitFor(() => {
      expect(screen.getByLabelText(/hbo max/i)).toBeInTheDocument()
    })
    expect(screen.queryByLabelText(/amazon channel/i)).not.toBeInTheDocument()
  })
})
