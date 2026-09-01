# Feature Research

**Domain:** Group movie-matching / swipe-to-decide apps ("Tinder for movies")
**Researched:** 2026-09-01
**Confidence:** MEDIUM overall (individual claims tagged below; this niche has thin official documentation — most apps market outcomes, not mechanics)

## Research Method & Caveat

This category is dominated by small indie/solo-dev apps (App Store/Play Store listings, a handful of blog reviews) rather than apps with public engineering write-ups. Confidence per claim reflects: **MEDIUM** = pattern repeated independently across 3+ apps (cross-verified), **LOW** = seen in only one or two sources or inferred from marketing copy rather than documented behavior. No claim in this file should be treated as HIGH confidence — treat all "how it works" claims as informed inference, not verified fact, and validate cheaply with your own testing of Matched/MatchaFilm/Movie Swiper if a decision is load-bearing.

Apps reviewed: Matched (Movie App For Couples), MatchaFilm, Movie Swiper, Movie Matchup, FlickFix, Reelgood's Swipe With Friends, Netflip, MatchWatch, Movie Night Matcher, plus general filter-forward pickers (CineMatch, FlickPick, MovieShaker, NightPicks, The Movie App) and general dating-app swipe UX (Tinder/Bumble pattern literature).

## Feature Landscape

### Table Stakes (Users Expect These)

