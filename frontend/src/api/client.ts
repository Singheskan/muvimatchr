import type { JoinResponse, SessionBootstrapResponse } from './types'

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

export async function fetchBootstrap(joinCode: string, token: string): Promise<SessionBootstrapResponse> {
  return apiFetch<SessionBootstrapResponse>(`/api/sessions/by-code/${encodeURIComponent(joinCode)}/me`, {
    token,
  })
}
