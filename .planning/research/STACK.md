# Stack Research

**Domain:** Group movie-matching swipe app ("Tinder for movies") — real-time multi-user session app
**Researched:** 2026-09-01
**Confidence:** MEDIUM-HIGH (backend/DB/TMDB findings verified against official docs; frontend framework popularity and library-maintenance claims from web search, cross-checked across multiple sources)

## Recommended Stack

### Core Technologies

| Technology | Version | Purpose | Why Recommended |
|------------|---------|---------|-----------------|
| Kotlin | 2.3.20 (current stable; project is on 1.9.25) | Backend language | Already the project's chosen language (constraint). Spring Boot 4's Kotlin support requires Kotlin ≥2.2, so an upgrade from 1.9.25 is mandatory regardless of which Boot version is chosen. |
| Spring Boot | 4.1.x (current: 4.1.1) — see version note below | Backend framework | Confirmed current stable via spring.io (verified, HIGH confidence). Built on Spring Framework 7 / Jakarta EE 11, Java 17+ (project already targets Java 21, so no floor-raise needed). Spring Boot 3.5 reached OSS end-of-life 2026-06-30 and 3.4 EOL'd 2025-12-31 — starting a **new** build today on either would mean building on an already-unsupported line. |
| React | 19.2.x | Frontend SPA framework | See "Frontend Framework Decision" below — recommended over Vue/Svelte for this specific app given its swipe-card + real-time UI needs and the depth of the React gesture/animation ecosystem. |
| Vite | 7.x | Frontend build tool / dev server | Standard 2025/2026 scaffold for a React+TS SPA (`npm create vite@latest app -- --template react-ts`). Create React App is deprecated; Next.js is the wrong tool here because this app has no SSR/RSC requirement — it's a pure client-rendered SPA talking to a separate Spring Boot API. |
| TypeScript | 5.x (bundled with Vite template) | Frontend language | Type-safety for the vote/session/results contracts shared conceptually with the Kotlin DTOs; standard pairing with Vite+React in 2025/2026. |
| PostgreSQL | 17 or 18 | Persistence | See "Database Decision" below. Chosen over SQLite because free/cheap-tier hosts (Render, Railway) give ephemeral app-container filesystems — a SQLite file would not reliably survive redeploys/restarts without a paid persistent volume, which directly conflicts with the "data survives restart" requirement. |
| Spring Data JPA + Hibernate | Bundled with Spring Boot 4.1.x | ORM / persistence layer | Standard Spring persistence approach; pairs with Flyway for schema control (see Supporting Libraries). |
| Spring WebSocket (STOMP) | Bundled with `spring-boot-starter-websocket` | Real-time updates | See "Real-Time Decision" below. STOMP's topic/queue pub-sub model (`/topic/session/{code}`) is a direct fit for "broadcast waiting-room state to everyone in this session," and the prior prototype already used WebSocket — this keeps that investment while adding the routing structure it was missing. |

### Frontend Framework Decision

**Recommendation: React 19 + Vite, not Vue or Svelte.**

All three (React, Vue, Svelte) are equally capable of pairing with a Spring Boot REST+WebSocket backend — none of them care what the backend is, since they only see JSON over HTTP/WS. The differentiator for *this* app is the swipe-card interaction and its supporting library ecosystem:

- React has, by a wide margin, the deepest ecosystem of drag/gesture/animation libraries applicable to swipe-card UIs (Motion, `@use-gesture/react` + react-spring, etc.) and the most first-party and community tutorials specifically for "Spring Boot + STOMP + React" real-time apps — reducing integration risk for the WebSocket layer.
- React remains the most widely adopted framework (44.7% in the 2025 Stack Overflow survey), meaning the most Stack Overflow/GitHub issue coverage if something goes wrong mid-build — a meaningful factor for a project without a dedicated frontend specialist.
- Svelte compiles away the framework and ships smaller bundles, and Vue sits in between on bundle size — but neither advantage matters much for an app with one core screen flow (join → swipe → wait → results) where bundle size is not a bottleneck.

Vue or Svelte are reasonable if the person building this already has strong existing familiarity with one of them — in that case, team familiarity should override the ecosystem-depth argument above.

### Swipe-Card UI Decision

