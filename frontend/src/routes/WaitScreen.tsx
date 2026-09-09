import { useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { useParams } from 'react-router'
import { useSessionSocket } from '../realtime/useSessionSocket'
import { useRouteGuard } from '../routing/useRouteGuard'
import { useBootstrap } from '../session/useBootstrap'
import { useRoster } from '../session/useRoster'
import { useSessionStatus } from '../session/useSessionStatus'
import { useSessionToken } from '../session/useSessionToken'
import './wait.css'

// D-10's named waiting roster, wired to the Phase 5 STOMP topic -- the client half of the
// reconnect-reconcile contract Phase 5 built the server half of.
export function WaitScreen() {
  const { code } = useParams<{ code: string }>()
  const token = useSessionToken()
  const queryClient = useQueryClient()

  const bootstrap = useBootstrap(code, token)
  const sessionId = bootstrap.data?.sessionId
  const status = useSessionStatus(sessionId, token)
  const roster = useRoster(sessionId, token)

  // Every onConnect -- the first one and every reconnect -- and every inbound frame invalidates
  // these three query keys before any frame is applied; the frame body is never written into
  // component state as the displayed value (prohibition P-04).
  const onFrame = useCallback(() => {
    queryClient.invalidateQueries({ queryKey: ['status', sessionId] })
    queryClient.invalidateQueries({ queryKey: ['roster', sessionId] })
    queryClient.invalidateQueries({ queryKey: ['bootstrap', code, token] })
  }, [queryClient, sessionId, code, token])

  const connectionState = useSessionSocket(sessionId, onFrame)

  // RTIME-02 rendered in the SPA: when status.isComplete flips true, this moves the screen to
  // /s/{code}/results with no user action.
  useRouteGuard(code, 'wait', {
    hasToken: Boolean(token),
    status: status.data ?? null,
    myVotedCount: bootstrap.data?.votedMovieIds.length ?? 0,
    deckPinned: bootstrap.data?.deckPinned ?? false,
    ready: !bootstrap.isLoading && !status.isLoading,
  })

  if (!token) {
    return null
  }

  if (bootstrap.isLoading || roster.isLoading || !roster.data || !status.data) {
    return <p>Loading…</p>
  }

  return (
    <section>
      <h1>Session {code}</h1>
      <p>
        Waiting on {status.data.finishedCount} of {status.data.activeCount}
      </p>
      <ul className="wait-roster">
        {roster.data.participants.map((participant) => (
          <li key={participant.participantId}>
            <span>{participant.displayName}</span>
            {participant.isFinished ? (
              <span
                className="wait-marker wait-marker-done"
                data-testid={`marker-${participant.participantId}`}
              >
                Done
              </span>
            ) : participant.isActive ? (
              <span
                className="wait-marker wait-marker-waiting"
                data-testid={`marker-${participant.participantId}`}
              >
                Waiting
              </span>
            ) : (
              <span className="wait-marker wait-marker-away" data-testid={`marker-${participant.participantId}`}>
                Away
                <span className="wait-away-note">idle, not being waited on</span>
              </span>
            )}
          </li>
        ))}
      </ul>
      {connectionState !== 'connected' && (
        <p role="status" className="wait-reconnecting">
          Reconnecting…
        </p>
      )}
    </section>
  )
}
