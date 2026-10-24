# SDD ledger — plan: docs/superpowers/plans/2026-10-24-task-2-network-state-coordinator.md

- Android base: `28ac7fc134d6248ae0c9720b0ebd1c426e27356e` (`origin/main`, 2026-10-24).
- Branch: `feature/task-2-network-state-coordinator`, created from the clean local `main` baseline before Task 2 changes.
- Scope: Task 2 contract freeze and Batch decomposition only; no Task 2 product implementation, no Task 3, no merge to `main`.
- Upstream MPD-Server baseline checked via `git ls-remote`: `2ad84454ceb70817bd996c92b590cfa2d9e9b128`, matching the four Android authority documents' recorded source baseline.
- Authority reviewed: Android client master plan; product/engineering spec; backend API/realtime protocol contract; Web behavior inheritance contract; client/server boundary and architecture contract; Task 0 environment/signing contract; Task 1 baseline contract and implementation plan.
- Frozen Task 2 scope: origin/HTTP/WS, strict DTO/wire handling, complete snapshot Store, one realtime controller, one mutation coordinator and shared activity/error state; no UI/business features, local business DB, server changes, CI, or cross-process mutation journal.
- Contract matrix explicitly maps each invariant to authority, implementation boundary, machine tests and real-service/manual evidence.
- Batch plan: 0 upstream recheck/interface mapping; 1 HTTP/origin/DTO; 2 realtime transport/frame lifecycle; 3 canonical Store/watermarks/read coalescing; 4 app-level single runtime/controller ownership; 5 mutation idempotency/unknown/activity recovery; 6 integration verification and exit review.
- Verification status: planning documents pending review and commit; no product implementation or tests run for Task 2. Task 1 baseline command will be run as part of final documentation verification.
