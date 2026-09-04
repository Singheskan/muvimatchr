package org.example.muvimatchr.catalog

import org.example.muvimatchr.catalog.MovieCatalogService.CachedProvider
import org.example.muvimatchr.catalog.tmdb.TmdbMovieWatchProvidersResponse

// Kept as a small sibling file (not folded into MovieCatalogClient.kt) so the client stays
// focused on outbound HTTP concerns; this is a pure, client-free response-reading function.
data class RegionalAvailability(val providers: List<CachedProvider>, val watchLink: String?)

// Exact-key lookup on the requested region only -- no fallback to another region, no
// "first available" region, no merge across regions. A wrong-region availability badge is worse
// than no badge at all: it sends someone to a service where the film is not actually watchable.
fun resolveRegionalAvailability(response: TmdbMovieWatchProvidersResponse, region: String): RegionalAvailability {
    val regional = response.results[region] ?: return RegionalAvailability(emptyList(), null)

    // Merging the four monetization categories is deliberate (COVERAGE.md's OPT-OUT on
    // monetization-type scoping): the application does not distinguish subscription from rental
    // anywhere, so one merged "where to watch" list is the honest representation of what was
    // requested. Duplicate provider ids collapse, keeping the first occurrence's name/logo.
    val seenProviderIds = LinkedHashSet<Int>()
    val providers = mutableListOf<CachedProvider>()
    for (summary in regional.flatrate + regional.rent + regional.buy + regional.ads) {
        if (seenProviderIds.add(summary.providerId)) {
            providers.add(CachedProvider(summary.providerId, summary.providerName, summary.logoPath))
        }
    }
    return RegionalAvailability(providers, regional.link)
}
