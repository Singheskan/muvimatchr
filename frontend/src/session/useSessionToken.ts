import { useSearchParams } from 'react-router'

// D-05: the participant's bearer token stays in the URL query string on the resume/share link
// permanently -- read on every load, never copied into browser storage, never scrubbed from the
// address bar. This is the already-accepted Phase 2 risk (see 02-REVIEW.md); this phase adds no
// new mitigation for it.
export function useSessionToken(): string | null {
  const [searchParams] = useSearchParams()
  return searchParams.get('token')
}
