import { Client, type StompSubscription } from '@stomp/stompjs'
import { useEffect, useRef, useState } from 'react'

// Client half of Phase 5's reconnect-reconcile contract (PITFALLS.md Pitfall 5, prohibition P-04):
// the socket is a signal to re-read, never a carrier of truth. This hook deliberately does not
// deserialize or apply the inbound payload -- the handler takes no argument and only re-triggers
// a REST read via onFrame.
export type ConnectionState = 'connecting' | 'connected' | 'reconnecting'

export function useSessionSocket(sessionId: string | undefined, onFrame: () => void): ConnectionState {
  const [state, setState] = useState<ConnectionState>('connecting')
  const onFrameRef = useRef(onFrame)
  const subscriptionRef = useRef<StompSubscription | null>(null)

  useEffect(() => {
    onFrameRef.current = onFrame
  })

  // Dependency array is [sessionId] alone -- holding onFrame in a ref above means a parent
  // re-render never tears the socket down and rebuilds it.
  useEffect(() => {
    if (!sessionId) {
      return
    }

    const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const client = new Client({
      brokerURL: `${protocol}://${window.location.host}/ws`,
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,
      onConnect: () => {
        setState('connected')
        // Fires on the very first connect and on every reconnect -- this is the
        // reconnect-reconcile trigger, before any frame is applied.
        onFrameRef.current()
        // This must be the only client.subscribe( call site in the file. Subscribing anywhere
        // else is the documented stompjs duplicate-delivery hazard: the library re-runs
        // onConnect on every reconnect and does not restore or de-duplicate prior subscriptions.
        subscriptionRef.current = client.subscribe('/topic/session/' + sessionId, () => onFrameRef.current())
      },
      onWebSocketClose: () => {
        setState('reconnecting')
        // The old subscription object is dead with the connection and must not be reused.
        subscriptionRef.current = null
      },
      onStompError: () => {
        setState('reconnecting')
        subscriptionRef.current = null
      },
    })

    client.activate()

    return () => {
      subscriptionRef.current?.unsubscribe()
      subscriptionRef.current = null
      client.deactivate()
    }
  }, [sessionId])

  return state
}
