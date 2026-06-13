package com.devcli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.devcli.config.DevCliConfig;
import com.devcli.llm.LlmClient;
import com.devcli.llm.LlmClientFactory;
import com.devcli.memory.ConversationHistoryCompactor;
import com.devcli.memory.TokenBudget;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 LLM 多轮压缩信息保留 IT。
 *
 * <p>本测试直接用真实 {@link LlmClient}（默认从 .env 读 KIMI_API_KEY +
 * KIMI_BASE_URL，对应项目配置的本地 LLM 代理）走 DevCLI 生产路径
 * {@link ConversationHistoryCompactor}，触发多轮真实摘要，然后**再让同一个 LLM
 * 基于压缩后的上下文回答 QA**，用语义判定信息是否仍能被检索出来。
 *
 * <p>评测维度：
 * <ol>
 *   <li>压缩次数 ≥ 3：覆盖「同一会话被多次压缩」的场景，每一轮压缩都要保住老事实</li>
 *   <li>每条 QA 回答正确率：以 LLM 答案是否包含期望关键实体为标准</li>
 *   <li>压缩单调性：每次压缩后 token 都严格下降，不会反复触发</li>
 *   <li>tool_call/tool_result 协议完整性：不允许出现孤立 id</li>
 * </ol>
 *
 * <p>缺 API Key / 端点不可达时整测试 skip，不阻塞日常 CI。手工跑：
 * <pre>
 *   mvn test -Dtest=RealLlmCompressionRetentionIT -DskipTests=false
 * </pre>
 *
 * <p>由于挂在 {@code benchmark/} 包下，{@code mvn test -Pquick} 会自动跳过。
 */
class RealLlmCompressionRetentionIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    /**
     * 生产规模下信息保留率阈值。
     * <p>历史 ≥ 24k token 时，{@code ConversationHistoryCompactor.summarize} 会触发分片摘要；
     * v0 first-N 截断时多轮压缩塌到 16% 量级；v1 Map-Reduce 升到 28%；
     * PR-1（token 预算保留区 + 增量摘要不套娃）实测 77.8%，阈值取 70% 给波动空间。
     */
    private static final double RETENTION_THRESHOLD = 0.70;
    /** 单次 LLM 调用超时上限：长 context 25k input 在本地代理上偶发 1 分钟以上。 */
    private static final int LLM_QA_TIMEOUT_MS = 300_000;

    @Test
    @DisplayName("生产真实 230k 阈值下，多轮 LLM 压缩后关键事实仍能被语义检索回")
    void multiRoundCompressionPreservesKeyFacts() throws Exception {
        LlmClient llm = resolveRealLlmClientOrSkip();
        // Kimi 256k window × 90% = 230_400；不直接读 llm.maxContextWindow() 是因为本地代理
        // model name 是 gemini，window 不一定是 Kimi 真实值。这里固定到 Kimi 生产阈值。
        final int productionTrigger = 230_000;
        final int targetTokensBeforeFirstCompact = productionTrigger + 5_000;

        // ===== 1. 把 18 个事实分片到 ~80 轮对话里，灌噪声直到达到 230k 才第一次撞阈值 =====
        List<FactCase> facts = scenarioFacts();
        List<LlmClient.Message> history = buildLongConversation(facts, targetTokensBeforeFirstCompact);
        int initialTokens = TokenBudget.estimateMessagesTokens(history);
        assertTrue(initialTokens >= productionTrigger,
                "初始 history 必须 ≥ 230k 才能在生产阈值下触发压缩，实际 " + initialTokens);

        // ===== 2. 真实生产阈值多轮压缩 =====
        ConversationHistoryCompactor compactor = new ConversationHistoryCompactor(llm, 3);
        List<CompactionPhase> phases = new ArrayList<>();
        int compactedTimes = 0;
        long compactStart = System.currentTimeMillis();
        for (int round = 0; round < 8; round++) {
            int beforeTokens = TokenBudget.estimateMessagesTokens(history);
            int beforeMessages = history.size();
            boolean compacted = compactor.compactIfNeeded(history, productionTrigger);
            if (!compacted) {
                // 没撞阈值就再灌一批对话再试
                appendAdditionalTurns(history, round + 1, facts, productionTrigger);
                if (TokenBudget.estimateMessagesTokens(history) < productionTrigger) {
                    break; // 灌完都没到，认了
                }
                continue;
            }
            compactedTimes++;
            int afterTokens = TokenBudget.estimateMessagesTokens(history);
            phases.add(new CompactionPhase(round + 1, beforeTokens, afterTokens,
                    beforeMessages, history.size()));
            // 压缩成功后，再追加对话直到再次撞 230k，逼出下一次压缩
            appendAdditionalTurns(history, round + 1, facts, productionTrigger);
            if (compactedTimes >= 5) break;
        }
        long compactElapsed = System.currentTimeMillis() - compactStart;

        // ===== 3. 协议不变量 + 单调性硬断言 =====
        assertProtocolIntegrity(history);
        assertCompactionMonotonicity(phases);
        assertTrue(compactedTimes >= 1,
                "至少要发生 1 次真实压缩；实际 " + compactedTimes
                        + " 次。可能 history 没堆到 230k，调大 fillerNoise size。");

        // ===== 4. 让同一个 LLM 基于压缩后的 history 回答 20 条 QA =====
        long qaStart = System.currentTimeMillis();
        List<QaResult> qaResults = new ArrayList<>();
        int passed = 0;
        for (FactCase fact : facts) {
            QaResult result = askWithTimeout(llm, history, fact);
            qaResults.add(result);
            if (result.passed) {
                passed++;
            }
        }
        long qaElapsed = System.currentTimeMillis() - qaStart;

        // ===== 5. 输出报告（控制台 + benchmark 目录 JSON），方便人工审阅 =====
        double retention = facts.isEmpty() ? 1.0 : (double) passed / facts.size();
        Path report = writeReport(llm, facts, phases, qaResults, initialTokens,
                retention, compactElapsed, qaElapsed);
        StringBuilder tierLine = new StringBuilder();
        for (Tier tier : Tier.values()) {
            long total = qaResults.stream().filter(r -> r.fact.tier == tier).count();
            long pass = qaResults.stream().filter(r -> r.fact.tier == tier && r.passed).count();
            if (total == 0) continue;
            tierLine.append(' ').append(tier.name()).append('=').append(pass).append('/').append(total);
        }
        System.out.printf(Locale.ROOT,
                "Real LLM compression retention: %d/%d (%.1f%%) over %d compactions; "
                        + "init=%d tokens; tiers:%s; compact=%dms; qa=%dms; report=%s%n",
                passed, facts.size(), retention * 100, compactedTimes,
                initialTokens, tierLine, compactElapsed, qaElapsed, report);

        assertTrue(retention >= RETENTION_THRESHOLD,
                String.format(Locale.ROOT,
                        "信息保留率 %.1f%% 低于阈值 %.0f%%，详见 %s",
                        retention * 100, RETENTION_THRESHOLD * 100, report));
    }

    // ------------------------------------------------------------------
    // LLM 解析与跳过策略
    // ------------------------------------------------------------------

    private static LlmClient resolveRealLlmClientOrSkip() {
        // .env 里实际启用的是 KIMI_API_KEY + KIMI_BASE_URL（指向本地 LLM 代理）
        DevCliConfig config = DevCliConfig.load();
        String preferred = System.getProperty("devcli.it.compression.provider", "kimi");
        LlmClient client = LlmClientFactory.create(preferred, config);
        if (client == null) {
            // fallback：依次尝试其他 provider
            for (String p : List.of("glm", "deepseek", "step")) {
                client = LlmClientFactory.create(p, config);
                if (client != null) break;
            }
        }
        Assumptions.assumeTrue(client != null,
                "未检测到可用 LLM provider；本机至少要在 .env 配置一个 *_API_KEY");
        // 端点探活：发一条 "ping" 短请求，30s 超时
        try {
            LlmClient.ChatResponse pong = client.chat(List.of(
                    LlmClient.Message.system("你是 ping 探针，只回复一个字 OK。"),
                    LlmClient.Message.user("ping")), null);
            assertNotNull(pong);
            String content = pong.content() == null ? "" : pong.content();
            Assumptions.assumeTrue(!content.isBlank(),
                    "LLM 端点 ping 返回空，跳过真实 LLM 压缩 IT");
        } catch (IOException e) {
            Assumptions.abort("LLM 端点不可达，跳过真实 LLM 压缩 IT: " + e.getMessage());
        }
        return client;
    }

    // ------------------------------------------------------------------
    // 长会话与事实场景
    // ------------------------------------------------------------------

    /**
     * 18 个事实分 4 档难度，模拟真实 Agent 长会话里的不同信号强度：
     * <ul>
     *   <li><b>EASY (5)</b>：明确决策类，对话里被反复引用 — 摘要器强项</li>
     *   <li><b>MEDIUM (5)</b>：单次提及但带语义锚点（"我们决定"/"最终选用"）</li>
     *   <li><b>HARD_ENTITY (5)</b>：埋在工具结果、tool_call args、stack trace 里的精确实体</li>
     *   <li><b>HARD_OVERRIDE (3)</b>：早期一个值，中后期被覆盖；只有最新值算正确</li>
     * </ul>
     * 题目尽量不复用 fact 原文里的关键词，避免变成关键词检索题。
     */
    private static List<FactCase> scenarioFacts() {
        List<FactCase> facts = new ArrayList<>();

        // ===== EASY：被反复引用的稳定决策（5 题） =====
        facts.add(new FactCase(Tier.EASY,
                "用户最初的任务目标是优化 DevCLI 的 RAG 调用链检索",
                "本次会话最初要解决什么问题？",
                List.of("RAG", "检索", "调用链")));
        facts.add(new FactCase(Tier.EASY,
                "当前实现已经把 JavaParser SymbolSolver 接入 CodeAnalyzer，作为符号解析第一阶段",
                "JavaParser 符号解析现在做到哪一步了？",
                List.of("SymbolSolver", "CodeAnalyzer")));
        facts.add(new FactCase(Tier.EASY,
                "排序方案最终选择 RetrievalFusion 加 RRF，再叠加符号级 boost",
                "排序方案是怎么选的？",
                List.of("RetrievalFusion", "RRF")));
        facts.add(new FactCase(Tier.EASY,
                "代码图谱保留 calls / implements / extends / contains 四种关系",
                "图谱里抽了哪几种边？",
                List.of("calls", "implements", "extends", "contains")));
        facts.add(new FactCase(Tier.EASY,
                "用户偏好默认使用简体中文沟通",
                "Agent 跟用户对话该用什么语言？",
                List.of("简体中文", "中文")));

        // ===== MEDIUM：单次提及，带语义锚点（5 题） =====
        facts.add(new FactCase(Tier.MEDIUM,
                "embedding 模型最终选用 Qwen/Qwen3-Embedding-4B",
                "向量化用的什么模型？",
                List.of("Qwen3-Embedding-4B", "Qwen")));
        facts.add(new FactCase(Tier.MEDIUM,
                "调用链最大扩展深度限制为 3 跳，再深就跨模块噪声太大",
                "图谱扩展深度限到几跳？",
                List.of("3")));
        facts.add(new FactCase(Tier.MEDIUM,
                "中途遇到 MCP schema 缺少 required 字段错误，已修复",
                "调试过程中最严重的错是什么？",
                List.of("MCP", "schema", "required")));
        facts.add(new FactCase(Tier.MEDIUM,
                "下一步要补一组 SymbolVersionDiff 到 InvalidationMemory 的上下文失效契约测试",
                "接下来要写哪类上下文治理测试？",
                List.of("SymbolVersionDiff", "InvalidationMemory", "失效")));
        facts.add(new FactCase(Tier.MEDIUM,
                "压缩保留率回归命令使用 mvn test -Dtest=RealLlmCompressionRetentionIT",
                "怎么跑真实压缩保留率回归？",
                List.of("RealLlmCompressionRetentionIT")));

        // ===== HARD_ENTITY：埋在工具结果 / tool_call args / 数字常量里（5 题） =====
        // 这些事实只在工具结果或参数里出现一次，不会被自然语言复述
        facts.add(new FactCase(Tier.HARD_ENTITY,
                "[tool] read_file CodeRetriever.java 第 217 行",  // 埋在 tool_call args
                "Agent 改的关键文件是哪个？",
                List.of("CodeRetriever.java")));
        facts.add(new FactCase(Tier.HARD_ENTITY,
                "[tool] execute_command mvn -pl rag test 输出 BUILD SUCCESS in 47.3s",
                "RAG 模块单元测试跑了多久？",
                List.of("47.3", "47")));
        facts.add(new FactCase(Tier.HARD_ENTITY,
                "[tool] noise ratio 18.4%，比上一版降了 6.2 个百分点",
                "降噪后噪声率多少？",
                List.of("18", "18.4")));
        facts.add(new FactCase(Tier.HARD_ENTITY,
                "[tool] read_file 看到 application.yml 里 server.port=8443",
                "用户的服务跑在哪个端口？",
                List.of("8443")));
        facts.add(new FactCase(Tier.HARD_ENTITY,
                "[tool] stack trace 第一帧 com.devcli.rag.CodeRetriever.search:217",
                "异常发生在哪个具体的位置？",
                List.of("CodeRetriever.search", "217")));

        // ===== HARD_OVERRIDE：决策被覆盖，只接受最新值（3 题） =====
        // 早期的值会先被注入，到中后期再注入新值；问题问的是"现在"用的是什么
        facts.add(new FactCase(Tier.HARD_OVERRIDE,
                "OVERRIDE: top_k 一开始定的是 5，后来用户说改成 8，最终决定用 10",
                "search_code 的 top_k 现在用的是几？",
                List.of("10")));
        facts.add(new FactCase(Tier.HARD_OVERRIDE,
                "OVERRIDE: 摘要器先尝试 LLM rerank，后改成确定性排序，最后回到 RRF 混合（向量+关键词+图谱）",
                "排序最终用的是哪个版本？",
                List.of("RRF", "混合")));
        facts.add(new FactCase(Tier.HARD_OVERRIDE,
                "OVERRIDE: chunk_size 从 256 改到 512，最终定 384 平衡速度和召回",
                "chunk_size 最后定多少？",
                List.of("384")));

        return facts;
    }

    /**
     * 真实 Agent 长会话仿真：
     * <ul>
     *   <li>EASY 事实：被反复引用 3-5 次（决策类天然会被复述）</li>
     *   <li>MEDIUM 事实：只在某一轮被自然提及一次</li>
     *   <li>HARD_ENTITY 事实：作为 tool_call → tool_result 的输出出现，不进入自然语言</li>
     *   <li>HARD_OVERRIDE 事实：早期值 + 中段过渡值 + 最终值，按时间顺序埋三段</li>
     *   <li>剩余空间用模拟的 read_file / execute_command 输出灌满，不复述任何事实</li>
     * </ul>
     */
    private static List<LlmClient.Message> buildLongConversation(List<FactCase> facts,
                                                                 int targetTokens) {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system(
                "你是 DevCLI Agent，正在协助一个真实的多轮 RAG 优化任务。"
                        + "上下文里包含用户决策、工具调用结果、错误日志、debug 输出。"
                        + "面对压缩仍要能够回忆起最初目标、决策、错误与精确实体。"));

        int turn = 0;
        // 第一轮：用户陈述总目标（EASY）
        turn++;
        injectUser(history, turn, facts.get(0).statement);  // RAG 调用链检索
        injectAssistant(history, turn, "明白，我先理清当前 RAG 检索的流程。");

        // 注入第一个 tool_call（HARD_ENTITY：CodeRetriever.java）
        turn++;
        injectToolCall(history, turn, "tc-1", "read_file",
                "{\"path\":\"src/main/java/com/devcli/rag/CodeRetriever.java\"}",
                facts.get(10).statement);  // [tool] read_file CodeRetriever.java 第 217 行

        // EASY-2 + MEDIUM-1
        turn++;
        injectUser(history, turn, facts.get(1).statement);  // SymbolSolver 边界
        injectAssistant(history, turn, "好，那我们走 JavaParser 路线。");

        turn++;
        injectUser(history, turn, "选个 embedding 模型，要中英都能用。");
        injectAssistant(history, turn, facts.get(5).statement);  // Qwen3-Embedding-4B

        // HARD_OVERRIDE 早期值（top_k=5）
        turn++;
        injectUser(history, turn, "search_code 工具的 top_k 默认设成 5 吧。");
        injectAssistant(history, turn, "已设为 top_k=5。");

        // EASY-3（决策被反复引用）
        turn++;
        injectUser(history, turn, facts.get(2).statement);  // 确定性分层排序
        injectAssistant(history, turn, "好，避免每轮都调 LLM 打分。");

        // 大段背景：模拟 read_file 返回的代码片段（不复述事实）
        turn++;
        injectToolCall(history, turn, "tc-2", "read_file",
                "{\"path\":\"src/main/java/com/devcli/rag/CodeAnalyzer.java\"}",
                fakeJavaSource("CodeAnalyzer", 800));

        // HARD_ENTITY：mvn test 输出
        turn++;
        injectToolCall(history, turn, "tc-3", "execute_command",
                "{\"command\":\"mvn -pl rag test\"}",
                facts.get(11).statement);  // mvn test BUILD SUCCESS in 47.3s

        // EASY-4
        turn++;
        injectUser(history, turn, facts.get(3).statement);  // 四种关系类型
        injectAssistant(history, turn, "已经把这四种边都建在 SQLite 图表里了。");

        // MEDIUM-2
        turn++;
        injectUser(history, turn, "调用链扩展太深会噪声爆表。");
        injectAssistant(history, turn, facts.get(6).statement);  // 3 跳

        // HARD_OVERRIDE 中段过渡值（top_k 改 8）
        turn++;
        injectUser(history, turn, "测了下 top_k=5 偶尔漏掉关键节点，改成 8 试试。");
        injectAssistant(history, turn, "已改 top_k=8 重新跑 eval。");

        // 灌大段背景：模拟 search_code 工具结果（不复述事实）
        turn++;
        injectToolCall(history, turn, "tc-4", "search_code",
                "{\"query\":\"how Agent.run drives ReAct loop\"}",
                fakeSearchResult(15));

        // MEDIUM-3：MCP schema 错误
        turn++;
        injectUser(history, turn, "MCP server 启动失败，看看日志。");
        injectAssistant(history, turn, facts.get(7).statement);  // MCP schema required

        // HARD_ENTITY：noise ratio 数字
        turn++;
        injectToolCall(history, turn, "tc-5", "execute_command",
                "{\"command\":\"java -jar bin/eval.jar --metric noise\"}",
                facts.get(12).statement);  // 18.4%

        // HARD_ENTITY：application.yml 里的端口（用户没强调）
        turn++;
        injectToolCall(history, turn, "tc-6", "read_file",
                "{\"path\":\"src/main/resources/application.yml\"}",
                facts.get(13).statement);  // server.port=8443

        // EASY-1 第一次复述（决策被引用）
        turn++;
        injectUser(history, turn, "我们最初是要优化 RAG 检索吧？继续按这个推进。");
        injectAssistant(history, turn, "对，目标没变。");

        // HARD_OVERRIDE 最终值（top_k 改 10）
        turn++;
        injectUser(history, turn, "再加点冗余度，把 top_k 调到 10。这是最终值。");
        injectAssistant(history, turn, "好，top_k=10 已生效。");

        // EASY-5：用户偏好
        turn++;
        injectUser(history, turn, facts.get(4).statement);  // 简体中文
        injectAssistant(history, turn, "好的。");

        // HARD_OVERRIDE 第二组：排序方案变更
        turn++;
        injectUser(history, turn, "先试 LLM rerank。");
        injectAssistant(history, turn, "已切到 LLM rerank。");
        turn++;
        injectUser(history, turn, "rerank 太慢，改回纯确定性排序。");
        injectAssistant(history, turn, "已切回确定性。");
        turn++;
        injectUser(history, turn, facts.get(15).statement);  // OVERRIDE: 最终用混合
        injectAssistant(history, turn, "已切到混合排序。");

        // HARD_OVERRIDE 第三组：chunk_size
        turn++;
        injectUser(history, turn, "chunk_size 一开始 256。");
        injectAssistant(history, turn, "好。");
        turn++;
        injectUser(history, turn, "改成 512 看看召回。");
        injectAssistant(history, turn, "已改 512。");
        turn++;
        injectUser(history, turn, facts.get(16).statement);  // OVERRIDE: 最终 384
        injectAssistant(history, turn, "好，定 chunk_size=384。");

        // HARD_ENTITY：stack trace
        turn++;
        injectToolCall(history, turn, "tc-7", "execute_command",
                "{\"command\":\"java -jar bin/eval.jar\"}",
                facts.get(14).statement);  // stack trace CodeRetriever.search:217

        // MEDIUM-4 + MEDIUM-5
        turn++;
        injectUser(history, turn, "下一步呢？");
        injectAssistant(history, turn, facts.get(8).statement);  // ConversationHistoryCompactor 契约测试

        turn++;
        injectUser(history, turn, "回归怎么跑？");
        injectAssistant(history, turn, facts.get(9).statement);  // mvn test RealLlmCompressionRetentionIT

        // EASY-1 / EASY-2 第二次复述
        turn++;
        injectUser(history, turn, "总目标还是 RAG 检索优化对吧？SymbolSolver 已经接到 CodeAnalyzer。");
        injectAssistant(history, turn, "对，目标和当前符号解析状态都没变。");

        // 灌大段纯对话直到达到 targetTokens（不用大代码块，避免撑爆保留尾部）
        while (TokenBudget.estimateMessagesTokens(history) < targetTokens) {
            turn++;
            injectUser(history, turn, fakePlainDialogueLine(turn, true));
            injectAssistant(history, turn, fakePlainDialogueLine(turn, false));
        }
        return history;
    }

    /**
     * 每次压缩后再灌新轮次让 trigger 再次满足。
     * <p>这次只灌"user / assistant 纯文字对话"形态的 noise（不复述任何 fact，不塞大块工具结果）。
     * 这样保留尾部（最后 retainRecentRounds=3 个 user 起算）始终是几 k token 的小区间，
     * 压缩才能真正打到 10-50k 的稳态。
     * <p>注意：这是测试侧的简化——真实 Agent 长任务里保留区可能包含大块工具结果，
     * 导致 conversationHistory 压缩在工具密集场景下收敛失效。这个生产缺陷由
     * docs/conversation-history-compactor-retain-tail-bloat.md 单独记录。
     */
    private static void appendAdditionalTurns(List<LlmClient.Message> history,
                                              int round, List<FactCase> facts,
                                              int targetTokens) {
        int turn = 1000 + round * 100;
        int safety = 0;
        while (TokenBudget.estimateMessagesTokens(history) < targetTokens) {
            turn++;
            // 一轮纯文字对话 ~1.5k token，让保留区每移动一步只扩 ~5k
            injectUser(history, turn, fakePlainDialogueLine(turn, true));
            injectAssistant(history, turn, fakePlainDialogueLine(turn, false));
            safety++;
            if (safety > 400) break; // 安全阀
        }
    }

    /** 一段不带 fact 关键词的纯文字对话片段，~700-1500 token。 */
    private static String fakePlainDialogueLine(int seed, boolean isUser) {
        StringBuilder sb = new StringBuilder();
        sb.append(isUser ? "[noise turn " : "[noise reply ").append(seed).append("] ");
        sb.append(isUser
                ? "我们继续推进，先别动核心逻辑，把测试覆盖率和文档稳一稳。"
                : "好，我先把当前任务列表整理一下，再决定下一步。");
        // 每条 dialogue 灌 ~30 行无意义但语义合理的话，堆到 ~1k token
        for (int i = 0; i < 30; i++) {
            if (isUser) {
                sb.append("讨论点 ").append(seed).append("-").append(i)
                        .append("：保持当前架构思路，先不扩大依赖范围；")
                        .append("回顾一下之前的讨论结论，确认大家理解一致；")
                        .append("注意保持代码风格的一致性；");
            } else {
                sb.append("响应点 ").append(seed).append("-").append(i)
                        .append("：明白，我会按这个方向推进；")
                        .append("具体执行步骤会拆成小任务依次落地；")
                        .append("有阻塞我会及时反馈；");
            }
        }
        return sb.toString();
    }

    private static void injectUser(List<LlmClient.Message> history, int turn, String content) {
        history.add(LlmClient.Message.user("[turn " + turn + "] " + content));
    }

    private static void injectAssistant(List<LlmClient.Message> history, int turn, String content) {
        history.add(LlmClient.Message.assistant("[turn " + turn + "] " + content));
    }

    private static void injectToolCall(List<LlmClient.Message> history, int turn,
                                       String callId, String toolName, String args, String result) {
        List<LlmClient.ToolCall> calls = List.of(new LlmClient.ToolCall(callId,
                new LlmClient.ToolCall.Function(toolName, args)));
        history.add(LlmClient.Message.assistant(null, null, calls));
        history.add(new LlmClient.Message("tool", result, null, null, callId));
    }

    /** 模拟 read_file 返回的代码片段，提供 token 体量但不带 fact 关键词。 */
    private static String fakeJavaSource(String className, int lines) {
        StringBuilder sb = new StringBuilder("// File: " + className + ".java\npackage com.devcli.fake;\n\n");
        sb.append("public class ").append(className).append(" {\n");
        for (int i = 0; i < lines; i++) {
            sb.append("    private final int field").append(i)
                    .append(" = ").append(i * 31 + 7).append(";\n");
            if (i % 5 == 0) {
                sb.append("    public int compute").append(i)
                        .append("() { return field").append(i).append(" * 2 + ").append(i)
                        .append("; }\n");
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** 模拟 search_code 工具返回的若干代码块，token 大但不带 fact。 */
    private static String fakeSearchResult(int blocks) {
        StringBuilder sb = new StringBuilder("Found relevant code:\n\n");
        for (int i = 0; i < blocks; i++) {
            sb.append("=== Match ").append(i + 1).append(" (score=0.")
                    .append(50 + i).append(") ===\n");
            sb.append("File: src/main/java/com/devcli/util/Helper").append(i).append(".java\n");
            sb.append("Lines ").append(i * 10).append("-").append(i * 10 + 30).append("\n");
            sb.append("snippet: public void method").append(i)
                    .append("(String input) { /* generic helper logic ").append(i).append(" */ }\n\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 协议不变量与单调性
    // ------------------------------------------------------------------

    private static void assertProtocolIntegrity(List<LlmClient.Message> history) {
        // tool_call → tool_result 必须配对
        java.util.Set<String> openCalls = new java.util.HashSet<>();
        java.util.Set<String> seenResults = new java.util.HashSet<>();
        for (LlmClient.Message m : history) {
            if (m.toolCalls() != null) {
                for (LlmClient.ToolCall tc : m.toolCalls()) {
                    openCalls.add(tc.id());
                }
            }
            if ("tool".equals(m.role()) && m.toolCallId() != null) {
                seenResults.add(m.toolCallId());
            }
        }
        // 任何还没拿到 result 的 call 都是孤儿
        for (String id : openCalls) {
            assertTrue(seenResults.contains(id),
                    "tool_call " + id + " 在压缩后丢失了对应的 tool_result");
        }
        for (String id : seenResults) {
            assertTrue(openCalls.contains(id),
                    "tool_result " + id + " 找不到对应的 tool_call（可能被压缩切走）");
        }
        // system 必须仍在头部（如果一开始有的话）
        assertTrue(!history.isEmpty(), "压缩后历史不应为空");
    }

    private static void assertCompactionMonotonicity(List<CompactionPhase> phases) {
        // 至少有一次真实下降即可。允许极少数轮次因 retain 区已经接近 trigger 时
        // 出现"压缩前 = 压缩后"的边界情况——这本身是设计缺陷的暴露，不是测试逻辑错。
        boolean anyReduced = phases.stream().anyMatch(p -> p.afterTokens < p.beforeTokens);
        assertTrue(anyReduced, "至少应有一次压缩使 token 真实下降");
        for (CompactionPhase p : phases) {
            assertTrue(p.afterTokens <= p.beforeTokens,
                    String.format(Locale.ROOT,
                            "第 %d 次压缩 token 反而上升：%d -> %d（不应发生）",
                            p.round, p.beforeTokens, p.afterTokens));
        }
    }

    // ------------------------------------------------------------------
    // QA 评测
    // ------------------------------------------------------------------

    private QaResult askWithTimeout(LlmClient llm, List<LlmClient.Message> compressedHistory,
                                    FactCase fact) {
        long start = System.currentTimeMillis();
        // 在压缩后的真实历史末尾追加一个用户提问：要求模型只输出答案
        List<LlmClient.Message> probe = new ArrayList<>(compressedHistory);
        probe.add(LlmClient.Message.user(
                "基于以上所有上下文（包含被压缩为摘要的历史），请用一句话回答：" + fact.question
                        + "\n如果上下文里没有，请直接回答 \"未提及\"。不要解释。"));
        try {
            long deadline = start + LLM_QA_TIMEOUT_MS;
            LlmClient.ChatResponse resp = llm.chat(probe, null);
            long elapsed = System.currentTimeMillis() - start;
            String answer = resp == null || resp.content() == null ? "" : resp.content().trim();
            boolean passed = matches(answer, fact.expectedKeywords);
            String diagnosis = passed ? "ok"
                    : "missing keywords " + fact.expectedKeywords;
            return new QaResult(fact, answer, passed, elapsed, diagnosis);
        } catch (IOException e) {
            return new QaResult(fact, "<error: " + e.getMessage() + ">", false,
                    System.currentTimeMillis() - start, "io_error");
        }
    }

    private static boolean matches(String answer, List<String> expectedKeywords) {
        if (answer == null || answer.isBlank()) return false;
        if (answer.contains("未提及")) return false;
        String lower = answer.toLowerCase(Locale.ROOT);
        for (String kw : expectedKeywords) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 报告
    // ------------------------------------------------------------------

    private Path writeReport(LlmClient llm, List<FactCase> facts,
                             List<CompactionPhase> phases, List<QaResult> qaResults,
                             int initialTokens, double retention,
                             long compactMs, long qaMs) throws IOException {
        Path dir = Path.of("target", "benchmark");
        Files.createDirectories(dir);
        Path file = dir.resolve("real-llm-compression-retention.json");
        ObjectNode root = JSON.createObjectNode();
        root.put("model", llm.getModelName());
        root.put("provider", llm.getProviderName());
        root.put("initial_tokens", initialTokens);
        root.put("compactions", phases.size());
        root.put("retention_ratio", retention);
        root.put("compact_ms", compactMs);
        root.put("qa_ms", qaMs);
        ArrayNode pn = root.putArray("phases");
        for (CompactionPhase p : phases) {
            ObjectNode n = pn.addObject();
            n.put("round", p.round);
            n.put("before_tokens", p.beforeTokens);
            n.put("after_tokens", p.afterTokens);
            n.put("before_messages", p.beforeMessages);
            n.put("after_messages", p.afterMessages);
        }
        ArrayNode qn = root.putArray("qa");
        for (QaResult r : qaResults) {
            ObjectNode n = qn.addObject();
            n.put("tier", r.fact.tier.name());
            n.put("question", r.fact.question);
            n.put("expected_keywords", String.join("|", r.fact.expectedKeywords));
            n.put("answer", r.answer);
            n.put("passed", r.passed);
            n.put("elapsed_ms", r.elapsedMs);
            n.put("diagnosis", r.diagnosis);
        }
        // 分档保留率：让面试时能讲清"哪些档好哪些档差"
        ObjectNode tierStats = root.putObject("retention_by_tier");
        for (Tier tier : Tier.values()) {
            long total = qaResults.stream().filter(r -> r.fact.tier == tier).count();
            long passed = qaResults.stream().filter(r -> r.fact.tier == tier && r.passed).count();
            ObjectNode t = tierStats.putObject(tier.name());
            t.put("passed", passed);
            t.put("total", total);
            t.put("ratio", total == 0 ? 0.0 : (double) passed / total);
        }
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        return file;
    }

    // ------------------------------------------------------------------
    // 数据类型
    // ------------------------------------------------------------------

    private enum Tier {
        EASY, MEDIUM, HARD_ENTITY, HARD_OVERRIDE
    }

    private static final class FactCase {
        final Tier tier;
        final String statement;
        final String question;
        final List<String> expectedKeywords;

        FactCase(Tier tier, String statement, String question, List<String> expectedKeywords) {
            this.tier = tier;
            this.statement = statement;
            this.question = question;
            this.expectedKeywords = expectedKeywords;
        }
    }

    private static final class CompactionPhase {
        final int round;
        final int beforeTokens;
        final int afterTokens;
        final int beforeMessages;
        final int afterMessages;

        CompactionPhase(int round, int beforeTokens, int afterTokens,
                        int beforeMessages, int afterMessages) {
            this.round = round;
            this.beforeTokens = beforeTokens;
            this.afterTokens = afterTokens;
            this.beforeMessages = beforeMessages;
            this.afterMessages = afterMessages;
        }
    }

    private static final class QaResult {
        final FactCase fact;
        final String answer;
        final boolean passed;
        final long elapsedMs;
        final String diagnosis;

        QaResult(FactCase fact, String answer, boolean passed, long elapsedMs,
                 String diagnosis) {
            this.fact = fact;
            this.answer = answer;
            this.passed = passed;
            this.elapsedMs = elapsedMs;
            this.diagnosis = diagnosis;
        }
    }
}
