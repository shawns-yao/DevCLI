package com.devcli.hitl;

import com.devcli.config.ConfigResolver;
import com.devcli.llm.LlmClient;
import com.devcli.prompt.PromptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 用模型实现的权限分类器。
 *
 * <p>调用是同步的：判定必须在动作执行前完成，没有异步的余地。模型客户端自身的读超时是分钟级
 * （见 {@code AbstractOpenAiCompatibleClient}），对审批路径来说太长，因此这里用独立的 daemon
 * 线程池 + {@link Future#get} 施加自己的超时。超时后底层 HTTP 调用可能仍在跑，但本方法立即
 * 失败关闭，不再等它。</p>
 *
 * <p>任何异常都不向上抛「未决」，一律转成 {@link IOException}：调用方据此按拒绝处理。
 * 分类器只有「放行」与「拒绝」两种输出，没有「判断不出来，还是问一下用户」。</p>
 */
public class LlmPermissionClassifier implements PermissionClassifier, AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 分类器工作线程数。
     *
     * <p>它只在 {@code auto} 模式下、规则层未决时被调用，正常频率很低；固定 2 个同时起到限流作用——
     * 若模型侧持续卡住，后续调用会在队列里耗尽自己的超时并按拒绝处理，而不是无限堆积线程。</p>
     */
    private static final int WORKER_COUNT = 2;

    private final Supplier<LlmClient> client;
    private final String systemPrompt;
    private final long timeoutMillis;
    private final ExecutorService workers;

    /**
     * @param client 模型客户端来源。用 {@link Supplier} 而不是固定引用，是因为会话中可以切换模型；
     *               持有启动时那一个会让分类器在切换后继续用旧客户端判定。
     */
    public LlmPermissionClassifier(Supplier<LlmClient> client, String systemPrompt, long timeoutMillis) {
        this.client = Objects.requireNonNull(client, "client");
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt");
        this.timeoutMillis = timeoutMillis;
        this.workers = Executors.newFixedThreadPool(WORKER_COUNT, daemonWorkers());
    }

    /**
     * 按项目约定装配：提示词走 {@link PromptRepository}（只支持可信的用户级覆盖，忽略项目级覆盖），
     * 超时走 {@link ConfigResolver}。
     */
    public static LlmPermissionClassifier createDefault(Supplier<LlmClient> client) {
        long seconds = ConfigResolver.longValue(
                "devcli.permission.classifier.timeout.seconds",
                "DEVCLI_PERMISSION_CLASSIFIER_TIMEOUT_SECONDS", 10L, 1L, 120L);
        return new LlmPermissionClassifier(client,
                PromptRepository.createDefault().loadRequired("permission-classifier.md"),
                seconds * 1000L);
    }

    @Override
    public Verdict classify(Request request) throws IOException {
        LlmClient active = client.get();
        if (active == null) {
            throw new IOException("当前没有可用的模型客户端，按拒绝处理");
        }
        List<LlmClient.Message> messages = List.of(
                LlmClient.Message.system(systemPrompt),
                LlmClient.Message.user(describe(request)));
        Future<LlmClient.ChatResponse> pending = workers.submit(() -> active.chat(messages, List.of()));

        LlmClient.ChatResponse response;
        try {
            response = pending.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            pending.cancel(true);
            throw new IOException("权限分类器超时（" + timeoutMillis + "ms），按拒绝处理");
        } catch (InterruptedException interrupted) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("权限分类器调用被中断，按拒绝处理");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new IOException("权限分类器调用失败：" + cause.getMessage(), cause);
        }

        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new IOException("权限分类器返回空响应，按拒绝处理");
        }
        return parseVerdict(response.content());
    }

    @Override
    public long decisionTimeoutMillis() {
        return timeoutMillis;
    }

    /** 交给模型的最小上下文。刻意不含会话历史、工具输出与已授权资源清单。 */
    private static String describe(Request request) {
        return "工具：" + request.toolName() + "\n"
                + "参数：" + request.argumentsJson() + "\n"
                + "项目路径：" + request.projectPath() + "\n"
                + "当前权限模式：" + request.mode();
    }

    /**
     * 解析模型响应。
     *
     * <p>提示词要求只输出 JSON，但模型常把它包在代码围栏或一句说明里，因此取首个 {@code &#123;}
     * 到末个 {@code &#125;} 之间的内容，而不是直接解析整段。缺 {@code allow} 字段一律视为不可解析——
     * 猜一个默认值就等于让解析失败变成放行。</p>
     */
    static Verdict parseVerdict(String raw) throws IOException {
        if (raw == null) {
            throw new IOException("权限分类器返回空响应，按拒绝处理");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("权限分类器响应中没有 JSON 对象，按拒绝处理");
        }
        JsonNode node;
        try {
            node = JSON.readTree(raw.substring(start, end + 1));
        } catch (IOException failure) {
            throw new IOException("权限分类器响应无法解析，按拒绝处理", failure);
        }
        JsonNode allow = node.get("allow");
        if (allow == null || !allow.isBoolean()) {
            throw new IOException("权限分类器响应缺少布尔字段 allow，按拒绝处理");
        }
        return new Verdict(allow.asBoolean(), node.path("reason").asText(""));
    }

    private static ThreadFactory daemonWorkers() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "permission-classifier-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
