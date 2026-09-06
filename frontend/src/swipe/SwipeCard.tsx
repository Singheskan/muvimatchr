import { motion, useMotionValue, useTransform, type PanInfo } from 'motion/react'
import type { DeckMovieResponse, VoteChoice } from '../api/types'
import { SWIPE_COMMIT_DISTANCE, SWIPE_TILT_DEGREES, SWIPE_TILT_RANGE, resolveSwipe } from './swipeDecision'

// w500, not the original resolution -- PITFALLS.md's TMDB image-CDN connection-count/bandwidth
// guidance says to prefer smaller size variants for deck thumbnails.
export const TMDB_IMAGE_BASE = 'https://image.tmdb.org/t/p/w500'

interface SwipeCardProps {
  movie: DeckMovieResponse
  isTop: boolean
  onVote: (choice: VoteChoice) => void
  disabled: boolean
}

export function SwipeCard({ movie, isTop, onVote, disabled }: SwipeCardProps) {
  const x = useMotionValue(0)
  const rotate = useTransform(x, [-SWIPE_TILT_RANGE, 0, SWIPE_TILT_RANGE], [-SWIPE_TILT_DEGREES, 0, SWIPE_TILT_DEGREES])
  const likeOpacity = useTransform(x, [0, SWIPE_COMMIT_DISTANCE], [0, 0.85])
  const passOpacity = useTransform(x, [-SWIPE_COMMIT_DISTANCE, 0], [0.85, 0])

  function handleDragEnd(_event: MouseEvent | TouchEvent | PointerEvent, info: PanInfo) {
    const choice = resolveSwipe({ offsetX: info.offset.x, velocityX: info.velocity.x })
    if (choice !== null) {
      onVote(choice)
    }
  }

  const releaseYear = movie.releaseDate ? movie.releaseDate.slice(0, 4) : null

  return (
    <motion.div
      className="swipe-card"
      style={{ x, rotate }}
      drag={isTop && !disabled ? 'x' : false}
      dragElastic={0.6}
      dragSnapToOrigin
      onDragEnd={handleDragEnd}
    >
      <motion.div className="swipe-card-tint swipe-card-tint-like" style={{ opacity: likeOpacity }}>
        LIKE
      </motion.div>
      <motion.div className="swipe-card-tint swipe-card-tint-pass" style={{ opacity: passOpacity }}>
        PASS
      </motion.div>
      {movie.posterPath ? (
        <>
          <img
            className="swipe-card-poster"
            src={`${TMDB_IMAGE_BASE}${movie.posterPath}`}
            alt={movie.title}
            loading="lazy"
          />
          <div className="swipe-card-info">
            <h2>{movie.title}</h2>
            {releaseYear && <p>{releaseYear}</p>}
          </div>
        </>
      ) : (
        <div className="swipe-card-poster swipe-card-poster-placeholder">
          <div className="swipe-card-info">
            <h2>{movie.title}</h2>
            {releaseYear && <p>{releaseYear}</p>}
          </div>
        </div>
      )}
    </motion.div>
  )
}
