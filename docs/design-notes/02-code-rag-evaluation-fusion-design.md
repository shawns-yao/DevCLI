# Code RAG Evaluation And Fusion Design

## Status

Implemented.

Shipped in this phase:

- `RetrievalFusion（检索融合）` implements `RRF（倒数排名融合）` across keyword, semantic, and graph channels.
- `CodeRetriever（代码检索器）` now routes each retrieval channel into `RetrievalFusion`.
- `CrossEncoderReranker（交叉编码器重排器）` runs as the second-stage reranker after RRF and symbol-aware boost.
- `CodeRagBenchmark（代码 RAG 基准评估）` evaluates `Recall@k（前 k 召回率）`, `Precision@k（前 k 精度）`, and `MRR（平均倒数排名）`.
- Tests cover RRF ranking and benchmark reporting.

Verification:

```text
mvn test '-Dtest=CodeRetrieverTest,RetrievalFusionTest,CodeRagBenchmarkTest,VectorStoreTest,CodeSearchModeTest' -DskipTests=false
Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
```

## 暴露的问题

围绕 `Code RAG（代码检索增强生成）` 的面试问题集中在两个方向：

- 检索融合是否严谨。
- 召回效果是否能证明。

面试官反复追问：

- `Keyword Search（关键词检索）`、`Semantic Search（语义检索）`、`Graph Expansion（图扩展）` 的分数不在同一量纲，怎么合并？
- SQLite 本身没有向量索引，当前向量检索性能边界在哪里？
- `recall@5（前 5 召回率）` 怎么测？
- 什么结果算有效召回？
- 代码检索的 `Ground Truth（黄金标准）` 怎么定义？

## 当前已有

- `CodeChunker（代码切片器）` 按 Java class/method 粒度切片。
- `CodeAnalyzer（代码分析器）` 提取 imports、extends、implements、calls、contains。
- `VectorStore（向量存储）` 用 SQLite 持久化 chunk、embedding、relation。
- `CodeRetriever（代码检索器）` 支持 keyword + semantic + bounded graph expansion。
- 图扩展已有上限：depth、seed、relations per node、total graph chunks。
- `search_code（代码检索）` 支持 mode 和 graph_depth 等意图参数。

## 不足

- 多路召回融合偏 `heuristic（启发式）`。
- 没有正式 `RRF（倒数排名融合）`。
- 没有可复现 `CodeRagBenchmark（代码 RAG 基准集）`。
- 没有固定的 must-have / should-have / nice-to-have 评估口径。
- 召回报告没有拆分 keyword、semantic、graph 的贡献。
- SQLite JSON embedding 在大规模代码库下性能有限。

## 怎么修改

### 1. 抽象 RetrievalFusion

新增 `RetrievalFusion（检索融合）` 组件：

```text
keyword candidates
+ semantic candidates
+ graph candidates
-> rank normalization
-> RRF fusion
-> symbol-aware boost
-> CrossEncoder rerank
-> context budget trim
```

### 2. 使用 RRF 作为基础融合

`RRF（倒数排名融合）` 不依赖各通道原始分数：

```text
score += 1 / (k + rank)
```

适合融合：

- keyword rank.
- semantic rank.
- graph rank.

### 3. 增加符号加权和 CrossEncoder Rerank

RRF 后再加 deterministic boost：

- 精确 file path 命中。
- 精确 class/method 命中。
- error stack 文件和行号命中。
- `implements（接口实现）` 命中。
- `extends（继承关系）` 命中。
- test 与 production 文件配对命中。

之后再调用 `CrossEncoderReranker（交叉编码器重排器）` 做二阶段语义相关性排序：

- 默认开启。
- 默认使用本地 Docker 暴露的 OpenAI-compatible `/rerank` endpoint。
- rerank 服务不可用时降级回 RRF 结果。

### 4. 建 CodeRagBenchmark

数据集分三类：

```text
KnownFixCase（已知修复案例）
CompileErrorCase（编译错误案例）
SyntheticSymbolQuery（合成符号查询）
```

每条样例包含：

- query.
- repo snapshot.
- must_have files/chunks.
- should_have files/chunks.
- optional files/chunks.
- expected symbols.

### 5. 输出检索报告

每次 benchmark 输出：

- `Recall@k（前 k 召回率）`
- `MRR（平均倒数排名）`
- `Precision@k（前 k 精度）`
- `ContextPrecision（上下文精度）`
- topK token cost.
- keyword/semantic/graph contribution.

## 设计边界

- 不用未标注数据吹高 recall。
- 不把语义相似度高当成符号正确。
- 不为了 recall 盲目扩大 topK。
- 不让 graph expansion 变成无界图遍历。

## 验收标准

- 本地能运行 RAG benchmark。
- 输出包含每条 query 的 topK、命中原因和融合分数。
- recall 指标必须带数据集名称。
- RRF 可开关，对比旧融合策略。
- 语义高分但符号不匹配的结果会降权。

## 文字解释

面试时可以这样讲：

> 当前 DevCLI 的 RAG 已经不是单纯向量检索，而是 keyword、semantic 和 graph expansion 的混合检索。融合层先用 `RRF（倒数排名融合）` 解决不同通道分数量纲不一致的问题，再叠加符号级 boost，最后用 `CrossEncoderReranker（交叉编码器重排器）` 对候选代码块做二阶段重排。评估上不会空口说 recall，而是构建 `CodeRagBenchmark（代码 RAG 基准集）`，把已知修复案例、编译错误案例、合成符号查询分开测，指标同时看 `Recall@k（前 k 召回率）`、`MRR（平均倒数排名）`、`Precision@k（前 k 精度）` 和 Agent 后续编译通过率。
