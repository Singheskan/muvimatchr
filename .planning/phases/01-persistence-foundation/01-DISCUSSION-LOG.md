# Phase 1: Persistence Foundation - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-09-01
**Phase:** 1-Persistence Foundation
**Areas discussed:** Framework upgrade scope, Local dev database setup, Restart-survival test rigor

---

## Framework upgrade scope

| Option | Description | Selected |
|--------|-------------|----------|
| Upgrade now | Do the Boot 4.1 + Kotlin 2.3 bump in Phase 1 — already touching build.gradle.kts and adding first-time persistence deps, so bundling it avoids doing the migration twice and avoids building new JPA code against a soon-to-be-outdated 3.3.3/1.9.25 baseline (3.5 EOL'd 2026-06-30). | ✓ |
| Stay on current versions | Add JPA/Flyway/Postgres/Testcontainers to Boot 3.3.3/Kotlin 1.9.25 as-is; keep Phase 1 scoped to just persistence and defer the JSpecify/Jackson3/Kotlin-nullability migration risk to its own later step. | |

**User's choice:** Upgrade now
**Notes:** None provided beyond the selection.

---

## Local dev database setup

| Option | Description | Selected |
|--------|-------------|----------|
| Testcontainers-only | Skip docker-compose.yml this phase; Testcontainers already gives a real Postgres for every test run, add compose in Phase 2 once there's an endpoint worth manually hitting with bootRun. | |
| Add docker-compose.yml now | Set up local Postgres via docker-compose alongside Testcontainers, so `./gradlew bootRun` already works against a real DB even before Phase 2's endpoints exist. | ✓ |

**User's choice:** Add docker-compose.yml now
**Notes:** None provided beyond the selection.

---

## Restart-survival test rigor

| Option | Description | Selected |
|--------|-------------|----------|
| Full context restart | Write rows via one Spring context wired to a Testcontainers Postgres, shut that context down, start a fresh Spring context pointed at the same container, then read back — the only version that proves "survives a server restart" rather than "survives a Hibernate cache clear." | ✓ |
| Repository round-trip | Write via a repository, clear/detach the persistence context (EntityManager.clear()), then read back in the same running app — faster to write, but doesn't prove the app process itself can restart. | |

**User's choice:** Full context restart
**Notes:** None provided beyond the selection.

---

## Claude's Discretion

- Exact Flyway migration file granularity (one migration for all three tables vs. one per table).
- Primary key strategy details beyond what ARCHITECTURE.md already specifies.
- Join code column type/length (actual code-generation logic belongs to Phase 2).
- Package structure — follow the package-by-feature layout from ARCHITECTURE.md.

## Deferred Ideas

None — discussion stayed within phase scope. Participant "active"/"left" status modeling was intentionally not raised here; STATE.md already flags it as a Phase 2/4 product decision, out of scope for this schema-only phase.
