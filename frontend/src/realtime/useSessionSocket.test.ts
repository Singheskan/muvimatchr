import { act, render, screen, waitFor } from '@testing-library/react'
import React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Mocked, not real: proves the hook's *wiring* (one subscribe call site inside onConnect, torn
// down on cleanup) without opening a real socket. A real browser reconnect is what Task 3's
// human-verify checkpoint proves; this suite structurally cannot make that claim.
interface FakeSubscription {
  unsubscribe: ReturnType<typeof vi.fn>
}

class FakeClient {
  static instances: FakeClient[] = []
  onConnect: () => void
  onWebSocketClose: () => void
  onStompError: () => void
  activate = vi.fn()
  deactivate = vi.fn()
  subscribe = vi.fn((_topic: string): FakeSubscription => {
    const sub = { unsubscribe: vi.fn() }
    this.subscriptions.push(sub)
    return sub
  })
  subscriptions: FakeSubscription[] = []
  topics: string[] = []

  constructor(config: { onConnect: () => void; onWebSocketClose: () => void; onStompError: () => void }) {
    this.onConnect = config.onConnect
    this.onWebSocketClose = config.onWebSocketClose
    this.onStompError = config.onStompError
    FakeClient.instances.push(this)
  }
}

vi.mock('@stomp/stompjs', () => ({ Client: FakeClient }))

const { useSessionSocket } = await import('./useSessionSocket')

function Harness({ sessionId, onFrame }: { sessionId: string | undefined; onFrame: () => void }) {
  const state = useSessionSocket(sessionId, onFrame)
  return React.createElement('p', null, `state:${state}`)
}

describe('useSessionSocket', () => {
  beforeEach(() => {
    FakeClient.instances = []
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it("reports 'connecting' before the first connect, 'connected' after onConnect fires, and 'reconnecting' after the socket closes", async () => {
    const onFrame = vi.fn()
    render(React.createElement(Harness, { sessionId: 'session-1', onFrame }))

    expect(screen.getByText('state:connecting')).toBeInTheDocument()

    const instance = FakeClient.instances[0]
    act(() => instance.onConnect())
    await waitFor(() => {
      expect(screen.getByText('state:connected')).toBeInTheDocument()
    })

    act(() => instance.onWebSocketClose())
    await waitFor(() => {
      expect(screen.getByText('state:reconnecting')).toBeInTheDocument()
    })
  })

  it('creates the subscription only inside onConnect, with exactly one live subscription surviving a disconnect-then-reconnect cycle', async () => {
    const onFrame = vi.fn()
    render(React.createElement(Harness, { sessionId: 'session-1', onFrame }))

    const instance = FakeClient.instances[0]
    expect(instance.subscribe).not.toHaveBeenCalled()

    act(() => instance.onConnect())
    expect(instance.subscribe).toHaveBeenCalledTimes(1)
    expect(instance.subscribe).toHaveBeenCalledWith('/topic/session/session-1', expect.any(Function))
    const firstSub = instance.subscriptions[0]

    act(() => instance.onWebSocketClose())
    act(() => instance.onConnect())
    expect(instance.subscribe).toHaveBeenCalledTimes(2)
    const secondSub = instance.subscriptions[1]

    // The stale subscription from before the drop must never be reused or re-unsubscribed --
    // it died with the connection. Only the current, live one is ever torn down.
    expect(firstSub.unsubscribe).not.toHaveBeenCalled()
    expect(secondSub.unsubscribe).not.toHaveBeenCalled()
  })

  it('fires onFrame on every connect -- the first one and every reconnect -- before any frame arrives', () => {
    const onFrame = vi.fn()
    render(React.createElement(Harness, { sessionId: 'session-1', onFrame }))

    const instance = FakeClient.instances[0]
    act(() => instance.onConnect())
    expect(onFrame).toHaveBeenCalledTimes(1)

    act(() => instance.onWebSocketClose())
    act(() => instance.onConnect())
    expect(onFrame).toHaveBeenCalledTimes(2)
  })

  it('unsubscribes and deactivates on unmount, so a StrictMode double-mount does not leak a socket', () => {
    const onFrame = vi.fn()
    const { unmount } = render(React.createElement(Harness, { sessionId: 'session-1', onFrame }))

    const instance = FakeClient.instances[0]
    act(() => instance.onConnect())
    const sub = instance.subscriptions[0]

    unmount()

    expect(sub.unsubscribe).toHaveBeenCalledTimes(1)
    expect(instance.deactivate).toHaveBeenCalledTimes(1)
  })
})
