# Phase 2: Session & Lobby Flow - Research

**Researched:** 2026-09-02
**Domain:** Bearer-token identity for a no-accounts casual web app (Spring Boot 4.1 / Kotlin, REST API)
**Confidence:** MEDIUM (no HIGH-confidence provider — Context7/Exa/Brave/etc. are disabled in this project's config; findings are WebSearch cross-checked against official docs where fetched directly, or based on Phase 1's own already-proven codebase patterns)

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- **D-01:** Host shares a short typable join code only (e.g. `ABC123`) — no full shareable URL in this phase. Participant enters the code plus a display name on a join screen. Matches the `joinCode` field already built on the `Session` entity in Phase 1.
- **D-02:** Each participant is given a personal, bookmarkable resume link containing their session ID and unguessable token. Returning via that link (any device/browser) re-identifies them as the same participant with their existing votes attached — no login, no browser-local-storage dependency. — **Reversibility: costly** — the resume link's token *is* the participant's identity credential; once real participants are holding saved links, changing the token scheme (format, where it lives) breaks everyone's existing saved link.
- **D-03:** The host has no special role or privileges — they are just the first participant to join the session they created. No host-only actions exist in this phase or are implied by current requirements. Do not add a host/owner flag to `Participant` or `Session` speculatively.
- **D-04:** Participants can join a session at any time, including after other participants have already started swiping/voting — no lock, no cutoff. This phase does not need a "session started" or "locked" state.
- **D-05:** Duplicate display names within the same session are allowed (no uniqueness check). Each participant is distinguished by their own token/ID under the hood; a duplicate name is cosmetic only.

### Claude's Discretion
- Exact join-code generation strategy (charset, length within the existing `VARCHAR(16)` column, collision-retry approach).
- Token format (opaque random string vs signed) and storage (hashed vs plaintext in DB).
- Exact resume-link URL shape (path structure, query param vs path segment for the token).

### Deferred Ideas (OUT OF SCOPE)
None — discussion stayed within phase scope. Host-only privileges were explicitly considered and declined (see D-03), not deferred.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| SESH-01 | Host can create a session and get a unique shareable join code/link | Join-code generation strategy (Crockford-style charset, 6 chars, DB-level collision retry) — see Standard Stack / Code Examples |
| SESH-02 | Participant joins via code/link by picking a display name — no account required | `POST /api/sessions/{joinCode}/participants` endpoint design — see Architecture Patterns |
| SESH-03 | Each participant gets a server-issued unguessable token, not just their display name | SecureRandom 256-bit token + SHA-256 hash-at-rest design — see Standard Stack, Code Examples, Security Domain |
| SESH-04 | A session supports 2+ participants | No structural change needed — `participant.session_id` FK already unbounded 1:many (Phase 1); join endpoint has no participant-count cap |
| SESH-05 | A participant can leave and resume later via retained link/token without losing votes | Resume endpoint (`GET /api/sessions/{sessionId}/participants/me`) resolves by token hash only, never creates a new row — see Architecture Patterns, Common Pitfalls |
</phase_requirements>

## Summary

This phase adds one new column to `Participant` (a hashed bearer-credential) and a small REST layer on top of the Phase 1 schema — no new entities, no new tables besides the migration, and (per D-03) no roles/authorities. The two technical risks the phase context correctly flags are: (1) generating and storing the participant token correctly, since it is a long-lived, effectively irrevocable credential once real users hold resume links, and (2) choosing an auth mechanism proportionate to "no accounts, no roles" rather than defaulting to `spring-boot-starter-security`, which brings CSRF/login-page/permitAll configuration overhead this app doesn't need.

