import type { VoteChoice } from '../api/types'

// D-07's tuning constants. These are the intended tuning point for feel-level adjustments
// requested during the plan's human-verify checkpoint -- change values here, never inline.
export const SWIPE_COMMIT_DISTANCE = 120
export const SWIPE_COMMIT_VELOCITY = 500
export const SWIPE_MIN_INTENT_DISTANCE = 40
export const SWIPE_TILT_DEGREES = 18
export const SWIPE_TILT_RANGE = 200

export interface SwipeGesture {
  offsetX: number
  velocityX: number
}

// Pure and separately unit-tested so the commit rule (prohibition P-03) never depends on any
// rendering or gesture-library behavior to verify. Rules apply in order:
// 1. Distance past the commit threshold always commits, regardless of velocity.
// 2. A fast flick commits only when paired with matching-sign offset past the minimum intent
//    distance -- SWIPE_MIN_INTENT_DISTANCE is what stops a correction flick (fast movement in
//    the *opposite* direction of an accidental drag) from registering as a deliberate vote.
// 3. Otherwise no vote: an incidental tap (offsetX 0, velocityX 0) always resolves to null.
export function resolveSwipe({ offsetX, velocityX }: SwipeGesture): VoteChoice | null {
  if (offsetX >= SWIPE_COMMIT_DISTANCE) {
    return 'LIKE'
  }
  if (offsetX <= -SWIPE_COMMIT_DISTANCE) {
    return 'PASS'
  }

  if (velocityX >= SWIPE_COMMIT_VELOCITY && offsetX >= SWIPE_MIN_INTENT_DISTANCE) {
    return 'LIKE'
  }
  if (velocityX <= -SWIPE_COMMIT_VELOCITY && offsetX <= -SWIPE_MIN_INTENT_DISTANCE) {
    return 'PASS'
  }

  return null
}
