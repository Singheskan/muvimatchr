---
status: testing
phase: 05-real-time-notification-layer
source: [05-VERIFICATION.md]
started: 2026-09-06T08:35:00Z
updated: 2026-09-06T08:35:00Z
---

## Current Test

number: 1
name: Startup log `/ws` endpoint confirmation + dead `lobby.html` socket
expected: |
  Startup log shows a plain WebSocket STOMP endpoint registered at /ws with no fallback
  transport (no SockJS). Opening templates/lobby.html in a browser shows its SockJS
  client failing to establish a socket (the endpoint no longer accepts SockJS handshakes
  since Task 1 removed withSockJS()).
awaiting: user response

## Tests

### 1. Startup log `/ws` endpoint confirmation + dead `lobby.html` socket
expected: Startup log shows a plain WebSocket STOMP endpoint at /ws; lobby.html's SockJS connection fails or never establishes.
result: [pending]

### 2. Manual RTIME-03 walkthrough against a running instance
expected: |
  Open a STOMP connection, kill it, vote from a different client/session, reconnect the
  first client, and confirm the reconnected socket stays silent while a REST
  GET /api/sessions/{id}/votes/status call returns the finished state. No frame arrives
  on the reconnected socket; REST call returns the correct, current, completed status.
  (Note: ReconnectReconciliationTest already automates and passes this identical property
  end-to-end — this is the plans' own requested manual sanity-check on top of that proof.)
result: [pending]

## Summary

total: 2
passed: 0
issues: 0
pending: 2
skipped: 0
blocked: 0

## Gaps
