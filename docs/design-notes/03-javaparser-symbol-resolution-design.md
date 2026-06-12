# JavaParser Symbol Resolution Improvement Design

## Status

Implemented as a first hardening phase.

Shipped in this phase:

- Added `javaparser-symbol-solver-core`.
- `CodeAnalyzer（代码分析器）` now attempts JavaParser `SymbolSolver（符号求解器）` resolution before falling back to source-local receiver inference.
- `CodeRelation（代码关系）` carries `resolutionSource（解析来源）`, `confidence（置信度）`, and `classpathEpoch（类路径版本）`.
- `ClasspathEpoch（类路径版本）` is derived from build descriptor content such as `pom.xml`.
- `VectorStore（向量存储）` persists relation metadata with lazy schema migration.
- Graph expansion now uses relation confidence as part of relation scoring.

Current boundary:

- This is not full Maven external dependency resolution yet.
- Third-party Jar public API indexing remains future work.
- Lombok, generated sources, and Spring proxy semantics still require compile/LSP validation.

Verification:

```text
mvn test '-Dtest=CodeAnalyzerTest,ClasspathEpochTest,VectorStoreTest,CodeRetrieverTest,RetrievalFusionTest,CodeRagBenchmarkTest,CodeIndexTest' -DskipTests=false
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
```

## 暴露的问题

面试官多次指出：单纯 AST 并不等于完整代码理解。

典型追问：

- `userService.save(userDto)` 里的 `userService` 到底是什么类型？
- 没有 `SymbolSolver（符号求解器）`，怎么处理同名方法？
- Spring 接口多实现、Lombok、AOP、父类字段怎么办？
- `pom.xml` 变化后 classpath 怎么跟进？
- JavaParser 和 `LSP（语言服务器协议）` 结果冲突时听谁的？

## 当前已有

- JavaParser 用于 Java AST 解析。
- `CodeChunker（代码切片器）` 支持 class/method 粒度。
- `CodeAnalyzer（代码分析器）` 提取 imports、extends、implements、calls、contains。
- receiver type 有轻量推断：字段、局部变量、构造器参数、import。
- 编译器、Maven、LSP 作为 correctness gate（正确性关口）。

## 不足

- 当前不是完整 `Symbol Resolution（符号消解）`。
- 没有 Maven `Classpath（类路径）` 支持。
- 没有三方 Jar public API 索引。
- 没有 `ClasspathEpoch（类路径版本）`。
- 没有 `SymbolConfidence（符号置信度）`。
- 对 Lombok、注解生成代码、Spring 代理支持有限。

## 怎么修改

### 1. 分层符号解析

设计四级解析：

```text
Tier 0: AST extraction
Tier 1: source-only symbol table
Tier 2: Maven classpath + JavaParser SymbolSolver
Tier 3: LSP verified edges
```

每条关系都记录来源：

- `AST_INFERRED（AST 推断）`
- `SOURCE_RESOLVED（源码内解析）`
- `CLASSPATH_RESOLVED（类路径解析）`
- `LSP_VERIFIED（LSP 验证）`

### 2. 引入 SymbolConfidence

每条 relation 加置信度：

```text
calls: UserController.create -> UserService.save
confidence: 0.65
source: SOURCE_RESOLVED
```

置信度影响 graph expansion 排序和提示词措辞。

### 3. 引入 ClasspathEpoch

当 `pom.xml`、`build.gradle`、source root 变化：

```text
classpath_epoch++
external_symbol_cache -> stale
affected Java files -> re-resolve
old classpath edges -> degraded
```

### 4. LSP 作为验证源

不直接用 LSP 替代 JavaParser，而是作为 high-confidence verifier：

- Definition.
- Reference.
- Type hierarchy.
- Diagnostic.

JavaParser 负责低成本全量覆盖，LSP 负责高置信校正。

### 5. 冲突优先级

当视图冲突：

```text
current file content
> javac/Maven
> LSP
> JavaParser symbol graph
> vector semantic match
```

## 设计边界

- 不把轻量 AST 推断说成完整编译器能力。
- 不为了 SymbolSolver 牺牲 CLI 启动速度。
- 不把 LSP 作为唯一索引来源。
- 不让低置信关系驱动高风险代码修改。

## 验收标准

- relation 记录 source 和 confidence。
- pom.xml 变化能触发 classpath stale。
- search result 能提示 unresolved 或 low-confidence call。
- LSP verified relation 权重大于 AST inferred relation。
- 编译失败仍然优先于索引判断。

## 文字解释

面试时可以这样讲：

> 当前 PaiCLI 用 JavaParser 做的是低成本 AST 索引，不会把它夸成完整编译器。对于 `userService.save()` 这种调用，当前会基于字段、局部变量、import 做 best-effort 类型推断，但生产级要升级为分层符号解析：先做源码内符号表，再接 Maven classpath 和 JavaParser SymbolSolver，最后把 LSP 的 definition/reference 写成高置信 verified edge。每条关系都带 `SymbolConfidence（符号置信度）` 和来源，graph expansion 会优先使用高置信边。pom.xml 变化时通过 `ClasspathEpoch（类路径版本）` 让旧边降级或失效。
