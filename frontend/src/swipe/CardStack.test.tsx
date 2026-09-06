import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CardStack } from './CardStack'
import type { DeckMovieResponse } from '../api/types'

function movie(overrides: Partial<DeckMovieResponse> = {}): DeckMovieResponse {
  return {
    tmdbId: 1,
    title: 'A Movie',
    posterPath: '/poster.jpg',
    genreIds: [],
    voteAverage: 7.5,
    releaseDate: '2020-01-01',
    overview: 'An overview.',
    providers: [],
    watchLink: null,
    ...overrides,
  }
}

describe('CardStack', () => {
  it('renders 3 cards when 5 movies remain', () => {
    const movies = [1, 2, 3, 4, 5].map((id) => movie({ tmdbId: id, title: `Movie ${id}` }))
    render(<CardStack movies={movies} onVote={vi.fn()} disabled={false} />)
    expect(screen.getByText('Movie 1')).toBeInTheDocument()
    expect(screen.getByText('Movie 2')).toBeInTheDocument()
    expect(screen.getByText('Movie 3')).toBeInTheDocument()
    expect(screen.queryByText('Movie 4')).not.toBeInTheDocument()
    expect(screen.queryByText('Movie 5')).not.toBeInTheDocument()
  })

  it('renders 2 cards when 2 movies remain', () => {
    const movies = [1, 2].map((id) => movie({ tmdbId: id, title: `Movie ${id}` }))
    render(<CardStack movies={movies} onVote={vi.fn()} disabled={false} />)
    expect(screen.getByText('Movie 1')).toBeInTheDocument()
    expect(screen.getByText('Movie 2')).toBeInTheDocument()
  })

  it('renders no cards when 0 movies remain', () => {
    render(<CardStack movies={[]} onVote={vi.fn()} disabled={false} />)
    expect(screen.queryByText(/Movie/)).not.toBeInTheDocument()
  })

  it('renders a titled placeholder block when posterPath is null', () => {
    const movies = [movie({ tmdbId: 1, title: 'No Poster', posterPath: null })]
    render(<CardStack movies={movies} onVote={vi.fn()} disabled={false} />)
    expect(screen.getByText('No Poster')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('invokes onVote with LIKE when the like button is clicked', () => {
    const onVote = vi.fn()
    const movies = [movie({ tmdbId: 1, title: 'Movie 1' })]
    render(<CardStack movies={movies} onVote={onVote} disabled={false} />)
    fireEvent.click(screen.getByRole('button', { name: /like/i }))
    expect(onVote).toHaveBeenCalledWith('LIKE')
  })

  it('invokes onVote with PASS when the pass button is clicked', () => {
    const onVote = vi.fn()
    const movies = [movie({ tmdbId: 1, title: 'Movie 1' })]
    render(<CardStack movies={movies} onVote={onVote} disabled={false} />)
    fireEvent.click(screen.getByRole('button', { name: /pass/i }))
    expect(onVote).toHaveBeenCalledWith('PASS')
  })
})
