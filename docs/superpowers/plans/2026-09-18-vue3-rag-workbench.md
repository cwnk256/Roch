# Vue 3 RAG Workbench Implementation Plan

**Goal:** Build a concise Vue 3 workbench that exposes dashboard, single-turn chat, multi-turn chat, file search,
document import/management, and document report workflows through the existing REST APIs.

**Architecture:** Create an independent `web/` Vite application with Vue Router, a small Axios API client, and
page-local state. Use Vite development proxying to the Spring Boot API. Keep the shell, API client, and reusable UI
primitives separate from feature pages.

**Tech Stack:** Vue 3, TypeScript, Vite, Vue Router, Axios, Vitest.

## Global Constraints

- Use real backend endpoints under `/api`; do not invent a second mock backend.
- Keep the interface compact and operational: restrained colors, dense lists, clear empty/loading/error states.
- Preserve the existing backend contract, including multipart upload and SSE multi-turn streaming.
- Run `npm run build` and browser verification against the Vite dev server before completion.

---

### Task 1: Frontend scaffold and API boundary

**Files:**

- Create: `web/package.json`
- Create: `web/index.html`
- Create: `web/vite.config.ts`
- Create: `web/tsconfig.json`
- Create: `web/src/main.ts`
- Create: `web/src/router.ts`
- Create: `web/src/api/http.ts`
- Create: `web/src/api/index.ts`
- Create: `web/src/types.ts`

- [ ] Add Vue/Vite/TypeScript scripts and dependencies.
- [ ] Configure `/api` proxy to `http://localhost:8080`.
- [ ] Add typed API methods for chat, conversations, documents, search, uploads, and document tasks.
- [ ] Add route metadata for all seven pages.

### Task 2: Workbench shell and shared styling

**Files:**

- Create: `web/src/App.vue`
- Create: `web/src/styles.css`
- Create: `web/src/components/AppShell.vue`
- Create: `web/src/components/StatusBadge.vue`
- Create: `web/src/components/EmptyState.vue`
- Create: `web/src/components/LoadingBlock.vue`

- [ ] Build responsive sidebar navigation and mobile header.
- [ ] Add shared page header, buttons, inputs, tables, badges, cards, and responsive breakpoints.
- [ ] Keep page sections unframed; use cards only for repeated records and focused tools.

### Task 3: Feature pages

**Files:**

- Create: `web/src/views/DashboardView.vue`
- Create: `web/src/views/SingleChatView.vue`
- Create: `web/src/views/MultiTurnChatView.vue`
- Create: `web/src/views/FileSearchView.vue`
- Create: `web/src/views/ImportDocumentView.vue`
- Create: `web/src/views/DocumentManagementView.vue`
- Create: `web/src/views/DocumentReportsView.vue`

- [ ] Connect dashboard counters to document and task list APIs.
- [ ] Connect single-turn chat to `/api/chat`.
- [ ] Connect multi-turn chat and conversation history to `/api/chat/*`, including stream fallback handling.
- [ ] Connect file search, uploads, document details/chunks, delete and reindex.
- [ ] Connect report task creation, polling, result loading, artifact listing/download, retry and re-export.
- [ ] Add loading, empty, error, validation and success states for every workflow.

### Task 4: Verification

- [ ] Run `npm install` and `npm run build`.
- [ ] Start Vite on an available port.
- [ ] Use browser automation to verify desktop and mobile layouts and primary navigation.
- [ ] Inspect git diff and report any backend endpoints that remain unavailable.
