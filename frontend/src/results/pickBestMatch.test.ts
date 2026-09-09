import { describe, expect, it } from 'vitest'
import type { DeckMovieResponse, MovieLikeCountResponse } from '../api/types'
import { pickBestMatch } from './pickBestMatch'

function movie(overrides: Partial<DeckMovieResponse> & { tmdbId: number }): DeckMovieResponse {
  return {
    title: `Movie ${overrides.tmdbId}`,
    posterPath: null,
    genreIds: [],
    voteAverage: 5,
    releaseDate: '2020-01-01',
    overview: null,
    providers: [],
    watchLink: null,
    ...overrides,
  }
}

function likeCount(movieId: number, likeCount: number): MovieLikeCountResponse {
  return { movieId, likeCount }
}

describe('pickBestMatch', () => {
  it('returns null when matchedMovieIds is empty, regardless of likeCounts -- including a movie with the highest like count of the whole session', () => {
    const result = pickBestMatch({
      matchedMovieIds: [],
      likeCounts: [likeCount(99, 1000)],
      movies: [movie({ tmdbId: 99 }), movie({ tmdbId: 10 })],
    })
    expect(result).toBeNull()
  })

  it('returns null when matchedMovieIds contains only ids absent from the movies array, rather than inventing a card', () => {
    const result = pickBestMatch({
      matchedMovieIds: [999],
      likeCounts: [],
      movies: [movie({ tmdbId: 10 }), movie({ tmdbId: 20 })],
    })
    expect(result).toBeNull()
  })

  it('picks the movie with the higher like count among matched ids', () => {
    const result = pickBestMatch({
      matchedMovieIds: [10, 20],
      likeCounts: [likeCount(10, 1), likeCount(20, 5)],
      movies: [movie({ tmdbId: 10 }), movie({ tmdbId: 20 })],
    })
    expect(result?.tmdbId).toBe(20)
  })

  it('breaks a like-count tie by the higher voteAverage', () => {
    const result = pickBestMatch({
      matchedMovieIds: [10, 20],
      likeCounts: [likeCount(10, 3), likeCount(20, 3)],
      movies: [movie({ tmdbId: 10, voteAverage: 8.5 }), movie({ tmdbId: 20, voteAverage: 6.0 })],
    })
    expect(result?.tmdbId).toBe(10)
  })

  it('breaks a like-count and voteAverage tie by the lowest tmdbId, so the choice is total', () => {
    const result = pickBestMatch({
      matchedMovieIds: [30, 10],
      likeCounts: [likeCount(30, 2), likeCount(10, 2)],
      movies: [movie({ tmdbId: 30, voteAverage: 7 }), movie({ tmdbId: 10, voteAverage: 7 })],
    })
    expect(result?.tmdbId).toBe(10)
  })

  it('is pure and returns the same result for the same input across repeated calls', () => {
    const input = {
      matchedMovieIds: [30, 10, 20],
      likeCounts: [likeCount(30, 2), likeCount(10, 5), likeCount(20, 5)],
      movies: [movie({ tmdbId: 30, voteAverage: 7 }), movie({ tmdbId: 10, voteAverage: 9 }), movie({ tmdbId: 20, voteAverage: 9 })],
    }
    const first = pickBestMatch(input)
    const second = pickBestMatch(input)
    expect(first).toEqual(second)
    expect(first?.tmdbId).toBe(10)
  })

  it('never returns the session-wide highest-like-count film when it is not in matchedMovieIds', () => {
    const result = pickBestMatch({
      matchedMovieIds: [10],
      likeCounts: [likeCount(10, 1), likeCount(99, 1000)],
      movies: [movie({ tmdbId: 10 }), movie({ tmdbId: 99 })],
    })
    expect(result?.tmdbId).toBe(10)
  })
})
