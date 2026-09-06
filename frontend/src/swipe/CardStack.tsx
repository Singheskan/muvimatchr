import type { DeckMovieResponse, VoteChoice } from '../api/types'
import { SwipeCard } from './SwipeCard'

interface CardStackProps {
  movies: DeckMovieResponse[]
  onVote: (choice: VoteChoice) => void
  disabled: boolean
}

// D-06: 2-3 card depth. Only the front card (index 0) is draggable; the cards behind it are
// visually scaled/offset and receive no drag handlers at all (SwipeCard's `isTop` prop gates
// `drag`, so they simply cannot be dragged, not just visually indicated as such).
const STACK_DEPTH = 3
const STACK_OFFSETS = [
  { scale: 1, translateY: 0 },
  { scale: 0.95, translateY: 12 },
  { scale: 0.9, translateY: 24 },
]

export function CardStack({ movies, onVote, disabled }: CardStackProps) {
  const visible = movies.slice(0, STACK_DEPTH)

  return (
    <div className="card-stack">
      {/* Reverse paint order so index 0 (the top, interactive card) paints last / on top. */}
      <div className="card-stack-cards">
        {visible
          .map((movie, index) => ({ movie, index }))
          .reverse()
          .map(({ movie, index }) => {
            const offset = STACK_OFFSETS[index]
            return (
              <div
                key={movie.tmdbId}
                className="card-stack-slot"
                style={{
                  transform: `translateY(${offset.translateY}px) scale(${offset.scale})`,
                  zIndex: STACK_DEPTH - index,
                }}
              >
                <SwipeCard movie={movie} isTop={index === 0} onVote={onVote} disabled={disabled} />
              </div>
            )
          })}
      </div>
      <div className="swipe-actions">
        <button type="button" className="swipe-action-pass" onClick={() => onVote('PASS')} disabled={disabled}>
          Pass
        </button>
        <button type="button" className="swipe-action-like" onClick={() => onVote('LIKE')} disabled={disabled}>
          Like
        </button>
      </div>
    </div>
  )
}
