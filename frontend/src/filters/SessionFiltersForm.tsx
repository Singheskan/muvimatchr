import { useEffect, useState } from 'react'
import { ApiError, updateSessionFilters } from '../api/client'
import { useGenres } from '../session/useGenres'
import { useSessionFilters } from '../session/useSessionFilters'
import { useWatchProviders } from '../session/useWatchProviders'
import './filters.css'

// Mirrors SessionController.kt's MAX_PROVIDER_IDS -- enforced client-side too so the cap is felt
// as a disabled tile, not a submit-time 400.
const MAX_PROVIDER_IDS = 20

// /api/catalog/watch-providers is already sorted by TMDB's own displayPriority ascending (Phase 3)
// -- the mainstream, globally-recognizable services genuinely do come first. Showing only that
// head by default (with an explicit expand) is "the mainstream ones first" without hiding anyone.
const MAINSTREAM_PROVIDER_COUNT = 10

const PROVIDER_LOGO_BASE = 'https://image.tmdb.org/t/p/w92'

interface SessionFiltersFormProps {
  sessionId: string
  token: string
}

// CTLG-02/CTLG-03 as a real screen: Phase 6 wired the join/swipe/wait/results path but never gave
// any participant a way to actually set genre/region/streaming-provider filters, even though the
// backend has supported PUT /api/sessions/{id}/filters since Phase 3. No host role exists
// (SessionController D-02) -- any participant may open and change this, same as the backend
// allows.
export function SessionFiltersForm({ sessionId, token }: SessionFiltersFormProps) {
  const filters = useSessionFilters(sessionId, token)
  const genres = useGenres(token)

  const [region, setRegion] = useState('')
  const [genre, setGenre] = useState<number | ''>('')
  const [providerIds, setProviderIds] = useState<number[]>([])
  const [initialized, setInitialized] = useState(false)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [showAllProviders, setShowAllProviders] = useState(false)

  // Seed local editable state from the server once, on first load -- never again, so the form
  // doesn't stomp in-progress edits if the query happens to refetch.
  useEffect(() => {
    if (filters.data && !initialized) {
      setRegion(filters.data.region)
      setGenre(filters.data.genre ?? '')
      setProviderIds(filters.data.providerIds)
      setInitialized(true)
    }
  }, [filters.data, initialized])

  const providers = useWatchProviders(region, token)
  const allProviders = providers.data ?? []
  const visibleProviders = showAllProviders ? allProviders : allProviders.slice(0, MAINSTREAM_PROVIDER_COUNT)
  const hiddenCount = allProviders.length - visibleProviders.length

  function toggleProvider(id: number) {
    setProviderIds((current) => {
      if (current.includes(id)) {
        return current.filter((p) => p !== id)
      }
      if (current.length >= MAX_PROVIDER_IDS) {
        return current
      }
      return [...current, id]
    })
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    setSaving(true)
    setSaved(false)
    setError(null)
    try {
      await updateSessionFilters(sessionId, token, {
        region,
        genre: genre === '' ? null : genre,
        providerIds,
      })
      setSaved(true)
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 409) {
          setError('Filters are locked -- the deck has already been pinned.')
        } else {
          setError(err.message)
        }
      } else {
        setError('Something went wrong. Please try again.')
      }
    } finally {
      setSaving(false)
    }
  }

  if (filters.isLoading || !initialized) {
    return <p role="status">Loading filters…</p>
  }

  return (
    <div className="filters">
      <h2>Filters</h2>
      <form onSubmit={handleSubmit}>
        <div className="filters-row">
          <div>
            <label htmlFor="filter-region">Region</label>
            <input
              id="filter-region"
              value={region}
              maxLength={2}
              onChange={(event) => setRegion(event.target.value.toUpperCase())}
            />
          </div>
          <div>
            <label htmlFor="filter-genre">Genre</label>
            <select
              id="filter-genre"
              value={genre}
              onChange={(event) => setGenre(event.target.value === '' ? '' : Number(event.target.value))}
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
                <label
                  key={p.id}
                  className={`provider-tile${selected ? ' provider-tile-selected' : ''}`}
                >
                  <input
                    type="checkbox"
                    checked={selected}
                    disabled={!selected && providerIds.length >= MAX_PROVIDER_IDS}
                    onChange={() => toggleProvider(p.id)}
                  />
                  {p.logoPath ? (
                    <img
                      className="provider-logo"
                      src={`${PROVIDER_LOGO_BASE}${p.logoPath}`}
                      alt=""
                      loading="lazy"
                    />
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

        <button type="submit" disabled={saving} className="btn-primary">
          Save filters
        </button>
      </form>
      {saved && <p role="status">Filters saved.</p>}
      {error && <p role="alert">{error}</p>}
    </div>
  )
}
