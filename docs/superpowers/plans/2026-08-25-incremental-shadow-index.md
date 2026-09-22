# Incremental Shadow Index Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `test-driven-development` and execute each checkbox in order. This session executes inline because project policy does not authorize subagents.

**Goal:** Add a persisted incremental `ShadowIndex` that reuses unchanged chunks, keeps the active index readable during construction, and promotes only a validated candidate whose base epoch and generation are still current.

**Architecture:** Keep `VectorStore` as the storage seam. A scoped shadow-build session owns candidate lifecycle and persists candidate chunks, relations, and state in dedicated SQLite tables. `CodeIndex` consumes the existing dirty-file queue, embeds only changed files, rebuilds the relation graph, validates the candidate, and promotes it through the existing epoch/generation CAS.

**Tech Stack:** Java 17, SQLite JDBC, JUnit 5, Maven

---

### Task 1: Lock the persisted candidate contract

**Files:**
- Modify: `src/test/java/com/devcli/rag/VectorStoreTest.java`
- Modify: `src/main/java/com/devcli/rag/VectorStore.java`

- [ ] Add a failing test proving staged chunks are invisible before promotion.
- [ ] Add a failing test proving incremental candidates retain unchanged active chunks.
- [ ] Add a failing test proving stale-base promotion is rejected without changing the active index.
- [ ] Run `mvn -q -DskipTests=false -Dtest=VectorStoreTest test` and confirm failure is caused by the missing shadow-build interface.
- [ ] Add `code_shadow_chunks`, `code_shadow_relations`, and `code_shadow_state` with additive `CREATE TABLE IF NOT EXISTS` migration.
- [ ] Add a scoped `ShadowIndexSession` interface with `stageChunks`, `stageRelations`, `validate`, `promote`, and rollback-on-close semantics.
- [ ] Implement CAS promotion inside one SQLite transaction, then rerun `VectorStoreTest`.

### Task 2: Consume the dirty-file queue incrementally

**Files:**
- Modify: `src/test/java/com/devcli/rag/CodeIndexTest.java`
- Modify: `src/main/java/com/devcli/rag/CodeIndex.java`

- [ ] Add a failing test that indexes two files, dirties one, and expects the second build to embed only the dirty file while preserving the unchanged file.
- [ ] Run `mvn -q -DskipTests=false -Dtest=CodeIndexTest test` and confirm the current full rebuild violates the assertion.
- [ ] Expose a normalized read-only dirty-file snapshot from `VectorStore`.
- [ ] Make `CodeIndex.index` select full mode for an empty/missing active index and incremental mode when dirty files exist.
- [ ] In incremental mode, stage reused unchanged chunks, embed only existing dirty files, treat missing dirty files as deletions, rebuild relations, validate, and promote.
- [ ] Preserve the existing public `CodeIndex.index` and legacy `VectorStore.replaceProjectIndex` interfaces.
- [ ] Rerun `CodeIndexTest` and `VectorStoreTest`.

### Task 3: Synchronize architecture documentation

**Files:**
- Modify: `docs/context-invalidation-shadow-index-design.md`
- Modify: `docs/design-notes/04-memory-context-governance-design.md`
- Modify: `AGENTS.md`

- [ ] Replace stale “not implemented” claims with the delivered persisted candidate and CAS promotion behavior.
- [ ] State the remaining limitation: relation extraction is project-wide while chunk embedding is dirty-file incremental; background scheduling and shard-level blocking remain future work.
- [ ] Verify Markdown headings, code fences, and links.

### Task 4: Verification and delivery

**Files:**
- Verify all modified files.

- [ ] Run `mvn -q -DskipTests test-compile`.
- [ ] Run `mvn -q -DskipTests=false -Dtest=VectorStoreTest,CodeIndexTest,CodeRetrieverTest,ToolRegistryTest,ProtocolBaselineGateTest test`.
- [ ] Run `mvn -q -Pquick test`.
- [ ] Run `git diff --check`, stale-reference searches, `git diff`, and `git status --short`.
- [ ] Commit with a concise Chinese Conventional Commit message.
- [ ] Fetch, verify branch divergence, and push without force.
