# Phase 25: RAG Search Intent Control

> Status: implemented

## Goal

Let the Agent pass retrieval intent into `search_code` while keeping backend
guardrails against hallucinated parameters.

PaiCLI is an Agent CLI, so the outer LLM often knows whether it is looking for a
call chain, a definition, an error location, or a config item. The RAG layer now
accepts that intent as a hint, but does not blindly trust it.

## Tool Parameters

`search_code` now accepts:

```text
query          required
top_k          optional, clamped to 1-30
mode           optional: auto / general / call_chain / definition / error_trace / config
graph_depth    optional, clamped to 0-3
```

## Backend Guardrails

The backend normalizes and checks LLM-provided parameters:

- unknown `mode` falls back to `auto`
- `auto` infers intent from query keywords
- obvious query/mode conflicts are corrected by backend rules
- `graph_depth` is clamped to 0-3
- non-call-chain modes narrow or disable graph expansion
- empty results fall back to general search

This keeps the LLM useful as an intent provider without letting it control
retrieval cost or context expansion unboundedly.

## Search Behavior

Mode defaults:

```text
call_chain   graph depth 3
general      graph depth 1
error_trace  graph depth 1
definition   graph depth 0
config       graph depth 0
```

`hybridSearch` remains as a compatibility entry and maps to call-chain style
retrieval with bounded graph expansion.

Retrieval is now routed by resolved intent:

- `general`: semantic recall first, then keyword recall, with light graph expansion
- `call_chain`: keyword/entry recall plus semantic recall, then bounded graph expansion
- `definition`: keyword/symbol recall first, semantic recall only when keyword recall is empty
- `config`: keyword/config-key recall first, semantic recall only when keyword recall is empty
- `error_trace`: error keyword recall first, semantic recall as a supplement, with light graph expansion

This keeps natural-language discovery broad while avoiding unnecessary embedding
calls and vector noise for exact definition/config queries.

## Explicit Non-Goal

This phase does not add LLM reranking or LLM scoring. Retrieval ranking remains
deterministic: vector similarity, keyword boost, type boost, graph expansion
score, and per-file limiting.

## Verification

Targeted tests:

```bash
mvn test -Dtest=CodeSearchModeTest,CodeRetrieverTest,ToolRegistryTest -DskipTests=false
```

Covered scenarios:

- hallucinated mode falls back to inferred intent
- query intent overrides conflicting LLM-provided mode
- graph depth is clamped and narrowed by mode
- definition mode disables deep graph expansion
- definition/config style modes prefer keyword recall before embedding fallback
