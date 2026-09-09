import type {
  CreateSessionResponse,
  DeckResponse,
  GenreResponse,
  JoinResponse,
  SessionBootstrapResponse,
  SessionFiltersRequest,
  SessionFiltersResponse,
  SessionRosterResponse,
  VoteChoice,
  VoteStatusResponse,
  WatchProviderResponse,
} from './types'

// Carries the response status and body text on any non-2xx response -- callers must branch on
// `status` rather than get a silently-empty result on failure.
export class ApiError extends Error {
  status: number
  body: string

  constructor(status: number, body: string) {
    super(`API request failed with status ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

interface ApiFetchOptions {
  token?: string
  method?: string
  body?: unknown
}

// Threat T-06-10: the Authorization header is attached ONLY when a token is explicitly supplied
// AND only for paths beginning "/api/" -- a bearer token must never ride along on a cross-origin
// asset request (e.g. a TMDB poster fetch). Paths are same-origin "/api/..." paths; no base URL
// prefix is added here.
export async function apiFetch<T>(path: string, opts: ApiFetchOptions = {}): Promise<T> {
  const headers: Record<string, string> = {}
  if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (opts.token && path.startsWith('/api/')) {
    headers['Authorization'] = `Bearer ${opts.token}`
  }

  const response = await fetch(path, {
    method: opts.method ?? (opts.body !== undefined ? 'POST' : 'GET'),
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })

  if (!response.ok) {
    const text = await response.text()
    throw new ApiError(response.status, text)
  }

  return (await response.json()) as T
}

export async function joinSession(joinCode: string, displayName: string): Promise<JoinResponse> {
  return apiFetch<JoinResponse>(`/api/sessions/${encodeURIComponent(joinCode)}/participants`, {
    method: 'POST',
    body: { displayName },
  })
}

// Filters (genre/region/providerIds) are deliberately not set here -- PROJECT.md/CTLG-02/CTLG-03
// treat filtering as a shared, any-participant, post-creation action via PUT /filters, not a
// creation-time host step (there is no host role, SessionController D-02). A blank session is
// created and the creator joins it exactly like anyone else opening the resulting link.
export async function createSession(): Promise<CreateSessionResponse> {
  return apiFetch<CreateSessionResponse>('/api/sessions', {
    method: 'POST',
  })
}

export async function fetchBootstrap(joinCode: string, token: string): Promise<SessionBootstrapResponse> {
  return apiFetch<SessionBootstrapResponse>(`/api/sessions/by-code/${encodeURIComponent(joinCode)}/me`, {
    token,
  })
}

// The deck is immutable once pinned (Phase 4 D-01); this call is also what triggers the lazy
// server-side pin on a session's first deck fetch.
export async function fetchDeck(sessionId: string, token: string): Promise<DeckResponse> {
  return apiFetch<DeckResponse>(`/api/sessions/${encodeURIComponent(sessionId)}/deck`, { token })
}

export async function postVote(
  sessionId: string,
  token: string,
  movieId: number,
  choice: VoteChoice,
): Promise<VoteStatusResponse> {
  return apiFetch<VoteStatusResponse>(`/api/sessions/${encodeURIComponent(sessionId)}/votes`, {
    token,
    method: 'POST',
    body: { movieId, choice },
  })
}

// The route guard's REST source of truth (D-04) -- resolveScreen reads status.isComplete from
// this response and nothing else.
export async function fetchStatus(sessionId: string, token: string): Promise<VoteStatusResponse> {
  return apiFetch<VoteStatusResponse>(`/api/sessions/${encodeURIComponent(sessionId)}/votes/status`, { token })
}

// D-10's named waiting roster.
export async function fetchRoster(sessionId: string, token: string): Promise<SessionRosterResponse> {
  return apiFetch<SessionRosterResponse>(`/api/sessions/${encodeURIComponent(sessionId)}/votes/roster`, { token })
}

// /api/catalog/* is auth-only, not session-scoped (any valid participant token works for any
// session's reference data) -- it exists purely to keep TMDB budget away from unauthenticated
// callers, per CatalogReferenceController.
export async function fetchGenres(token: string): Promise<GenreResponse[]> {
  return apiFetch<GenreResponse[]>('/api/catalog/genres', { token })
}

export async function fetchWatchProviders(region: string, token: string): Promise<WatchProviderResponse[]> {
  return apiFetch<WatchProviderResponse[]>(`/api/catalog/watch-providers?region=${encodeURIComponent(region)}`, {
    token,
  })
}

export async function fetchSessionFilters(sessionId: string, token: string): Promise<SessionFiltersResponse> {
  return apiFetch<SessionFiltersResponse>(`/api/sessions/${encodeURIComponent(sessionId)}/filters`, { token })
}

// 409 once the deck is pinned (locked) -- callers must surface that distinctly from a validation
// error, not as a generic failure.
export async function updateSessionFilters(
  sessionId: string,
  token: string,
  request: SessionFiltersRequest,
): Promise<SessionFiltersResponse> {
  return apiFetch<SessionFiltersResponse>(`/api/sessions/${encodeURIComponent(sessionId)}/filters`, {
    token,
    method: 'PUT',
    body: request,
  })
}
