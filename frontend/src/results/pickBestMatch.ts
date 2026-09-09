import type { DeckMovieResponse, MovieLikeCountResponse } from '../api/types'

// RSLT-02 / D-11 / prohibition P-01: the results screen's only permitted selection function.
// matchedMovieIds is the server's unanimity computation and the sole source of what may ever be
// displayed as "the group's match" -- likeCounts may only reorder that already-unanimous set, and
// must never be consulted to select a film absent from it. MatchAggregationService.perMovieLikeCounts
// is deliberately unfiltered by roster or unanimity, so a film with the session's highest like
// count can easily be one that somebody passed on; presenting that as the group's match would be a
// lie about the one thing this application exists to answer.
export function pickBestMatch({
  matchedMovieIds,
  likeCounts,
  movies,
}: {
  matchedMovieIds: number[]
  likeCounts: MovieLikeCountResponse[]
  movies: DeckMovieResponse[]
}): DeckMovieResponse | null {
  // The single most important line in this file: return null before likeCounts is even read.
  if (matchedMovieIds.length === 0) {
    return null
  }

  const matchedSet = new Set(matchedMovieIds)
  const candidates = movies.filter((m) => matchedSet.has(m.tmdbId))
  if (candidates.length === 0) {
    return null
  }

  const likeCountByMovieId = new Map<number, number>()
  for (const entry of likeCounts) {
    likeCountByMovieId.set(entry.movieId, entry.likeCount)
  }

  // Total, deterministic ordering (flagged assumption A-02): like count desc, then voteAverage
  // desc, then tmdbId asc as the final tiebreak -- every term is server-supplied, so two
  // participants loading results at the same moment can never see different films.
  const sorted = [...candidates].sort((a, b) => {
    const likeDiff = (likeCountByMovieId.get(b.tmdbId) ?? 0) - (likeCountByMovieId.get(a.tmdbId) ?? 0)
    if (likeDiff !== 0) {
      return likeDiff
    }
    const voteDiff = b.voteAverage - a.voteAverage
    if (voteDiff !== 0) {
      return voteDiff
    }
    return a.tmdbId - b.tmdbId
  })

  return sorted[0]
}
