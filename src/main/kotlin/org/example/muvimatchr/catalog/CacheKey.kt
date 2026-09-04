package org.example.muvimatchr.catalog

// Deliberate deviation from 03-RESEARCH.md Pattern 1's example, which collapses the region part
// to "none" whenever the provider list is empty (reasoning: region doesn't affect *sourcing*
// without a provider filter, per D-03). That reasoning became incomplete once D-10 was added:
// each cached row also carries per-movie streaming-provider data resolved for one specific
// region (Plan 03-04 populates it), which makes the row's contents region-dependent even when
// the movie *selection* is not. Collapsing region out of the key would let two sessions with
// different regions share one row and see each other's regional availability. Always include
// the region. D-07 rates this key as costly to change, so getting it right now avoids a later
// re-key that would invalidate this plan's own CTLG-04 cache-hit assertions.
fun buildDeckCacheKey(genreId: Int?, providerIds: List<Int>, region: String?): String {
    val genrePart = genreId?.toString() ?: "none"
    val providerPart = if (providerIds.isEmpty()) "none" else providerIds.sorted().joinToString(",")
    val regionPart = region ?: "none"
    return "genre:$genrePart|provider:$providerPart|region:$regionPart"
}
