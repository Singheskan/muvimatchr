import { describe, expect, it } from 'vitest'
import {
  SWIPE_COMMIT_DISTANCE,
  SWIPE_COMMIT_VELOCITY,
  SWIPE_MIN_INTENT_DISTANCE,
  resolveSwipe,
} from './swipeDecision'

describe('resolveSwipe', () => {
  it('returns LIKE at or beyond the positive commit distance', () => {
    expect(resolveSwipe({ offsetX: SWIPE_COMMIT_DISTANCE, velocityX: 0 })).toBe('LIKE')
    expect(resolveSwipe({ offsetX: SWIPE_COMMIT_DISTANCE + 50, velocityX: 0 })).toBe('LIKE')
  })

  it('returns PASS at or beyond the negative commit distance', () => {
    expect(resolveSwipe({ offsetX: -SWIPE_COMMIT_DISTANCE, velocityX: 0 })).toBe('PASS')
    expect(resolveSwipe({ offsetX: -SWIPE_COMMIT_DISTANCE - 50, velocityX: 0 })).toBe('PASS')
  })

  it('returns null strictly between the commit thresholds with no velocity', () => {
    expect(resolveSwipe({ offsetX: 0, velocityX: 0 })).toBeNull()
    expect(resolveSwipe({ offsetX: 50, velocityX: 0 })).toBeNull()
    expect(resolveSwipe({ offsetX: -50, velocityX: 0 })).toBeNull()
  })

  it('asserts the exact 119-vs-120 boundary', () => {
    expect(resolveSwipe({ offsetX: 119, velocityX: 0 })).toBeNull()
    expect(resolveSwipe({ offsetX: 120, velocityX: 0 })).toBe('LIKE')
    expect(resolveSwipe({ offsetX: -119, velocityX: 0 })).toBeNull()
    expect(resolveSwipe({ offsetX: -120, velocityX: 0 })).toBe('PASS')
  })

  it('returns LIKE for a fast rightward flick paired with sufficient rightward offset', () => {
    expect(
      resolveSwipe({ offsetX: SWIPE_MIN_INTENT_DISTANCE, velocityX: SWIPE_COMMIT_VELOCITY }),
    ).toBe('LIKE')
    expect(
      resolveSwipe({ offsetX: SWIPE_MIN_INTENT_DISTANCE + 10, velocityX: SWIPE_COMMIT_VELOCITY + 100 }),
    ).toBe('LIKE')
  })

  it('returns PASS for a fast leftward flick paired with sufficient leftward offset (symmetric)', () => {
    expect(
      resolveSwipe({ offsetX: -SWIPE_MIN_INTENT_DISTANCE, velocityX: -SWIPE_COMMIT_VELOCITY }),
    ).toBe('PASS')
    expect(
      resolveSwipe({ offsetX: -SWIPE_MIN_INTENT_DISTANCE - 10, velocityX: -SWIPE_COMMIT_VELOCITY - 100 }),
    ).toBe('PASS')
  })

  it('returns null for a rightward velocity flick paired with a leftward offset (correction flick)', () => {
    expect(resolveSwipe({ offsetX: -10, velocityX: SWIPE_COMMIT_VELOCITY })).toBeNull()
  })

  it('returns null for a leftward velocity flick paired with a rightward offset (correction flick)', () => {
    expect(resolveSwipe({ offsetX: 10, velocityX: -SWIPE_COMMIT_VELOCITY })).toBeNull()
  })

  it('returns null when velocity meets threshold but offset is below the minimum intent distance', () => {
    expect(
      resolveSwipe({ offsetX: SWIPE_MIN_INTENT_DISTANCE - 1, velocityX: SWIPE_COMMIT_VELOCITY }),
    ).toBeNull()
    expect(
      resolveSwipe({ offsetX: -(SWIPE_MIN_INTENT_DISTANCE - 1), velocityX: -SWIPE_COMMIT_VELOCITY }),
    ).toBeNull()
  })

  it('returns null for offsetX 0 and velocityX 0 (incidental tap)', () => {
    expect(resolveSwipe({ offsetX: 0, velocityX: 0 })).toBeNull()
  })
})