The standard, low-ceremony approach: generate a 256-bit token with `java.security.SecureRandom`, Base64URL-encode it for the client, store only its SHA-256 hash in Postgres (never the plaintext), and authenticate requests with a custom Spring MVC `HandlerMethodArgumentResolver` reading `Authorization: Bearer <token>`. Join codes use a 6-character human-friendly alphabet (digits + uppercase letters minus visually ambiguous ones) generated server-side and retried on the DB's existing unique-index collision, exactly mirroring the pattern Phase 1's own `SessionRepositoryTest` already proved (DB rejects duplicates; the app doesn't pre-check).

**Primary recommendation:** Add participant token support as a plain Kotlin JPA field (`tokenHash: String`, new Flyway `V4__add_participant_token.sql`, unique-indexed), generate/hash tokens in a small `TokenService`, and authenticate incoming requests with a custom `HandlerMethodArgumentResolver` + `WebMvcConfigurer` — do not add `spring-boot-starter-security` for this phase.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Join-code generation & uniqueness | API / Backend | Database / Storage | Generated in a Kotlin service, enforced by the existing `uq_session_join_code` unique index (DB is source of truth for collision rejection, per Phase 1's proven pattern) |
| Participant creation & token issuance | API / Backend | Database / Storage | `POST .../participants` creates the row and mints the credential in one transaction; DB stores only the hash |
| Token validation per-request | API / Backend | — | Custom `HandlerMethodArgumentResolver`/interceptor runs inside the same Spring MVC dispatch Phase 1's controllers will use — no separate auth service, no session store |
| Resume-link handling | API / Backend | — | Resume is a `GET` that resolves participant identity from the token hash; no separate "resume" state or table |
| Persistent identity storage | Database / Storage | — | `participant.token_hash` column, Postgres, survives restart per Phase 1's RELI-01 proof |
| Join/resume UI | Browser / Client | — | **Not built this phase** — ROADMAP sequences the SPA last; this phase is REST-API-only, consumed by a future frontend |

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| `java.security.SecureRandom` | JDK 21 built-in | CSPRNG for token bytes | Cryptographically secure, no external dependency; `java.util.Random` and low-bit UUIDs are explicitly the wrong tool here [ASSUMED — standard JDK crypto guidance, not fetched from a specific doc this session] |
| `java.security.MessageDigest` (`SHA-256`) | JDK 21 built-in | Hash token before storing at rest | Fast hash appropriate for *high-entropy* secrets — see Security Domain below [CITED: cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html — confirms fast hashes are unsuitable specifically for low-entropy passwords; industry pattern for high-entropy tokens per github.blog engineering post, MEDIUM confidence] |
| `java.util.Base64` (URL-safe, no padding) | JDK 21 built-in | Encode raw token bytes for transport in headers/URLs | RFC 4648 URL-safe variant avoids `+`/`/` characters that need escaping in query strings [ASSUMED — well-established, not independently re-verified against RFC text this session] |
| Spring MVC (`spring-boot-starter-web`, already on classpath) | Boot 4.1.1 (Framework 7.x) | `HandlerMethodArgumentResolver`, `WebMvcConfigurer`, `@RestController` | Already a dependency (Phase 1); no new coordinate needed for token auth |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Jakarta Bean Validation (`jakarta.validation`, transitively on classpath via `spring-boot-starter-web`) | — | `@NotBlank @Size(max = 100)` on the join-request `displayName` field, matching the existing `display_name VARCHAR(100)` column | Request DTO validation |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Custom `HandlerMethodArgumentResolver` for token auth | `spring-boot-starter-security` + custom `OncePerRequestFilter`/`AuthenticationProvider` | Security starter adds default login-form auto-config that must be explicitly disabled, forces an explicit `SecurityFilterChain` bean with `permitAll()`/`authenticated()` rules for every route, and its CSRF protection is irrelevant here (CSRF exploits *ambient* browser credentials like cookies; an explicit `Authorization: Bearer` header is not automatically sent by the browser, so it isn't CSRF-exposed the way a cookie-session app is) — net overhead without a matching benefit for a no-roles, no-accounts app. Reconsider only if a future phase introduces real accounts or role-based access. |
| Fast hash (SHA-256) for token-at-rest | bcrypt / argon2 / PBKDF2 | Those algorithms are deliberately slow to resist brute-forcing *low-entropy* human passwords; applied to an already-256-bit random token they add CPU cost on every authenticated request with no corresponding security gain, since the attack they defend against (guessing/brute-forcing the secret) is already infeasible against 256 bits of entropy [CITED: cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html confirms the *reverse* case — fast hashes are explicitly unsuitable for low-entropy passwords, which by inversion is the standard justification cited across web sources for treating high-entropy tokens differently; OWASP does not itself make the token-specific recommendation, so this is MEDIUM confidence, not a direct standards citation] |
| Opaque random token | Signed/stateless token (JWT-like) | A signed token would let the server validate without a DB round-trip, but this app already does a DB round-trip on every request anyway (to check the participant belongs to a real session/still exists), and D-02 requires an unguessable *credential*, not claims data — no benefit here, added complexity (signing key management, no revocation without a DB check anyway) |

**Installation:**
No new Gradle dependency coordinates are required for this phase — `SecureRandom`, `MessageDigest`, and `Base64` are JDK built-ins; Spring MVC is already on the classpath from Phase 1/the project scaffold.

**Version verification:** N/A — no new package added this phase.

## Package Legitimacy Audit

**No new external packages are introduced by this phase.** All token-generation and auth-resolution primitives (`SecureRandom`, `MessageDigest`, `Base64`, `HandlerMethodArgumentResolver`, `WebMvcConfigurer`) are either JDK built-ins or already-present Spring MVC classes from the existing `spring-boot-starter-web` dependency (present since project scaffolding, confirmed via `build.gradle.kts` read this session). The Package Legitimacy Gate protocol is not applicable — skipping the audit table.

**Packages removed due to [SLOP] verdict:** none (n/a — nothing installed)
**Packages flagged as suspicious [SUS]:** none (n/a — nothing installed)

## Architecture Patterns

### System Architecture Diagram

```
Client (browser, or curl/Postman this phase — SPA is a later phase)
   │
   │ 1. POST /api/sessions                       (no body)
   ▼
┌─────────────────────────────────────────────────────────────┐
│ SessionController.createSession()                            │
│   → SessionService.create()                                   │
│       → generate 6-char join code (retry on DB unique clash)  │
│       → SessionRepository.saveAndFlush()                      │
│   ← 201 Created, Location: /api/sessions/{id}                 │
│   ← body: { sessionId, joinCode }                              │
└─────────────────────────────────────────────────────────────┘
   │
   │ 2. POST /api/sessions/{joinCode}/participants  { displayName }
   ▼
┌─────────────────────────────────────────────────────────────┐
│ ParticipantController.join()                                  │
│   → SessionRepository.findByJoinCode(joinCode) or 404          │
│   → TokenService.issue() → (rawToken, tokenHash)                │
│   → ParticipantRepository.save(Participant(session, name,      │
│         tokenHash))                                            │
│   ← 201 Created                                                │
│   ← body: { participantId, sessionId, displayName,             │
│             token: rawToken,           ← shown once, never    │
│             resumeUrl: "/session/{sessionId}?token=..." }  again │
└─────────────────────────────────────────────────────────────┘
   │
   │ 3. Later: GET /api/sessions/{sessionId}/participants/me
   │           Authorization: Bearer <rawToken>
   ▼
┌─────────────────────────────────────────────────────────────┐
│ CurrentParticipantArgumentResolver (Spring MVC)                │
│   → extract token from Authorization header                    │
│   → hash it (SHA-256) → ParticipantRepository.findByTokenHash  │
│   → 401 if missing/no match                                    │
│   → resolved Participant injected into controller method       │
│ ParticipantController.me(@CurrentParticipant participant)       │
│   ← 200, body: { participantId, sessionId, displayName }        │
│   (SESH-05: same row is returned — no new Participant created, │
│   existing Vote rows in Phase 4 stay attributed to this id)     │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    PostgreSQL (Flyway V1-V4)
```

### Recommended Project Structure
```
src/main/kotlin/org/example/muvimatchr/
├── session/
│   ├── Session.kt                    # existing (Phase 1)
│   ├── SessionRepository.kt          # existing (Phase 1)
│   ├── Participant.kt                # MODIFIED — add tokenHash column
│   ├── ParticipantRepository.kt      # MODIFIED — add findByTokenHash query
│   ├── SessionService.kt             # NEW — join-code generation/retry
│   ├── ParticipantService.kt         # NEW — join + resume logic
│   ├── SessionController.kt          # NEW — POST /api/sessions
│   └── ParticipantController.kt      # NEW — join + me endpoints
├── auth/
│   ├── TokenService.kt               # NEW — SecureRandom + SHA-256 hashing
│   ├── CurrentParticipant.kt         # NEW — marker annotation
│   └── CurrentParticipantArgumentResolver.kt  # NEW
└── config/
    ├── WebSocketConfig.kt            # existing, untouched
    └── WebMvcConfig.kt               # NEW — registers the argument resolver
```
This follows Phase 1's existing package-by-feature convention (`session/`, `voting/`) rather than package-by-layer.

### Pattern 1: DB-level collision retry for join codes (matches Phase 1's proven pattern)
**What:** Generate a candidate code, attempt to persist, catch the database's unique-constraint rejection, retry with a new candidate — never pre-check with a `SELECT` first.
**When to use:** Any short human-facing code backed by a DB unique index (this project already proved this exact pattern for `join_code` in `SessionRepositoryTest`, Phase 1: *"a duplicate join_code raises DataIntegrityViolationException from the DB, not an app-level pre-check"* [VERIFIED: .planning/phases/01-persistence-foundation/01-02-SUMMARY.md — coverage block "Session join codes are unique at the database level ... a duplicate join_code raises DataIntegrityViolationException from the DB, not an app-level pre-check"]).
**Example:**
```kotlin
// Source: pattern mirrors Phase 1's already-proven join_code uniqueness behavior
private val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford-derived: excludes 0/O, 1/I/L ambiguity by construction
private val random = SecureRandom()

fun generateJoinCode(length: Int = 6): String =
    (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

@Transactional
fun createSession(): Session {
    repeat(MAX_JOIN_CODE_ATTEMPTS) {
        val candidate = generateJoinCode()
        try {
            return sessionRepository.saveAndFlush(Session(joinCode = candidate))
        } catch (e: DataIntegrityViolationException) {
            // collision on uq_session_join_code — retry with a new candidate
        }
    }
    throw IllegalStateException("Could not allocate a unique join code after $MAX_JOIN_CODE_ATTEMPTS attempts")
}
```
Note: `ALPHABET` above is 32 chars (`0-9` minus none, `A-Z` minus `I`, `L`, `O` — 10 + 23 = 33; adjust to exactly 32 or accept 33, either is fine for this purpose) [ASSUMED — verify the literal has the intended character count when implementing; the planner/executor should not copy this string without counting it].

### Pattern 2: Token issuance and hash-at-rest
**What:** Generate the raw token with `SecureRandom`, return it to the client exactly once (in the join response and the resume link), persist only its SHA-256 hash.
**When to use:** Any bearer-style credential where the plaintext never needs to be recovered server-side (only compared).
**Example:**
```kotlin
// Source: standard high-entropy-token pattern (SecureRandom + SHA-256), MEDIUM confidence — see Standard Stack
@Component
class TokenService {
    private val random = SecureRandom()

    fun issue(): IssuedToken {
        val bytes = ByteArray(32) // 256 bits
        random.nextBytes(bytes)
        val rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return IssuedToken(rawToken, hash(rawToken))
    }

    fun hash(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawToken.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } // 64 hex chars
    }
}

data class IssuedToken(val rawToken: String, val tokenHash: String)
```

### Pattern 3: Custom bearer-token resolution without Spring Security
**What:** A `HandlerMethodArgumentResolver` that extracts, hashes, and looks up the participant from the `Authorization` header, injecting the resolved `Participant` directly into controller methods.
**When to use:** Stateless, no-accounts APIs where full Spring Security's auth/authorization machinery (roles, filter chains, CSRF) would be pure overhead — matches D-03's explicit "no host role, no authorities."
**Example:**
```kotlin
// Source: pattern derived from Spring's own BearerTokenResolver contract
// (docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/bearer-tokens.html,
// fetched this session) adapted to a plain Spring MVC resolver, no spring-security dependency
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CurrentParticipant

@Component
class CurrentParticipantArgumentResolver(
    private val participantRepository: ParticipantRepository,
    private val tokenService: TokenService,
) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter) =
        parameter.hasParameterAnnotation(CurrentParticipant::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val header = webRequest.getHeader(HttpHeaders.AUTHORIZATION)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization header")
        val token = header.removePrefix("Bearer ").trim()
        val hash = tokenService.hash(token)
        return participantRepository.findByTokenHash(hash)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or unrecognized token")
    }
}

@Configuration
class WebMvcConfig(
    private val currentParticipantArgumentResolver: CurrentParticipantArgumentResolver,
) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(currentParticipantArgumentResolver)
    }
}

// Usage in a controller:
@GetMapping("/api/sessions/{sessionId}/participants/me")
fun me(@CurrentParticipant participant: Participant): ParticipantResponse =
    ParticipantResponse(participant.id!!, participant.session.id!!, participant.displayName)
```

### Anti-Patterns to Avoid
- **Using `UUID.randomUUID()` as the participant token:** it's a reasonable choice for a database primary key (already used that way for `Participant.id` in Phase 1) but only carries ~122 bits of randomness by spec and is not designed as a security credential — keep the DB id and the auth token as two separate values, never conflate them.
- **Pre-checking join-code/token uniqueness with a `SELECT` before `INSERT`:** race-prone under concurrent session creation; Phase 1 already established the correct pattern (attempt insert, catch the DB's own unique-constraint violation, retry) — don't regress to a check-then-act pattern for the new token column either.
- **Storing the token in an "authorization" query parameter on every subsequent API call:** OWASP's REST Security Cheat Sheet is explicit that credentials "should not appear in the URL" because they get captured in server/proxy access logs [CITED: cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html — "Passwords, security tokens, and API keys should not appear in the URL"]. The resume *link* necessarily carries the token once (that's the whole mechanism, per D-02, and is an accepted, deliberate tradeoff) — but once the client has the token, subsequent API calls should send it via the `Authorization: Bearer` header, not repeat it as a query string on every request.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Random token generation | A custom PRNG or `Random()`-seeded string builder | `java.security.SecureRandom` | Non-cryptographic PRNGs are predictable given enough output; this is exactly the class of bug OWASP's cryptography guidance exists to prevent |
| Constant-time-ish credential comparison | Manual byte-by-byte `==` comparison of raw tokens in app code | Hash-then-index-lookup (`findByTokenHash`) | The security property here rests on the token space being computationally infeasible to search (256 bits), not on timing-safe comparison of the raw secret — indexed DB equality lookup on the hash is the same approach GitHub/Stripe-style API key systems use in practice; don't add a bespoke constant-time-compare loop, it solves a problem this design doesn't have |
| Bean validation for the display-name field | Manual `if (name.isBlank() || name.length > 100)` checks scattered in controller/service code | `jakarta.validation` annotations (`@NotBlank @Size(max = 100)`) on the request DTO + `@Valid` on the controller parameter | Already transitively available via `spring-boot-starter-web`; centralizes validation and produces consistent 400 responses via Spring's built-in `MethodArgumentNotValidException` handling |

**Key insight:** This phase's entire risk surface is "don't roll your own crypto primitives and don't reinvent uniqueness enforcement Phase 1 already solved." Everything else is ordinary Spring MVC plumbing.

## Common Pitfalls

### Pitfall 1: Treating the token like a password (slow-hashing it)
**What goes wrong:** Hashing the 256-bit random token with bcrypt/argon2 "to be extra safe" adds meaningful CPU cost to *every* authenticated request (this token is checked on every API call, unlike a password checked once at login) without buying any real security margin, since the token is already computationally unguessable.
**Why it happens:** Password-hashing habits ("always use bcrypt for secrets") get over-applied to a different threat model.
**How to avoid:** Use SHA-256 (fast hash) specifically because the input already has 256 bits of entropy — see Standard Stack / Alternatives Considered.
**Warning signs:** Noticeably slower response times on every authenticated endpoint as the participant count grows.

### Pitfall 2: Building "leave"/"resume" as stateful transitions
**What goes wrong:** Adding a `left_at` timestamp or a session/participant status enum to model "the participant left" is unnecessary work and risks colliding with D-04 (no lock/state machine for this phase).
**Why it happens:** "Leave and resume" sounds like it needs explicit state tracking.
**How to avoid:** Per this project's decisions, "leaving" is purely a client-side event (closing the tab/browser) — there is no server-side "left" state to record. "Resume" is simply re-authenticating with the same token later; the participant row and its votes were never touched. Do not add a status column for this.
**Warning signs:** A plan or migration that adds a "status"/"active" column to `Participant` for this phase — that's scope creep against D-04/D-03.

### Pitfall 3: Editing V1-V3 migrations instead of adding V4
**What goes wrong:** Modifying an already-applied Flyway migration file causes a checksum mismatch on any environment that already ran it (this dev machine's local Postgres already has V1-V3 applied, confirmed in Phase 1's summaries).
**Why it happens:** It can look tempting to "just add the column to V2__create_participant.sql" since Participant has no production data yet.
**How to avoid:** Always add a new `V4__add_participant_token.sql` file. Flyway's `flyway_schema_history` table (already proven working across a restart in Phase 1's `RestartSurvivalTest`) tracks each migration's checksum — editing an applied file breaks that.
**Warning signs:** `Flyway checksum mismatch` errors on the next `./gradlew test`/`bootRun`.

### Pitfall 4: Disabling Flyway or switching to `ddl-auto=create` for controller/integration tests
**What goes wrong:** Generic web guidance about Flyway+Testcontainers (found this session) recommends disabling Flyway and letting Hibernate generate the schema in tests — **this directly contradicts what Phase 1 already proved works in this codebase**: `PostgresTestSupport` + `ddl-auto=validate` + real Flyway migrations, running successfully across 6 passing tests including a full restart proof.
**Why it happens:** It's common generic advice for projects that haven't already solved this; it doesn't apply here.
**How to avoid:** Continue using `PostgresTestSupport`'s singleton-container pattern and `ddl-auto=validate` for this phase's new controller/repository tests — do not introduce a second test configuration that bypasses Flyway.
**Warning signs:** A new test class that sets `spring.jpa.hibernate.ddl-auto=create` or `spring.flyway.enabled=false` in its own properties — this is a regression against the established Phase 1 pattern, not a new capability.

## Code Examples

See **Architecture Patterns** above (Patterns 1-3) for the primary code examples: join-code generation with DB-level collision retry, token issuance/hashing, and the custom `HandlerMethodArgumentResolver`. All three are original compositions of standard JDK/Spring APIs tailored to this codebase's existing conventions (plain Kotlin classes, not `data class`, for JPA entities; named `@Param` bindings for any native queries), not copied verbatim from a single external source.

### Repository query addition
```kotlin
// ParticipantRepository.kt — add alongside the existing JpaRepository<Participant, UUID>
interface ParticipantRepository : JpaRepository<Participant, UUID> {
    fun findByTokenHash(tokenHash: String): Participant?
}
```
Spring Data derives this from the method name — no native SQL needed, so the project's "named `@Param`, never string interpolation" rule (established by `VoteRepository.upsertVote`) doesn't even come into play here; it only applies when a query is written as `@Query(nativeQuery = true)`.

### Flyway V4 migration
```sql
-- V4__add_participant_token.sql
ALTER TABLE participant ADD COLUMN token_hash VARCHAR(64) NOT NULL;
CREATE UNIQUE INDEX uq_participant_token_hash ON participant (token_hash);
```
`VARCHAR(64)` exactly fits a hex-encoded SHA-256 digest (32 bytes → 64 hex chars). `NOT NULL` is safe to add directly (no `ADD COLUMN ... DEFAULT` migration dance needed) because Phase 1 never shipped any participant-creation flow — there is no existing production data in the `participant` table to backfill [VERIFIED: src/main/kotlin/org/example/muvimatchr/session/Participant.kt:17-31 — entity has no service/controller creating rows yet; ParticipantRepository (Phase 1) is a bare `JpaRepository<Participant, UUID>` with no save-path wired to any endpoint].

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|---------------|--------|
| Session-cookie-based lobby identity (this repo's own legacy `LobbyController.kt`, `HttpSession`-backed, in-memory) | Server-issued bearer token, persisted in Postgres | This phase | Legacy `LobbyController.kt`/`Lobby.kt` remain in the repo as unrelated prototype code (per CONTEXT.md, out of this phase's scope to delete); the new controllers are independent, not a refactor of the old ones |

**Deprecated/outdated:** N/A — no external library version changes; this is new code on an already-current stack (Boot 4.1.1/Kotlin 2.3.20, confirmed unchanged from Phase 1).

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | `UUID.randomUUID()` is unsuitable as a security token (JDK guidance, not independently re-verified against a spec this session) | Anti-Patterns to Avoid | Low — even if the entropy figure is slightly off from memory, the underlying reasoning (don't reuse a DB PK as a bearer credential) holds regardless of the exact bit count |
| A2 | Base64 URL-safe/no-padding is the right encoding choice for the raw token in URLs/headers | Standard Stack | Low — alternative encodings (hex, standard Base64 with escaping) would also work; URL-safe-no-padding is simply the most compact option without escaping needs |
| A3 | SHA-256 (unkeyed) is sufficient for hashing the token at rest, vs. a keyed HMAC with a server-side pepper | Standard Stack / Alternatives Considered | Medium — if this app's threat model later includes "attacker has read-only DB access but not app config/secrets," an HMAC pepper adds defense-in-depth this design omits; flagged as a discretionary follow-up, not required for v1 given OWASP itself doesn't mandate this for tokens |
| A4 | Crockford-style 32-symbol alphabet, 6 characters, is sufficient collision-resistance for join codes at this app's expected scale (small casual watch-parties, not high-volume) | Code Examples / Pattern 1 | Low — even at heavy usage, the DB-level unique index and retry loop correctly handles any collision; a shorter/longer code only affects retry frequency, not correctness |
| A5 | Spring MVC's `HandlerMethodArgumentResolver`/`WebMvcConfigurer` APIs are unchanged in Spring Framework 7 (underlying Boot 4.1.1) — not independently fetched against Boot-4.1-specific docs this session | Architecture Patterns | Low — these are long-stable, foundational Spring MVC APIs predating Spring Boot itself; a breaking change here would be a major, well-publicized framework event |

**If this table is empty:** N/A — assumptions listed above; none are HIGH risk enough to block planning, but A3 in particular is worth a one-line confirmation with the user during `/gsd-discuss-phase` follow-up or plan review if the team wants pepper-based defense-in-depth now rather than later.

## Open Questions

1. **Should the join response include the raw token in the JSON body, the resume URL, or both?**
   - What we know: The token must be shown to the client exactly once (never retrievable again, since only the hash is stored); D-02 says the resume link "contains" the token.
   - What's unclear: Whether the frontend (a later phase) will prefer a ready-made `resumeUrl` string or will construct its own URL from a raw `token` field — this doesn't block Phase 2 (both can be returned; it's cheap either way) but affects response DTO shape precisely.
   - Recommendation: Return both `token` and a pre-built `resumeUrl` in the join response; low cost, keeps both future consumers unblocked. Treat as Claude's discretion at plan time, not a blocking decision.

2. **Should `POST /api/sessions/{joinCode}/participants` reject an already-invalid/nonexistent join code with 404, or a more generic 400?**
   - What we know: REST convention favors 404 for "no such session resolves from this identifier" (the join code functions as the resource-lookup key in this URL).
   - What's unclear: Not discussed in CONTEXT.md; purely an HTTP-status nuance.
   - Recommendation: 404 for unknown join code — consistent with treating join code as a path-segment resource identifier, matches ordinary REST semantics, low risk either way.

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Colima (container runtime) | Testcontainers-backed repository/controller tests | ✓ | running, `docker info` OK this session | — |
| Local PostgreSQL 18 (docker-compose `db` service) | Manual dev-mode testing of new endpoints | ✓ | `db healthy` confirmed this session | — |
| Java (Temurin 21, via `JAVA_HOME` export) | `./gradlew` invocations | ✓ | 21.0.4 LTS confirmed this session | — |
| Gradle wrapper | Build/test | ✓ | 8.14.3 confirmed this session | — |

**Missing dependencies with no fallback:** none.
**Missing dependencies with fallback:** none.

**Machine-specific environment note (carried forward from Phase 1, still required):** every `./gradlew` invocation on this dev machine needs `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` exported; any test touching Testcontainers additionally needs `DOCKER_HOST=unix:///Users/psrg/.colima/default/docker.sock` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` exported, or the Ryuk reaper container fails to start under Colima [VERIFIED: .planning/STATE.md "Blockers/Concerns" section, re-confirmed working live this session via `docker info`, `java -version`, `./gradlew --version`].

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit 5 (`kotlin-test-junit5`) + Spring Boot Test (`spring-boot-starter-test`), Testcontainers Postgres — all already on the classpath from Phase 1 |
| Config file | none dedicated — configured via `build.gradle.kts` (`tasks.withType<Test> { useJUnitPlatform() }`) [VERIFIED: build.gradle.kts, read this session] |
| Quick run command | `./gradlew test --tests "*.SessionControllerTest"` (or the relevant new test class name) |
| Full suite command | `./gradlew test` |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
|--------|----------|-----------|-------------------|-------------|
| SESH-01 | Creating a session returns a unique join code; two rapid creations never collide | integration | `./gradlew test --tests "*.SessionServiceTest"` | ❌ Wave 0 |
| SESH-02 | Joining with a valid code + display name creates a Participant row and returns a token | integration | `./gradlew test --tests "*.ParticipantControllerTest"` | ❌ Wave 0 |
| SESH-03 | A request with no/invalid token cannot resolve as any participant (401) | integration | `./gradlew test --tests "*.CurrentParticipantArgumentResolverTest"` (or folded into `ParticipantControllerTest`) | ❌ Wave 0 |
| SESH-04 | A single session accepts 3+ distinct participant joins, each with a distinct token | integration | `./gradlew test --tests "*.ParticipantControllerTest"` | ❌ Wave 0 |
| SESH-05 | Re-authenticating with a previously issued token returns the same participant id, no duplicate row | integration | `./gradlew test --tests "*.ParticipantControllerTest"` | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** targeted `./gradlew test --tests "*.<NewTestClass>"`
- **Per wave merge:** `./gradlew test` (full suite — currently 6 tests from Phase 1, this phase adds new controller/service tests)
- **Phase gate:** Full suite green before `/gsd-verify-work`

### Wave 0 Gaps
- [ ] `src/test/kotlin/org/example/muvimatchr/session/SessionServiceTest.kt` — covers SESH-01 (join-code uniqueness/retry behavior)
- [ ] `src/test/kotlin/org/example/muvimatchr/session/ParticipantControllerTest.kt` — covers SESH-02, SESH-03, SESH-04, SESH-05 (full join/resume/multi-participant flow, likely via `@AutoConfigureMockMvc` layered on the existing `PostgresTestSupport` base class, matching Phase 1's real-Postgres integration-test style rather than mocking the repository layer)
- [ ] Framework install: none — JUnit 5, Spring Boot Test, and Testcontainers are already present from Phase 1; MockMvc auto-config comes free with `spring-boot-starter-test` (already a test dependency), no new Gradle coordinate needed

## Security Domain

### Applicable ASVS Categories

| ASVS Category | Applies | Standard Control |
|---------------|---------|-------------------|
| V2 Authentication | yes | Not username/password — a server-issued, high-entropy opaque bearer token stands in for authentication in this no-accounts app; `SecureRandom` + SHA-256 hash-at-rest (see Standard Stack) |
| V3 Session Management | yes (adapted) | The participant token is intentionally long-lived/non-expiring (per SESH-05's "resume later without losing progress" requirement) rather than a short-lived session — this is a deliberate scope decision, not an oversight; there is no logout/revoke endpoint in this phase (see Open Question below) |
| V4 Access Control | yes | A participant can only ever resolve as *themselves* — the token hash lookup is the sole access-control check; no roles/authorities exist (D-03), so there's no privilege-escalation surface to defend beyond "token X resolves to participant X, never anyone else" |
| V5 Input Validation | yes | `@NotBlank @Size(max = 100)` on `displayName` in the join request DTO, matching the existing `display_name VARCHAR(100)` column [VERIFIED: src/main/kotlin/org/example/muvimatchr/session/Participant.kt:22 — `@Column(name = "display_name", nullable = false, length = 100)`] |
| V6 Cryptography | yes | Never hand-roll: `SecureRandom` for generation, `MessageDigest("SHA-256")` for hashing — both JDK-provided, not custom (see Don't Hand-Roll) |

### Known Threat Patterns for this stack

| Pattern | STRIDE | Standard Mitigation |
|---------|--------|----------------------|
| Token brute-forcing / enumeration | Spoofing | 256-bit `SecureRandom` token — computationally infeasible to guess or enumerate |
| Token leakage via URL/access logs | Information Disclosure | Resume link necessarily carries the token once (accepted per D-02); subsequent API calls must use `Authorization: Bearer`, never repeat the token as a query parameter [CITED: cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html] |
| SQL injection via join-code/token lookup queries | Tampering | Continue this project's established pattern: Spring Data derived queries (`findByTokenHash`) or, if a native query is ever needed, named `@Param` bindings only — zero string interpolation, exactly as `VoteRepository.upsertVote` already established in Phase 1 |
| Display-name-based injection/overflow into a fixed-width column | Tampering | Bean Validation `@Size(max = 100)` at the DTO boundary, matching the DB column width, rejects oversized input before it reaches persistence |
| No token revocation/expiry mechanism | Elevation of Privilege (latent) | Explicitly out of scope for this phase per the locked decisions (no session lock/state machine); flagged here so it isn't silently forgotten if the product ever needs "kick a participant" or "expire old sessions" later |

## Sources

### Primary (HIGH confidence)
None — Context7/Exa/Brave/Firecrawl/Tavily are all disabled in this project's `.planning/config.json` (`exa_search`, `brave_search`, `firecrawl`, `tavily_search`, `ref_search`, `jina`, `perplexity` all `false`), so no HIGH-tier provider was available this session.

### Secondary (MEDIUM confidence — WebSearch cross-checked or WebFetch against official docs)
- cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html (fetched directly this session) — confirms fast hashes are unsuitable *for passwords* specifically; the token-storage inference from this is MEDIUM, not a direct standards citation
- cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html (WebSearch summary this session) — credentials should not appear in URLs
- docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/bearer-tokens.html (fetched directly this session) — `BearerTokenResolver` contract, `Authorization: Bearer` header convention
- github.blog/engineering/platform-security/behind-githubs-new-authentication-token-formats/ (WebSearch summary this session) — real-world production example of SHA-256 token hashing at a major engineering org
- Crockford Base32 spec summaries (WebSearch, multiple independent sources this session) — ambiguous-character-avoidance rationale

### Tertiary (LOW confidence — WebSearch only, not cross-checked)
- General blog/guide posts on API key hashing (apikeys.guide, Zuplo, cybersierra.co) — directionally consistent with the OWASP/GitHub sources above but not independently authoritative; used only to corroborate, not as sole support for any claim

## Metadata

**Confidence breakdown:**
- Standard Stack: MEDIUM — core primitives (SecureRandom, SHA-256, Base64) are JDK built-ins with well-established usage; the "why SHA-256 not bcrypt for tokens" reasoning is industry-consensus but not an OWASP-stated rule for tokens specifically
- Architecture: MEDIUM — endpoint/DTO shapes are original design following REST convention (CITED for 201/Location, MEDIUM for the rest); the custom-resolver-vs-spring-security tradeoff reasoning is sound first-principles analysis, not fetched from a single authoritative "don't use Spring Security for this" source
- Pitfalls: HIGH for pitfalls 2-4 (directly grounded in this project's own already-verified Phase 1 SUMMARY.md artifacts, which were read this session), MEDIUM for pitfall 1 (industry-consensus reasoning)

**Research date:** 2026-09-02
**Valid until:** 2026-10-02 (30 days — stable JDK/Spring APIs, no fast-moving dependency in scope this phase)