**Do not use `react-tinder-card`.** It is effectively unmaintained: the latest npm release (1.6.4) is ~3 years old with no recent activity. Building a new feature on it in 2026 risks hitting React 19 compatibility issues with no upstream fix path.

**Recommendation: build the swipe deck with `motion` (the renamed/current `framer-motion`, import from `motion/react`), version 13.x.** It has first-class `drag`, `whileDrag`, and gesture-velocity APIs purpose-built for this exact "drag card, release past threshold, animate off-screen" pattern, is actively maintained, and is already the de facto standard animation library in the React ecosystem — so it's also useful elsewhere in the app (result reveal animation, transitions) beyond just the swipe deck.

### Database Decision

**Recommendation: PostgreSQL (managed, e.g. Render or Railway's Postgres add-on), not SQLite.**

The project's own constraint is explicit: "sessions, participants, votes, and results must survive a server restart." On Render/Railway/Fly.io free or cheap tiers, the **application container's filesystem is ephemeral** — it gets wiped on redeploy, restart, or scale-to-zero. A file-based SQLite DB living inside that container would violate the durability requirement unless a paid persistent volume is explicitly attached (available on Fly.io, but Fly.io no longer offers a free tier for new accounts as of 2026). A managed Postgres instance is a separate, independently-persisted service — it survives your app container restarting, redeploying, or crashing, which is exactly the property this app needs.

- **Render**: offers a free-tier Postgres option with no credit card required alongside free web services — good first choice for "reachable by a link for friends," low-traffic use.
- **Railway**: one-click Postgres provisioning with excellent DX, but the free credit only covers a few hours/month of always-on runtime — likely to need a small paid plan (~$5/mo) once the app is actually running most of the time.
- **Fly.io**: requires a credit card and has no free tier for new signups as of 2026 — only worth considering if targeting Fly specifically for its volume-based Postgres + multi-region story, which is overkill for this project's scale.

Given the "small self/cheap-hosted" framing in the constraints, **Render is the better starting point**; Railway is a fine alternative if its DX is preferred and the small monthly cost is acceptable.

### Real-Time Decision

**Recommendation: STOMP over WebSocket (`spring-boot-starter-websocket` + `@EnableWebSocketMessageBroker`), not raw WebSocket, and not SSE.**

- STOMP adds destination-based routing (`/topic/session/{code}` for broadcast, `/user/queue/...` for participant-specific messages) and a `SimpMessagingTemplate` for server-initiated pushes — this maps directly onto "broadcast waiting-room/results state to everyone in a session," which is exactly the use case here. Building this on raw WebSocket means hand-rolling that routing/subscription logic yourself.
- Raw WebSocket is only worth the extra hand-rolled routing work for very latency-sensitive or high-throughput cases (e.g. real-time gaming) — not applicable here.
- SSE was one of the constraint's "or equivalent" options, but it's one-way (server→client only) and doesn't eliminate the need for a request/response channel for the actual vote submissions — WebSocket already covers both directions with one connection, and the prior prototype already used WebSocket successfully for the waiting-screen use case, so there's no reason to switch primitives.
- SockJS fallback is unnecessary — native WebSocket support is ubiquitous in modern browsers; only add SockJS if a specific unsupported browser needs to be targeted.

### Supporting Libraries

| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `spring-boot-starter-data-jpa` | Bundled w/ Boot 4.1.x | ORM for sessions/participants/votes/results entities | Always — this is the persistence backbone. |
| `org.postgresql:postgresql` | Latest (JDBC driver) | Postgres JDBC driver | Runtime dependency alongside JPA. |
| `org.flywaydb:flyway-core` + `flyway-database-postgresql` | Latest 10.x/11.x | Schema migrations | Always — set `spring.jpa.hibernate.ddl-auto=validate`, never `update`/`create-drop`, and let Flyway own schema changes via versioned `V<n>__description.sql` files in `src/main/resources/db/migration`. This is the standard, safe pattern; Hibernate auto-DDL is a known footgun for anything beyond a throwaway prototype. |
| `spring-webflux` (for `WebClient` only) | Bundled w/ Boot | Outbound HTTP client for calling TMDB | Use `WebClient`, not the deprecated `RestTemplate`, for all TMDB calls. Pair with `kotlinx-coroutines-reactor` to call `.awaitBody<T>()` from Kotlin `suspend` functions for idiomatic non-blocking code, and `retryWhen` with exponential backoff + jitter to handle TMDB 429s/transient failures gracefully. |
| `org.testcontainers:postgresql` | Latest | Integration testing | Standard approach for testing the persistence layer against a real Postgres instance instead of H2 (H2's SQL dialect quirks can hide real Postgres bugs). |
| `motion` (import from `motion/react`) | ^13.x | Swipe-card drag/gesture + animation | The swipe deck itself, plus any other transition/reveal animation (results screen, waiting-room state changes). |
| `@stomp/stompjs` | Latest 7.x | STOMP-over-WebSocket client in React | Connects directly to the Spring STOMP endpoint; no SockJS needed for a modern-browser target. |
| `@tanstack/react-query` | v5.x | Server-state data fetching/caching on the frontend | Recommended over SWR for this app: fuller mutation/optimistic-update support (useful for "submit a swipe vote" UX) and larger ecosystem/devtools; SWR's smaller bundle size matters more for Next.js-style apps than a single-page swipe app. |
| React Router | v7.x (if needed) | Client-side routing | Only needed if the app has more than 2-3 distinct screens (join, swipe, waiting, results) that warrant real URL-based navigation (e.g. so a results link is shareable/bookmarkable) — otherwise simple state-based view switching is sufficient and avoids the dependency. |

### Development Tools

| Tool | Purpose | Notes |
|------|---------|-------|
| Gradle Kotlin DSL (`build.gradle.kts`) | Backend build | Already in use — no change needed. |
| Vitest + React Testing Library | Frontend testing | Standard pairing with a Vite-scaffolded React app; ships alongside the `react-ts` template's dev-dependency conventions. |
| Docker Compose (local Postgres) | Local dev DB | Run a local Postgres container matching the hosted version so Flyway migrations and JPA mappings are tested against the same engine used in production, avoiding H2-vs-Postgres dialect surprises. |

## Installation

```bash
# Frontend scaffold
npm create vite@latest muvimatchr-web -- --template react-ts
cd muvimatchr-web
npm install motion @stomp/stompjs @tanstack/react-query

# Backend (add to build.gradle.kts dependencies)
# implementation("org.springframework.boot:spring-boot-starter-data-jpa")
# implementation("org.springframework.boot:spring-boot-starter-webflux") // for WebClient only, not for exposing reactive endpoints
# implementation("org.postgresql:postgresql")
# implementation("org.flywaydb:flyway-core")
# implementation("org.flywaydb:flyway-database-postgresql")
# implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:<latest>")
# testImplementation("org.testcontainers:postgresql:<latest>")
# testImplementation("org.testcontainers:junit-jupiter:<latest>")
```

## Alternatives Considered

| Category | Recommended | Alternative | When Alternative Makes Sense |
|----------|-------------|-------------|-------------------------------|
| Frontend framework | React 19 | Vue 3 | If the builder already knows Vue well — its Composition API + `<script setup>` is comparably productive, and it can absolutely handle a swipe UI + WebSocket client (via a `vue-stomp` wrapper or raw `@stomp/stompjs`). |
| Frontend framework | React 19 | Svelte 5 | If bundle size / raw runtime performance is a hard priority (e.g. targeting very low-end mobile devices) — Svelte's compiled, no-virtual-DOM approach ships noticeably less JS. Smaller ecosystem for this specific use case is the tradeoff. |
| Swipe UI | Custom w/ `motion` | `@use-gesture/react` + `react-spring` | Also a solid, actively-maintained pairing for drag gestures; `motion` was chosen mainly because it's a single dependency covering both gesture handling and animation, rather than two libraries doing related jobs. |
| Database | PostgreSQL | SQLite (embedded, e.g. via `sqlite-jdbc` + a Fly.io volume) | Only viable if deploying specifically to Fly.io (or another host) with a paid persistent volume attached and single-instance deployment (SQLite doesn't handle concurrent-writer, multi-instance scale-out well) — adds hosting-provider lock-in and complexity for no real benefit at this app's scale. |
| Real-time transport | STOMP over WebSocket | Server-Sent Events (SSE) + separate POST for votes | Simpler to reason about (SSE is just an HTTP stream, easier to debug/proxy through some hosts), and is a legitimate fallback if a hosting provider's free tier has flaky/unreliable WebSocket proxying — SSE is more universally proxy-friendly. Only reach for this if WebSocket connectivity issues actually show up on the chosen host. |
| Data fetching | TanStack Query | SWR | If bundle size becomes a real, measured concern, or if the project later grows into a Next.js app (unlikely given the SPA constraint). |
| Spring Boot version | 4.1.x | 3.5.x (last OSS-supported 3.x release, EOL'd 2026-06-30) | If the extra migration surface of Boot 4 (JSpecify nullability annotations changing Kotlin interop, Jackson 3 as default codec, Kotlin ≥2.2 requirement) feels risky for a first solo build and more StackOverflow/tutorial coverage on 3.x is preferred over running on a now-EOL minor version. This is a legitimate, defensible choice for a hobby-scale app with no compliance/security SLA — see "What NOT to Use" for the specific 4.0 migration gotchas to watch for either way. |

## What NOT to Use

| Avoid | Why | Use Instead |
|-------|-----|-------------|
| `react-tinder-card` | Unmaintained (~3 years since last release); real risk of React 19 incompatibility with no upstream fix | Custom swipe deck built on `motion`'s drag/gesture API |
| In-memory `Map`/list state for sessions/votes (the prior prototype's approach) | Explicitly the root cause of the two hard requirements this rebuild must fix: data doesn't survive restart, and multi-user vote aggregation never got finished, both of which are much harder to reason about with hand-rolled in-memory state | PostgreSQL + Spring Data JPA with a schema designed for concurrent, idempotent vote writes (one row per participant+movie, upserted, not appended) |
| `RestTemplate` for calling TMDB | Deprecated by Spring in favor of `WebClient`/`RestClient`; blocking-only, no built-in retry/backoff ergonomics | `WebClient` (reactive) or `RestClient` (Boot 3.2+ synchronous alternative) with Reactor `retryWhen` |
| Hibernate `ddl-auto=update` or `create-drop` in anything beyond local scratch work | Silently drifts schema in ways that are hard to reproduce/debug and has no migration history — directly risks the "results correctly aggregate votes... this is a hard requirement" goal if a schema change silently breaks vote rows | `ddl-auto=validate` + Flyway-owned versioned migrations |
| Building the deploy target around Fly.io's free tier | Fly.io no longer offers a free tier for new accounts as of 2026 and requires a credit card on file | Render (free Postgres + free web service, no card required) as the default target |
| Passing TMDB's `api_key` as a URL query parameter everywhere | Works, but leaks the key into logs/URLs/browser history if ever proxied through anything that logs full URLs; TMDB's own docs recommend the Bearer-token approach as default | `Authorization: Bearer <TMDB_READ_ACCESS_TOKEN>` header on all TMDB calls |
| Create React App | Officially deprecated; no TypeScript-by-default, slower webpack-based builds, unmaintained relative to Vite | Vite (`npm create vite@latest -- --template react-ts`) |
| SockJS for the WebSocket transport | Adds a dependency and fallback complexity that's only needed for browsers without native WebSocket support — effectively none in 2025/2026 for a friends-and-family app | Plain `@stomp/stompjs` connecting directly over `ws://`/`wss://` |

## Stack Patterns by Variant

**If the builder has strong existing Vue or Svelte experience:**
- Use that framework instead of React — none of the backend/DB/real-time recommendations change, since they're framework-agnostic (Spring emits JSON over REST/WebSocket regardless of frontend).
- For Vue: use `@stomp/stompjs` directly (no Vue-specific wrapper needed) and `@vueuse/gesture` or a custom drag handler for the swipe deck.
- For Svelte: `@stomp/stompjs` again works framework-agnostically; use Svelte's built-in transition/motion primitives (`svelte/motion`, `svelte/transition`) for the swipe interaction instead of pulling in `motion`.

**If deploying to Fly.io specifically (e.g. for its edge/multi-region story) instead of Render:**
- Budget for the credit-card requirement and lack of a free tier.
- SQLite + a Fly.io persistent volume + Litestream (for continuous backup to object storage) becomes a legitimate, lower-cost-at-scale alternative to managed Postgres — but only pursue this if deploying as a single instance (no horizontal scale-out), since SQLite doesn't support multiple concurrent writer instances.

**If the app later needs to support many concurrent large groups (beyond the "couple + friends" scale in the requirements):**
- Revisit the WebSocket broker: Spring's built-in simple in-memory STOMP broker doesn't scale across multiple app instances. At that point, wire in an external broker (RabbitMQ or Redis pub/sub via `spring-boot-starter-websocket` + a full STOMP broker relay) so WebSocket state is shared across horizontally-scaled backend instances. Not needed at this project's current scale — flagging for awareness only.

## Version Compatibility

| Package A | Compatible With | Notes |
|-----------|-----------------|-------|
| Spring Boot 4.1.x | Kotlin ≥2.2 (use 2.3.20) | Boot 4.0 raised the Kotlin baseline to 2.2; the project's current 1.9.25 must be upgraded as part of this rebuild regardless of which Boot version is ultimately chosen. |
| Spring Boot 4.1.x | Java 17+ (project uses 21 — fine) | Boot 4 did not raise the Java floor above 17, so the existing Java 21 toolchain setting needs no change. |
| Spring Boot 4.1.x | Jackson 3 (default), Jakarta EE 11, Servlet 6.1 | Watch for JSpecify (`org.jspecify.annotations`) replacing Spring's own `@Nullable`/`@NonNull` — this can surface new Kotlin nullability type-mismatch errors on code that compiled fine under Boot 3.x, since APIs previously treated as non-null by Kotlin may now be annotated as nullable. Budget time for this during backend setup. |
| React 19.x | `motion` ^13.x, `@tanstack/react-query` v5.x, `@stomp/stompjs` v7.x | All current major versions are React-19-compatible as of this research; no known conflicts. |
| Vite 7.x | Node.js 24.x (current active LTS) | Use Node 24 for the frontend toolchain; Node 22 (maintenance LTS) also works if that's already installed. |
| PostgreSQL 17/18 | Flyway 10.x/11.x, Hibernate (bundled w/ Boot 4.1.x) | No known compatibility issues; both are current within their respective support windows. |

## Sources

- spring.io/projects/spring-boot — verified current stable version (4.1.1) directly from the official project page (HIGH confidence)
- developer.themoviedb.org/docs/authentication-application — official TMDB docs, fetched directly, confirms Bearer-token as default/recommended auth method (HIGH confidence)
- developer.themoviedb.org/reference/configuration-details — official TMDB docs, fetched directly, confirms `/configuration` image base URL + size arrays (HIGH confidence)
- developer.themoviedb.org/docs/rate-limiting — official TMDB docs on rate limiting behavior (per-second ceiling in the ~40 req/s range, subject to change, enforced via HTTP 429) (HIGH confidence)
- Web search (multiple sources, cross-checked) — React/Vue/Svelte 2025/2026 adoption and ecosystem comparison (MEDIUM confidence)
- Web search — `react-tinder-card` maintenance status via npm/Snyk listings (MEDIUM confidence)
- Web search — Spring Boot WebSocket STOMP vs raw WebSocket vs SSE tradeoffs, multiple 2025/2026 tutorials (MEDIUM confidence)
- Web search — Render/Railway/Fly.io free-tier and Postgres offering comparison, 2026 (MEDIUM confidence)
- Web search — Spring Boot 4.0 migration guides (spring-projects/spring-boot wiki, multiple community writeups) for Kotlin/Jackson/JSpecify breaking-change details (MEDIUM confidence)
- Web search — Kotlin 2.3.20 current stable release confirmation via kotlinlang.org/JetBrains blog (MEDIUM-HIGH confidence, official domain)
- Web search — TanStack Query vs SWR adoption/feature comparison, 2025/2026 (MEDIUM confidence)
- Web search — Node.js 24 current active LTS status confirmation (MEDIUM confidence)
- Web search — `motion` (formerly `framer-motion`) package rename and current version confirmation via motion.dev official docs (MEDIUM-HIGH confidence, official domain)

---
*Stack research for: Group movie-matching swipe app (Kotlin/Spring Boot backend, TMDB-sourced data, WebSocket real-time, no-auth session model)*
*Researched: 2026-09-01*
