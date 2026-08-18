# Unified Plan Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use test-driven development and execute this plan task-by-task. Do not commit or push unless the user explicitly requests it.

**Goal:** Expose one `/plan` orchestration entry that always uses the existing Planner/Worker/Reviewer DAG pipeline, while retaining hidden compatibility parsing for legacy Team commands.

**Architecture:** Keep ReAct as the default path. Normalize `/plan`, `/plan --team`, and `/team` to the current Team-capable `AgentOrchestrator`, record the public mode as `plan`, and let DAG readiness determine serial or parallel execution. Keep `PlanExecuteAgent` and the STANDARD profile only as internal compatibility code for now; remove them from the interactive routing and documentation.

**Tech Stack:** Java 17, JLine, JUnit 5, Maven

---

## Task 1: Command contract

**Files:**
- Modify: `src/test/java/com/devcli/cli/CliCommandParserTest.java`
- Modify: `src/test/java/com/devcli/agent/OrchestrationProfileTest.java`
- Modify: `src/main/java/com/devcli/cli/CliCommandParser.java`
- Modify: `src/main/java/com/devcli/agent/OrchestrationProfile.java`

- [x] Write failing tests proving `/plan` selects the reviewer/checkpoint-capable pipeline.
- [x] Write compatibility tests for `/plan --team` and `/team` normalization.
- [x] Run tests and confirm RED.
- [x] Normalize public Plan metadata to `snapshotMode=plan` and `displayName=Plan`.
- [x] Run tests until green.

## Task 2: Single interactive execution path

**Files:**
- Modify: `src/test/java/com/devcli/cli/MainTeamPlanReviewTest.java`
- Modify: `src/test/java/com/devcli/cli/MainTeamResumeParseTest.java`
- Modify: `src/main/java/com/devcli/cli/Main.java`
- Modify: `src/main/java/com/devcli/agent/AgentOrchestrator.java`

- [x] Write failing tests for Plan naming, Plan resume parsing, and unified review text.
- [x] Remove the STANDARD/TEAM switch from interactive execution.
- [x] Route `/plan` through `AgentOrchestrator` and store snapshots as `plan`.
- [x] Rename user-visible Team messages and recovery instructions to Plan.
- [x] Run focused CLI and Orchestrator tests until green.

## Task 3: Help, completion, and compatibility surface

**Files:**
- Modify: `src/test/java/com/devcli/cli/DevCliCompleterTest.java`
- Modify: `src/main/java/com/devcli/cli/Main.java`

- [x] Write a failing test proving help/completion expose `/plan` and `/plan resume`, not Team choices.
- [x] Hide `/plan --team` and `/team` from help/completion while preserving parser compatibility.
- [x] Run CLI tests until green.

## Task 4: Documentation and verification

**Files:**
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `TODO.md`
- Modify: `docs/agents-reference.md`
- Modify: `docs/interview-agent-architecture-review.md`
- Modify: `docs/interview-agent-current-vs-production-qa.md`

- [x] Document ReAct + unified Plan as the only public mode model.
- [x] Clarify that serial/parallel execution is a DAG property, not a user-selected profile.
- [x] Run focused parser, CLI, profile, resume, and Orchestrator tests.
- [x] Skip full regression per the user's explicit instruction.
- [ ] Run `git diff --check` and inspect scoped changes.
