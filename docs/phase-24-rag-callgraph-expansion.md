# Phase 24: RAG Call Graph Expansion

> Status: implemented

## Goal

Enhance code RAG for common Java web application chains such as:

```text
Controller -> Service interface -> ServiceImpl -> Mapper / DAO
```

The previous RAG flow stored code relations in SQLite, but the main
`hybridSearch` path mainly used semantic recall, keyword recall, and type
boosting. This phase connects the stored relation graph back into retrieval.

## What Changed

### 1. Better `calls` Relation Modeling

`CodeAnalyzer` now resolves common receiver-based method calls.

Before:

```text
UserController.detail -> detail
```

After:

```text
UserController.detail -> UserService.detail
```

The implementation uses JavaParser AST and a lightweight receiver map from
field / local variable names to declared types. It is intentionally heuristic:
it covers common Spring-style field calls such as `userService.detail()` and
`userMapper.selectById()`, but it is not a full Java symbol solver.

### 2. Interface / Implementation Method Links

For classes that `implements` or `extends` another type, method-level links are
also recorded:

```text
UserServiceImpl.detail -> UserService.detail  implements
```

This allows retrieval to move from a Service interface method to its
implementation method, then continue along the implementation's `calls`
relations.

### 3. Bounded Multi-Hop Graph Expansion

`CodeRetriever.hybridSearch` now expands graph neighbors after the initial
semantic + keyword candidate merge.

Expansion limits:

- max depth: 3 hops
- seed candidates: top 5
- relations per node: 5
- added graph chunks: 12
- relation whitelist: `calls`, `implements`, `extends`, `contains`

The expansion is bounded on purpose. It improves call-chain recall without
turning retrieval into unbounded graph traversal or flooding the LLM context.

### 4. Chunk Lookup By Relation Name

`VectorStore` now supports looking up chunks by relation names such as:

```text
UserService.detail
```

It matches exact chunk names and method signatures with the same prefix, for
example:

```text
UserService.detail(Long id)
```

This is needed because method chunks are stored with signatures, while graph
relations store normalized `ClassName.method` names.

## Verification

Targeted tests:

```bash
mvn test -Dtest=CodeAnalyzerTest,CodeRetrieverTest,VectorStoreTest -DskipTests=false
```

Covered scenarios:

- field receiver call resolution: `userService.detail()` -> `UserService.detail`
- 3-hop graph expansion:
  - `UserController.detail`
  - `UserService.detail`
  - `UserServiceImpl.detail`
  - `UserMapper.selectById`

## Boundaries

This is not a full static analysis engine.

Known limits:

- dynamic proxy / reflection calls are not resolved
- complex local variable reassignment may be missed
- overloaded methods are matched by name prefix, not full signature binding
- multiple interface implementations are all candidates and rely on scoring
- MyBatis XML mapping is not parsed in this phase

The feature is best described as bounded call graph expansion for RAG context,
not precise whole-program call-chain analysis.