| Feature | Why Expected | Complexity | Notes |
|---------|--------------|------------|-------|
| Card-based swipe deck (right = like, left = pass) | Universal across every app surveyed — this *is* the category's defining interaction, borrowed directly from Tinder | LOW–MEDIUM | One card at a time, single clear binary decision reduces cognitive load. **MEDIUM confidence** (cross-verified in Matched, MatchaFilm, Movie Swiper, Netflip, FlickFix, Reelgood). |
| No-account session join via code/link | Movie Matchup and FlickFix both lead their marketing with "no accounts, no profiles, no setup" + a short join code (6-digit or shareable URL) | LOW | Matches MuviMatchr's confirmed v1 scope exactly. **MEDIUM confidence** (2 independent apps, same pattern, consistent with dating-app-adjacent group tools generally). |
| Genre filter on the deck | Every filter-capable app surveyed includes genre as a baseline filter; it's the minimum "make the deck relevant" lever | LOW | Table stakes even in apps with no other filters. **MEDIUM confidence**. |
| Streaming-availability filter | JustWatch-adjacent value prop; CineMatch, FlickPick, NightPicks, The Movie App all filter by streaming service; core reason these apps exist over a plain "random movie" picker | MEDIUM | Requires TMDB watch-provider data + region handling. **MEDIUM confidence**. |
| Match = mutual "like" (both/all swipe right on the same title) | Universal for 2-person (couple) flows: Matched, MatchaFilm, Movie Swiper, Netflip, Reelgood all define a base "match" as both people liking the same title | LOW–MEDIUM | For groups >2 this fractures into different rules (see Feature Dependencies below) — but for the couple case (MuviMatchr's primary use case) "both liked it" is the unambiguous, expected baseline. **MEDIUM confidence**. |
| Match reveal / result screen with poster + where to watch | Every app surveyed shows the matched title with a "watch on X" call to action once a match is found | LOW | Table stakes payoff moment — the whole point of the interaction. **MEDIUM confidence**. |
| Notification/signal when a match occurs | FlickFix: "instantly notifies you"; Reelgood surfaces matches in a dedicated "Matches" tab | LOW | For async use this becomes the waiting/live-update screen MuviMatchr already scoped. **LOW confidence** on exact mechanism (push notification vs. in-app tab varies), but the *expectation* of being told is consistent. |

### Differentiators (Competitive Advantage)

| Feature | Value Proposition | Complexity | Notes |
|---------|-------------------|------------|-------|
| Live "waiting on N people" status during async voting | Most surveyed apps are vague or silent on async behavior — Movie Matchup's copy actually leans synchronous ("create in seconds," fast group game), and none advertise a persistent live waiting room. A well-built async status screen is a genuine gap in the category. | MEDIUM (WebSocket + per-participant completion tracking) | Already scoped in PROJECT.md — research confirms this is *not* solved well by competitors, so it's a real differentiator, not table stakes to merely match. **LOW confidence** (absence of a feature is harder to verify than presence — inferred from lack of any mention across 9+ listings). |
| Session/vote persistence across restarts (no data loss) | Most of these are small indie apps; several explicitly market sessions as "private and ephemeral, with nothing saved" (Movie Matchup) — i.e., competitors treat ephemerality as a feature, not persistence as a guarantee | LOW–MEDIUM (this is a backend/reliability property, not a user-facing "feature" in itself, but its *absence* was the prior MuviMatchr prototype's core failure) | Not something competitors compete on visibly, but reliability under restart is exactly the gap the prior prototype fell into — worth treating as a genuine differentiator for a "tool you actually trust with tonight's plans." |
| Group support (3+) with a coherent match rule beyond couples | Most apps in this space are explicitly couple-first (Matched, MatchaFilm, Netflip); apps that do support groups (Movie Swiper, Movie Matchup, FlickFix) are vague about exactly how matching generalizes past 2 people | MEDIUM–HIGH | This is where MuviMatchr's "groups supported from v1" claim is genuinely more ambitious than most direct competitors — see Pitfall-adjacent note below on match-rule ambiguity. **LOW confidence** on competitor internals (none document their n>2 rule publicly). |
| Runtime / release-year / star-rating filters (beyond genre + streaming) | CineMatch, FlickPick, MovieShaker, and NightPicks all layer these on top of genre+streaming; matching-focused apps (Movie Matchup, FlickFix) tend to *not* have them | LOW–MEDIUM | Confirms the v1 scope decision to launch with genre+streaming only is reasonable — these are a natural, low-risk v1.x add, not something users will call "broken" without. **MEDIUM confidence** (4 independent apps share this filter set). |
| Ranked / near-miss results ("closest matches" when no unanimous hit) | "Movie Night" app explicitly falls back to "start again with different search options" when there's no match — a weak, punt-to-the-user fallback. Nothing surveyed shows a good near-miss ranked list. Decider-wheel apps (a separate app category entirely: Decider, Wheel Decide, "The Decider" movie wheel) exist specifically because plain matching-tools leave people stuck with no resolution. | MEDIUM (requires storing per-movie vote *counts*, not just a match boolean) | Directly supported by MuviMatchr's stated data-model requirement ("shouldn't block adding a ranked list later") — building the aggregate-count layer now, even while surfacing only the single best match in v1 UI, is validated by this gap in the market. **LOW confidence** (small sample, but the gap itself — no competitor has a polished no-match story — is a real and recurring absence across every source checked). |
| Undo last swipe | Standard, expected affordance in dating-app swipe UX generally (Tinder's "Rewind"); not consistently present in movie-swipe apps surveyed | LOW | Cheap to build (client-side history stack), reduces "oops" friction; a nice-to-have carried over from the broader swipe-UX pattern literature rather than something this specific niche has standardized on. **LOW confidence** for movie apps specifically; **MEDIUM** as a general swipe-UX best practice. |

### Anti-Features (Commonly Requested, Often Problematic)

| Feature | Why Requested | Why Problematic | Alternative |
|---------|---------------|------------------|-------------|
| ML-based taste modeling / personalized recommendations | Matched's marketing ("compares to favorites of similar-minded viewers," "the more you swipe the better it gets") makes this look like the obvious next step and a competitive edge | Needs a large cross-user rating corpus to be worth anything; cold-start is bad for a casual one-off-session tool; already explicitly out of scope in PROJECT.md and doubles the surface area of "what could be wrong" in results | Genre + streaming filters (already scoped) plus TMDB's own popularity/rating sort as a deck-ordering signal — gets most of the perceived benefit with none of the ML infra. |
| True simultaneous real-time swiping (live cursor/sync between devices) | Feels "more Tinder-like" and technically flashy; Movie Night Matcher markets "vote together in real-time" as a selling point | Actual usage pattern for this product (per PROJECT.md) is async — people swipe on their own schedule; simultaneous sync adds WebSocket race-condition complexity (conflicting card order, double-voting on reconnect) for a UX nobody in the target use case (couples deciding what to watch tonight, often not even in the same room) will use | Async voting + a well-built live waiting/status screen (already scoped) — delivers the "feels alive" payoff without the synchronization complexity. |
| Accounts / auth / persistent user profiles | Feels like the "grown-up" way to build a real product, and unlocks cross-session history | Adds signup friction to a casual, low-commitment "watch party" tool where the whole point is zero-friction entry; explicitly out of scope in PROJECT.md | Code/link + display name (already scoped); revisit only if usage data shows people returning across many separate sessions and wanting history. |
| Anonymous voting within the session | Movie Matchup markets this as reducing "loudest voice wins" bias | For MuviMatchr's primary use case (a couple), anonymity is meaningless — each person already knows who's in the session — and for small friend groups it adds UI complexity (hiding attribution, extra copy) for a bias problem that mostly matters in large-group settings this app isn't targeting in v1 | Skip anonymity in v1; if group size grows and social-pressure bias becomes a real complaint, revisit then. |
| Majority/weighted voting as the *primary* result rule | Looks more "fair" for groups and avoids the "no unanimous match" dead end | For the couple case (n=2, the primary use case), majority and unanimity are mathematically identical, so building a general weighted-voting engine now solves a problem MuviMatchr doesn't yet have, while adding real ambiguity (what does "majority" mean with 3 people and no tiebreak?) that no competitor has solved cleanly either | Define v1's single rule explicitly as "liked by everyone in the session" (unanimous), store per-movie like-counts so a majority/ranked fallback can be *added* later without a data model change — see Feature Dependencies. |

## Feature Dependencies

```
Swipe deck (per-participant vote persistence)
    └──requires──> Session + participant model (join code, display name)

Match determination ("liked by everyone")
    └──requires──> Swipe deck (per-participant vote persistence)
    └──requires──> Per-movie vote aggregation (count of likes, not just boolean AND)

Live "waiting on N people" screen
    └──requires──> Per-participant completion tracking
    └──requires──> WebSocket (or equivalent) push channel

Auto-transition to results
    └──requires──> Live "waiting on N people" screen
    └──requires──> Match determination

Ranked / runner-up list (v2)
    └──requires──> Per-movie vote aggregation (already needed for match determination — do NOT model votes as a single boolean per movie, or this becomes a rewrite)

No-match fallback (closest pick / decider wheel / relax filters)
    └──requires──> Per-movie vote aggregation
    └──requires──> An explicit v1 decision on what "closest" means (most likes short of unanimous, vs. re-swipe with relaxed filters)

Runtime / year / rating filters ──enhances──> Genre + streaming filters (same filter UI/query layer, additive)

Undo last swipe ──enhances──> Swipe deck (client-side affordance, does not touch match logic)

Group support (3+) ──conflicts-with-naive-implementation-of──> "Match = liked by everyone" at scale
    (the more participants, the less likely a strict unanimous match exists — this is a real tension,
    not a bug; v1's explicit choice to keep unanimous-for-all and defer ranked/majority modes to v2
    is the correct way to sidestep the conflict for now)
```

### Dependency Notes

- **Match determination requires per-movie vote aggregation, not a boolean:** This is the single most important structural finding from this research. Every app that convincingly supports groups beyond 2 (or plans a ranked list) must store *how many* participants liked each title, not just whether a title reached full consensus. PROJECT.md already anticipates this ("data model shouldn't block adding a ranked list later") — this research confirms it's the right call and flags it as a hard requirement on the schema, not a nice-to-have.
- **No-match fallback requires an explicit v1 decision, and the market hasn't solved this well:** Every competitor surveyed is either silent on this scenario or punts to "try again with different filters." MuviMatchr should decide now (even if the decision is "for v1, if there's no unanimous match, show the title with the most likes, labeled as a runner-up, not a match") rather than leaving it undefined until it's discovered in testing.
- **Group support conflicts with strict unanimity at scale:** This isn't a bug to fix, it's a known tension the app should acknowledge rather than solve prematurely. Recommendation: keep "unanimous = match" as the v1 rule (it's simple, expected from the couple-flow pattern, and matches PROJECT.md's stated primary use case), but make sure the aggregation layer doesn't hard-code "match requires ALL participants" in a way that blocks adding a threshold-based rule (e.g., "matched for 4 of 5") later.

## MVP Definition

### Launch With (v1)

Matches PROJECT.md's confirmed scope — validated as reasonable table stakes by this research, not over- or under-scoped relative to the competitive set:

- [ ] Card-based swipe deck (right = like, left = pass) — the category-defining interaction; anything else would feel like a different product
- [ ] Code/link join, display name only, no accounts — this is now the *expected* pattern for casual session-based tools (Movie Matchup, FlickFix both lead with it), not a corner-cutting shortcut
- [ ] Genre + streaming-availability filters — universal baseline across every app surveyed; nothing more is required to feel complete
- [ ] Match = liked by every participant in the session — matches the couple-flow pattern nearly every competitor uses as their base case
- [ ] Async voting with persisted per-participant progress — table stakes for the stated usage pattern (people swipe on their own time)
- [ ] Live "waiting on N people" status + auto-transition to results — a genuine gap in the competitive set; building this well is a differentiator, not just parity
- [ ] Per-movie vote-count aggregation in the data model (even though v1 UI only shows the single best match) — structural prerequisite for v2's ranked list and for any future no-match fallback; skipping this now is the exact mistake the prior prototype made

### Add After Validation (v1.x)

- [ ] Runtime / release-year / rating filters — cheap, additive, several competitors have them; add once genre+streaming filtering is proven to work end-to-end
- [ ] Explicit no-match fallback UX (e.g., show highest-vote-count title as a labeled "runner-up," or offer a re-swipe with relaxed filters) — trigger: real sessions hitting "no unanimous match" during early usage (a couple with divergent taste will hit this quickly)
- [ ] Undo last swipe — trigger: user feedback that mis-swipes are a common frustration
- [ ] Ranked list of runner-up matches as a secondary display (not replacing the single best match) — trigger: users asking "what else did we both sort of like"

### Future Consideration (v2+)

- [ ] Majority/threshold-based matching for larger groups (e.g., "matched for 4 of 5 people") — defer until real group-size usage data shows strict unanimity is failing too often at n>2
- [ ] Weighted votes / "superlike" — defer; not a pattern any competitor in this niche has adopted, unclear it solves a real problem for movie night specifically
- [ ] ML-based personalized recommendations — explicitly out of scope per PROJECT.md; would need a much larger user base and rating history than a casual session tool naturally accumulates
- [ ] True simultaneous real-time swiping — explicitly out of scope per PROJECT.md; the competitive research found no evidence this UX pattern is actually valued over async by the target use case

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---------|------------|---------------------|----------|
| Swipe deck + code/link join + display name | HIGH | LOW–MEDIUM | P1 |
| Genre + streaming filters | HIGH | MEDIUM | P1 |
| Unanimous match determination + vote-count aggregation | HIGH | MEDIUM | P1 |
| Async voting + persistence across restarts | HIGH | MEDIUM | P1 |
| Live waiting screen + auto-transition | HIGH | MEDIUM | P1 |
| Runtime / year / rating filters | MEDIUM | LOW | P2 |
| No-match fallback UX | MEDIUM–HIGH | LOW–MEDIUM | P2 |
| Undo last swipe | LOW–MEDIUM | LOW | P2 |
| Ranked runner-up list (UI surface) | MEDIUM | LOW (if data model built right in P1) / HIGH (if not) | P2 |
| Majority/threshold matching for large groups | LOW (unproven need) | HIGH | P3 |
| ML personalization | LOW (out of scope) | HIGH | P3 |
| Simultaneous real-time swiping | LOW (out of scope) | HIGH | P3 |

**Priority key:**
- P1: Must have for launch
- P2: Should have, add when possible
- P3: Nice to have, future consideration

## Competitor Feature Analysis

| Feature | Matched / MatchaFilm / Netflip (couple-first) | Movie Matchup / FlickFix (group-first) | Our Approach |
|---------|------------------------------------------------|------------------------------------------|--------------|
| Session join | In-app, likely lightweight signup or device-based | Short code / URL, no accounts | Code/link + display name (matches group-first pattern, which is closer to our scoped UX) |
| Match rule | Both swipe right (strict unanimous-of-2) | Undisclosed "consensus" algorithm, marketed as avoiding "loudest voice wins" | Unanimous-of-all-participants for v1; store counts to support a fallback/ranked mode later |
| Filters | Mood, streaming, some taste-learning | Largely undisclosed; FlickFix has streaming + genre | Genre + streaming for v1 (matches the more transparent group-first apps) |
| No-match handling | Undocumented / not surfaced | Undocumented / not surfaced | Explicit v1.x decision: show highest-vote-count runner-up rather than leaving users stuck |
| Async support | Unclear, marketing implies taste-building over time rather than live sessions | FlickFix leans synchronous ("instantly notifies"); Movie Matchup markets speed over async | Async-first with live waiting screen — the gap this research found nobody has nailed |
| Persistence guarantees | Unknown (not marketed) | Movie Matchup explicitly markets ephemerality ("nothing saved") as a feature | Explicit persistence across restarts — a deliberate differentiator, direct fix for the prior prototype's biggest failure |

## Sources

- [Matched: Movie App For Couples — App Store](https://apps.apple.com/us/app/matched-movie-app-for-couples/id1623287922) — MEDIUM (official listing, cross-verified by independent local-news coverage)
- ["Matched" App Helps Couples Agree on Which Movie to Watch — ABC 6 / kaaltv.com](https://www.kaaltv.com/6-on-your-side/what-the-tech/matched-app-helps-couples-agree-on-which-movie-to-watch/) — MEDIUM
- [MatchaFilm — Film Matcher & Movie Picker for Couples](https://matchafilm.app/) — LOW (single-source, marketing copy)
- [Movie Swiper — Google Play](https://play.google.com/store/apps/details?id=com.github.freshmorsikov.moviematcher&hl=en_US) — LOW
- [Netflip Is Essentially Tinder For Movies — Android Headlines](https://www.androidheadlines.com/2021/06/netflip-app-tinder-movies-android.html) — LOW
- [Swipe With Friends is like Tinder for movies — Android Police](https://www.androidpolice.com/2021/01/16/swipe-with-friends-is-like-tinder-for-finding-movies-and-shows-to-watch/) — MEDIUM (cross-referenced with MakeUseOf coverage of the same Reelgood feature)
- [Reelgood Launches a Tinder-Style Movie Matching Service — MakeUseOf](https://www.makeuseof.com/reelgood-swipe-with-friends-movie-matching/) — MEDIUM
- [Movie Matchup — App Store](https://apps.apple.com/us/app/movie-matchup/id6756759571) — LOW (single-source, marketing copy, mechanics undisclosed)
- [FlickFix - Swipe Match Stream — App Store](https://apps.apple.com/mx/app/flickfix-swipe-match-stream/id6470672356) — LOW
- [Best Movie Apps for Couples in 2026, Ranked — MatchWatch](https://www.matchwatch.tv/blog/best-movie-apps-for-couples-2026) — LOW (aggregator/review, not primary source)
- [Best Movie Apps for Couples 2026 — Tested — TasteRay](https://www.tasteray.com/review/best-movie-apps-for-couples) — LOW (notes several Tinder-style movie apps have been discontinued over the years — a signal worth heeding about category durability)
- [Movie Picker for Friends (CineMatch) — Google Play](https://play.google.com/store/apps/details?id=com.travisapps.cinematch&hl=en_GB) — LOW
- [FlickPick: Film Tracker & List — Google Play](https://play.google.com/store/apps/details?id=com.flickpick.app&hl=en) — LOW
- [MovieShaker: Movie & TV Picks — App Store](https://apps.apple.com/us/app/movieshaker-movie-tv-picks/id6745936940) — LOW
- [Movie Night Picker Wheel — The Decider](https://www.the-decider.com/wheels/movie-night) — LOW (evidence that a separate "decider wheel" app category exists specifically to fill the no-resolution gap left by matching apps)
- [The Psychology of Swiping in Apps — App Partner Academy](https://medium.com/app-partner-academy/the-psychology-of-swiping-in-apps-464895b2b485) — LOW (general dating-app swipe UX pattern literature, not movie-specific)
- [Why Tinder's Swipe Interaction Was a UX Masterstroke — Medium](https://medium.com/design-bootcamp/why-tinders-swipe-interaction-was-a-ux-masterstroke-e583d5eddfd1) — LOW

---
*Feature research for: group movie-matching / swipe-to-decide apps*
*Researched: 2026-09-01*
