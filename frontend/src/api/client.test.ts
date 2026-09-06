import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch, joinSession } from './client'

describe('apiFetch', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('attaches the Authorization header when a token is supplied', async () => {
    const mockFetch = fetch as unknown as ReturnType<typeof vi.fn>
    mockFetch.mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), { status: 200 }),
    )

    await apiFetch('/api/sessions/by-code/ABC123/me', { token: 'my-token' })

    const [, init] = mockFetch.mock.calls[0]
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer my-token')
  })

  it('does not attach the Authorization header when no token is supplied', async () => {
    const mockFetch = fetch as unknown as ReturnType<typeof vi.fn>
    mockFetch.mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), { status: 200 }),
    )

    await apiFetch('/api/sessions/by-code/ABC123/me')

    const [, init] = mockFetch.mock.calls[0]
    expect((init.headers as Record<string, string>)['Authorization']).toBeUndefined()
  })

  it('throws ApiError carrying the status on a non-2xx response', async () => {
    const mockFetch = fetch as unknown as ReturnType<typeof vi.fn>
    mockFetch.mockImplementation(async () => new Response('not found', { status: 404 }))

    await expect(apiFetch('/api/sessions/by-code/ZZZ/me')).rejects.toMatchObject({
      status: 404,
    })
    await expect(apiFetch('/api/sessions/by-code/ZZZ/me')).rejects.toBeInstanceOf(ApiError)
  })

  it('joinSession URL-encodes the join code', async () => {
    const mockFetch = fetch as unknown as ReturnType<typeof vi.fn>
    mockFetch.mockResolvedValue(
      new Response(
        JSON.stringify({
          participantId: 'p1',
          sessionId: 's1',
          displayName: 'Alice',
          token: 'tok',
          resumeUrl: '/s/AB C?token=tok',
        }),
        { status: 201 },
      ),
    )

    await joinSession('AB C', 'Alice')

    const [url] = mockFetch.mock.calls[0]
    expect(url).toBe('/api/sessions/AB%20C/participants')
  })
})
