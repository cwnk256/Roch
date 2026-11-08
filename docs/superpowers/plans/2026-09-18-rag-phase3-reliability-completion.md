# RAG Phase 3 Reliability Completion Implementation Plan

**Goal:** Complete the next backend RAG reliability increment by making source documents reusable, enforcing evidence
integrity, preventing duplicate task execution, and exposing task progress through SSE.

## Tasks

### Task 1: Source document storage and no-file reindex

- [ ] Add failing tests for save/read/missing source.
- [ ] Implement storage, source download, and no-file reindex.
- [ ] Run focused and full backend tests.

### Task 2: Evidence integrity validation

- [ ] Add failing validator tests.
- [ ] Validate generated evidence against task scope before persistence/export.

### Task 3: Atomic task claim

- [ ] Add a concurrent claim integration test.
- [ ] Add conditional PENDING-to-RUNNING claim and protect Runner.

### Task 4: Task progress SSE

- [ ] Add stream endpoint test.
- [ ] Implement progress broadcaster and terminal events.

### Task 5: Documentation and status

- [ ] Update README and Phase 3 status.
- [ ] Run backend and frontend verification.
