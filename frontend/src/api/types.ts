// TS mirrors of the backend DTOs, field for field. Keep this file the single source of truth for
// the wire contract the SPA binds to -- later plans should extend it here, not redeclare shapes
// ad hoc elsewhere.

export interface SessionBootstrapResponse {
  sessionId: string
  joinCode: string
  participantId: string
  displayName: string
  deckPinned: boolean
  votedMovieIds: number[]
}

export interface JoinResponse {
  participantId: string
  sessionId: string
  displayName: string
  token: string
  resumeUrl: string
}

export interface DeckProviderResponse {
  providerId: number
  providerName: string
  logoPath: string | null
}

export interface DeckMovieResponse {
  tmdbId: number
  title: string
  posterPath: string | null
  genreIds: number[]
  voteAverage: number
  releaseDate: string | null
  overview: string | null
  providers: DeckProviderResponse[]
  watchLink: string | null
}

export interface DeckResponse {
  sessionId: string
  status: 'ok' | 'insufficient_results'
  stale: boolean
  fetchedAt: string
  totalResults: number
  movies: DeckMovieResponse[]
}

export interface MovieLikeCountResponse {
  movieId: number
  likeCount: number
}

// Pinned to this exact wire name by a @get:JsonProperty("isComplete") on the Kotlin DTO -- do not
// rename to `complete`.
export interface VoteStatusResponse {
  sessionId: string
  deckSize: number
  activeCount: number
  finishedCount: number
  isComplete: boolean
  matchedMovieIds: number[]
  likeCounts: MovieLikeCountResponse[]
}

export type VoteChoice = 'LIKE' | 'PASS'

export interface VoteRequest {
  movieId: number
  choice: VoteChoice
}
