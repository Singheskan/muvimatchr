---
schema_version: 1
open_count: 3
waived_count: 0
fixed_count: 0
total_count: 3
last_updated: 2026-09-04T13:29:40.679Z
---

# Broken Windows Ledger

> Cross-phase defect register. With `workflow.windows_enforce` enabled, `/gsd-ship` blocks while `open_count > 0`.
> Waive with `gsd-tools windows waive <id> "<reason>"` (reason required).
> Mark fixed with `gsd-tools windows fixed <id>`.

| id | phase | kind | file | line | description | status | reason | recorded_at | resolved_at |
|----|-------|------|------|------|-------------|--------|--------|-------------|-------------|
| 1 | 03 | unrun-verify | .planning/phases/03-tmdb-integration-catalog-caching/03-04-PLAN.md |  | Task 3 human-check: TMDB with_watch_providers monetization-type default (A1) not re-confirmed live -- TMDB_API_TOKEN unavailable on this dev machine | open |  | 2026-09-04T13:12:59.583Z |  |
| 2 | 03 | unrun-verify | .planning/phases/03-tmdb-integration-catalog-caching/03-05-PLAN.md |  | Task 1 human-check: live-TMDB real-data confirmation (ROADMAP Phase 3 criterion 1 word real) not re-confirmed -- TMDB_API_TOKEN unavailable on this dev machine | open |  | 2026-09-04T13:29:34.804Z |  |
| 3 | 03 | unrun-verify | .planning/phases/03-tmdb-integration-catalog-caching/03-05-PLAN.md |  | Task 2 human-check: live sparse-filter experience against real TMDB not re-confirmed -- TMDB_API_TOKEN unavailable on this dev machine | open |  | 2026-09-04T13:29:40.679Z |  |

````json
[
  {
    "id": 1,
    "kind": "unrun-verify",
    "phase": "03",
    "file": ".planning/phases/03-tmdb-integration-catalog-caching/03-04-PLAN.md",
    "line": null,
    "description": "Task 3 human-check: TMDB with_watch_providers monetization-type default (A1) not re-confirmed live -- TMDB_API_TOKEN unavailable on this dev machine",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-09-04T13:12:59.583Z",
    "resolved_at": null
  },
  {
    "id": 2,
    "kind": "unrun-verify",
    "phase": "03",
    "file": ".planning/phases/03-tmdb-integration-catalog-caching/03-05-PLAN.md",
    "line": null,
    "description": "Task 1 human-check: live-TMDB real-data confirmation (ROADMAP Phase 3 criterion 1 word real) not re-confirmed -- TMDB_API_TOKEN unavailable on this dev machine",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-09-04T13:29:34.804Z",
    "resolved_at": null
  },
  {
    "id": 3,
    "kind": "unrun-verify",
    "phase": "03",
    "file": ".planning/phases/03-tmdb-integration-catalog-caching/03-05-PLAN.md",
    "line": null,
    "description": "Task 2 human-check: live sparse-filter experience against real TMDB not re-confirmed -- TMDB_API_TOKEN unavailable on this dev machine",
    "status": "open",
    "reason": "",
    "recorded_at": "2026-09-04T13:29:40.679Z",
    "resolved_at": null
  }
]
````
