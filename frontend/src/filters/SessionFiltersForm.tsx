import { useState } from 'react'
import { useGenres } from '../session/useGenres'
import { useWatchProviders } from '../session/useWatchProviders'
import './filters.css'

// Mirrors SessionController.kt's MAX_PROVIDER_IDS -- enforced client-side too so the cap is felt
// as a disabled tile, not a submit-time 400.
const MAX_PROVIDER_IDS = 20

// /api/catalog/watch-providers is sorted by TMDB's own displayPriority ascending (Phase 3), but
// that's a global popularity ranking -- it doesn't reliably put every mainstream-in-this-market
// service (RTL+ is a real example: strong in Germany, ranked well outside TMDB's global top 10)
// inside the default cut. These are pinned ahead of TMDB's own ordering, by name (case-insensitive
// substring) so it survives TMDB's exact naming/casing without hardcoding fragile provider ids.
const PINNED_PROVIDER_KEYWORDS = ['netflix', 'disney', 'prime video', 'hbo', 'rtl+', 'apple tv']
// Verified live against real TMDB DE data: a bundle/addon variant of a pinned service (e.g. "HBO
// Max Amazon Channel") can rank *better* than the plain canonical service ("HBO Max" itself), and
// a plain keyword match would pin the bundle instead of the actual service. These are excluded
// regardless of keyword match, everywhere -- TMDB names every region's addon/tier variants with
// one of these, never the canonical entry itself.
const PINNED_PROVIDER_EXCLUDE_KEYWORDS = ['channel', 'store', 'kids', 'with ads', 'free']
const MAINSTREAM_PROVIDER_COUNT = 10

const PROVIDER_LOGO_BASE = 'https://image.tmdb.org/t/p/w92'

function isPinnedProvider(name: string): boolean {
  const lower = name.toLowerCase()
  if (PINNED_PROVIDER_EXCLUDE_KEYWORDS.some((keyword) => lower.includes(keyword))) {
    return false
  }
  return PINNED_PROVIDER_KEYWORDS.some((keyword) => lower.includes(keyword))
}

interface SessionFiltersFormProps {
  token: string
  region: string
  genre: number | ''
  providerIds: number[]
  onRegionChange: (region: string) => void
  onGenreChange: (genre: number | '') => void
  onToggleProvider: (id: number) => void
  saving: boolean
  saved: boolean
  error: string | null
  disabled?: boolean
}

// CTLG-02/CTLG-03 as a real screen: Phase 6 wired the join/swipe/wait/results path but never gave
// any participant a way to actually set genre/region/streaming-provider filters, even though the
// backend has supported PUT /api/sessions/{id}/filters since Phase 3. No host role exists
// (SessionController D-02) -- any participant may open and change this, same as the backend
// allows.
//
// Purely presentational/controlled: LobbyScreen owns the actual filter values and the save call
// (auto-saved, debounced, and explicitly flushed before "Start swiping" pins the deck). A prior
// version of this component owned its own draft state behind a separate "Save filters" button --
// found live to be a real footgun: a provider toggled but not explicitly saved before clicking
// "Start swiping" silently pinned the deck with the *old* filters, so a selected provider had no
// effect on the fetched deck at all.
export function SessionFiltersForm({
  token,
  region,
  genre,
  providerIds,
  onRegionChange,
  onGenreChange,
  onToggleProvider,
  saving,
  saved,
  error,
  disabled = false,
}: SessionFiltersFormProps) {
  const genres = useGenres(token)
  const providers = useWatchProviders(region, token)
  const [showAllProviders, setShowAllProviders] = useState(false)

  // Stable sort: pinned providers float to the front in TMDB's own relative order, unpinned ones
  // keep following behind in that same original (displayPriority) order.
  const allProviders = [...(providers.data ?? [])].sort((a, b) => {
    const aPinned = isPinnedProvider(a.name)
    const bPinned = isPinnedProvider(b.name)
    return aPinned === bPinned ? 0 : aPinned ? -1 : 1
  })
  const visibleProviders = showAllProviders ? allProviders : allProviders.slice(0, MAINSTREAM_PROVIDER_COUNT)
  const hiddenCount = allProviders.length - visibleProviders.length

  return (
    <div className="filters">
      <h2>Filters</h2>
      <div className="filters-row">
        <div>
          <label htmlFor="filter-region">Region</label>
          <input
            id="filter-region"
            value={region}
            maxLength={2}
            onChange={(event) => onRegionChange(event.target.value.toUpperCase())}
            disabled={disabled}
          />
        </div>
        <div>
          <label htmlFor="filter-genre">Genre</label>
          <select
            id="filter-genre"
            value={genre}
            onChange={(event) => onGenreChange(event.target.value === '' ? '' : Number(event.target.value))}
            disabled={disabled}
          >
            <option value="">Any genre</option>
            {(genres.data ?? []).map((g) => (
              <option key={g.id} value={g.id}>
                {g.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      <fieldset className="provider-fieldset">
        <legend>Streaming providers</legend>
        <div className="provider-grid">
          {visibleProviders.map((p) => {
            const selected = providerIds.includes(p.id)
            return (
              <label key={p.id} className={`provider-tile${selected ? ' provider-tile-selected' : ''}`}>
                <input
                  type="checkbox"
                  checked={selected}
                  disabled={disabled || (!selected && providerIds.length >= MAX_PROVIDER_IDS)}
                  onChange={() => onToggleProvider(p.id)}
                />
                {p.logoPath ? (
                  <img className="provider-logo" src={`${PROVIDER_LOGO_BASE}${p.logoPath}`} alt="" loading="lazy" />
                ) : (
                  <span className="provider-logo provider-logo-placeholder" aria-hidden="true">
                    {p.name.slice(0, 1)}
                  </span>
                )}
                <span className="provider-name">{p.name}</span>
              </label>
            )
          })}
        </div>
        {allProviders.length > MAINSTREAM_PROVIDER_COUNT && (
          <button type="button" className="provider-toggle" onClick={() => setShowAllProviders((v) => !v)}>
            {showAllProviders ? 'Show fewer' : `Show ${hiddenCount} more`}
          </button>
        )}
      </fieldset>

      <p role="status" className="filters-status">
        {saving ? 'Saving…' : saved ? 'Filters saved.' : ' '}
      </p>
      {error && <p role="alert">{error}</p>}
    </div>
  )
}
