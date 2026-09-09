import { render } from '@testing-library/react'
import React from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { VoteStatusResponse } from '../api/types'
import { resolveScreen, type Screen } from './resolveScreen'

const CODE = 'ABC123'

const navigateMock = vi.fn()
let mockSearch = ''

// The guard's "preserve the query string" behaviour is asserted against a mocked useLocation
// rather than a real MemoryRouter -- this file stays .ts (no JSX), consistent with the plan's
// named test file, and a mocked navigate is exactly what the plan calls for.
vi.mock('react-router', () => ({
  useNavigate: () => navigateMock,
  useLocation: () => ({ pathname: '', search: mockSearch, hash: '', state: null, key: 'test' }),
}))

const { useRouteGuard } = await import('./useRouteGuard')

function status(overrides: Partial<VoteStatusResponse> = {}): VoteStatusResponse {
  return {
    sessionId: 'session-1',
    deckSize: 6,
    activeCount: 2,
    finishedCount: 0,
    isComplete: false,
    matchedMovieIds: [],
    likeCounts: [],
    ...overrides,
  }
}

describe('resolveScreen', () => {
  it("returns 'join' when hasToken is false, regardless of status", () => {
    expect(
      resolveScreen({ hasToken: false, status: status({ isComplete: true }), myVotedCount: 6, deckPinned: true }),
    ).toBe('join')
  })

  it("returns 'join' when status is null", () => {
    expect(resolveScreen({ hasToken: true, status: null, myVotedCount: 0, deckPinned: false })).toBe('join')
  })

  it("returns 'results' whenever status.isComplete is true, even if myVotedCount is below deckSize", () => {
    expect(
      resolveScreen({
        hasToken: true,
        status: status({ isComplete: true, deckSize: 6 }),
        myVotedCount: 2,
        deckPinned: true,
      }),
    ).toBe('results')
  })

  it("returns 'wait' when isComplete is false, deckSize is 6 and myVotedCount is 6", () => {
    expect(
      resolveScreen({
        hasToken: true,
        status: status({ isComplete: false, deckSize: 6 }),
        myVotedCount: 6,
        deckPinned: true,
      }),
    ).toBe('wait')
  })

  it("returns 'swipe' when isComplete is false, deckSize is 6, myVotedCount is 5 and the deck is pinned", () => {
    expect(
      resolveScreen({
        hasToken: true,
        status: status({ isComplete: false, deckSize: 6 }),
        myVotedCount: 5,
        deckPinned: true,
      }),
    ).toBe('swipe')
  })

  it("returns 'lobby' when deckSize is 0 (deck never pinned), myVotedCount is 0 and deckPinned is false", () => {
    expect(
      resolveScreen({
        hasToken: true,
        status: status({ isComplete: false, deckSize: 0 }),
        myVotedCount: 0,
        deckPinned: false,
      }),
    ).toBe('lobby')
  })

  it("returns 'swipe', not 'lobby', once deckPinned is true even if deckSize/myVotedCount are still both 0 (a genuinely empty pinned deck)", () => {
    expect(
      resolveScreen({
        hasToken: true,
        status: status({ isComplete: false, deckSize: 0 }),
        myVotedCount: 0,
        deckPinned: true,
      }),
    ).toBe('swipe')
  })
})

function GuardHarness(props: {
  currentScreen: Screen
  hasToken: boolean
  status: VoteStatusResponse | null
  myVotedCount: number
  deckPinned: boolean
  ready: boolean
}) {
  useRouteGuard(CODE, props.currentScreen, {
    hasToken: props.hasToken,
    status: props.status,
    myVotedCount: props.myVotedCount,
    deckPinned: props.deckPinned,
    ready: props.ready,
  })
  return React.createElement('p', null, 'harness')
}

describe('useRouteGuard', () => {
  beforeEach(() => {
    navigateMock.mockClear()
    mockSearch = '?token=tok-123'
  })

  it('performs no navigation while ready is false, so a screen never flickers through a redirect during its first load', () => {
    render(
      React.createElement(GuardHarness, {
        currentScreen: 'swipe',
        hasToken: false,
        status: null,
        myVotedCount: 0,
        deckPinned: false,
        ready: false,
      }),
    )
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('performs no navigation when the resolved screen already matches currentScreen', () => {
    render(
      React.createElement(GuardHarness, {
        currentScreen: 'swipe',
        hasToken: true,
        status: status({ isComplete: false, deckSize: 6 }),
        myVotedCount: 3,
        deckPinned: true,
        ready: true,
      }),
    )
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('navigates with replace semantics and preserves the query string when the resolved screen differs from currentScreen', () => {
    render(
      React.createElement(GuardHarness, {
        currentScreen: 'swipe',
        hasToken: true,
        status: status({ isComplete: true, deckSize: 6 }),
        myVotedCount: 0,
        deckPinned: true,
        ready: true,
      }),
    )
    expect(navigateMock).toHaveBeenCalledWith(
      { pathname: `/s/${CODE}/results`, search: '?token=tok-123' },
      { replace: true },
    )
  })

  it('navigates a settled, never-pinned session to /lobby rather than /swipe', () => {
    render(
      React.createElement(GuardHarness, {
        currentScreen: 'swipe',
        hasToken: true,
        status: status({ isComplete: false, deckSize: 0 }),
        myVotedCount: 0,
        deckPinned: false,
        ready: true,
      }),
    )
    expect(navigateMock).toHaveBeenCalledWith(
      { pathname: `/s/${CODE}/lobby`, search: '?token=tok-123' },
      { replace: true },
    )
  })
})
