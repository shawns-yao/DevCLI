package com.devcli.memory;

import com.devcli.context.ContextProfile;
import com.devcli.llm.LlmClient;
import com.devcli.memory.MemoryManager.SessionPreSummaryMaintenanceResult;
import com.devcli.memory.MemoryManager.SessionPreSummaryMetrics;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.devcli.memory.MemoryManager.SESSION_PRE_SUMMARY_ENABLED_PROPERTY;

/** 预摘要缓存的生成、排队、失效和关闭；不拥有工作计划或长期记忆。 */
final class SessionPreSummaryScheduler implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SessionPreSummaryScheduler.class);
    private final CompactionSummaryCache compactionSummaryCache;
    private final Supplier<TokenBudget> tokenBudget;
    private final Supplier<ContextProfile> contextProfile;
    private static final int SESSION_PRE_SUMMARY_TOKEN_DELTA = 2_000;

    private static final int SESSION_PRE_SUMMARY_TOOL_CALLS = 4;

    private static final int SESSION_PRE_SUMMARY_LARGE_TOOL_CHARS = 12_000;

    private static final long SESSION_PRE_SUMMARY_FAILURE_COOLDOWN_MILLIS = 30_000L;

    private final ConversationHistoryCompactor sessionPreSummaryCompactor;

    private final ExecutorService sessionPreSummaryExecutor;

    /** 后台预摘要最多保留一个运行中任务和一个最新待处理任务。 */
    private final Object sessionPreSummaryScheduleLock = new Object();

    private SessionPreSummaryTask runningSessionPreSummary;

    private SessionPreSummaryTask pendingSessionPreSummary;

    private boolean sessionPreSummaryClosed;

    private long sessionPreSummaryRequestVersion;

    private long sessionPreSummaryCommittedVersion;

    private long sessionPreSummaryInvalidatedVersion;

    private final AtomicLong preSummaryFullCount = new AtomicLong();

    private final AtomicLong preSummaryIncrementalCount = new AtomicLong();

    private final AtomicLong preSummaryFailureCount = new AtomicLong();

    private volatile SessionPreSummaryMetrics lastPreSummaryMetrics = SessionPreSummaryMetrics.empty();

    private volatile String preSummaryFailureFingerprint = "";

    private volatile long preSummaryFailureUntilMillis;

    private LlmClient llmClient;

    SessionPreSummaryScheduler(LlmClient client, CompactionSummaryCache cache,
                              Supplier<TokenBudget> budget, Supplier<ContextProfile> profile) {
        llmClient = client;
        compactionSummaryCache = cache;
        tokenBudget = budget;
        contextProfile = profile;
        sessionPreSummaryCompactor = new ConversationHistoryCompactor(client);
        sessionPreSummaryCompactor.setSummaryUsageConsumer(this::recordPreSummaryUsage);
        sessionPreSummaryExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "devcli-session-pre-summary");
            t.setDaemon(true);
            return t;
        });
    }

    void setSummaryToolsSupplier(Supplier<List<LlmClient.Tool>> supplier) {
        sessionPreSummaryCompactor.setSummaryToolsSupplier(supplier);
    }

    SessionPreSummaryMetrics metrics() {
        return lastPreSummaryMetrics;
    }

    void clear() {
        synchronized (sessionPreSummaryScheduleLock) {
            sessionPreSummaryInvalidatedVersion = ++sessionPreSummaryRequestVersion;
            compactionSummaryCache.clearPreSummary();
            clearPreSummaryFailure();
        }
    }

    void setLlmClient(LlmClient llmClient, Runnable applyProfile) {
        synchronized (sessionPreSummaryScheduleLock) {
            sessionPreSummaryInvalidatedVersion = ++sessionPreSummaryRequestVersion;
            this.llmClient = llmClient;
            this.compactionSummaryCache.clearPreSummary();
            clearPreSummaryFailure();
            this.sessionPreSummaryCompactor.setLlmClient(llmClient);
            applyProfile.run();
        }
    }

    /**
     * turn 结束后的会话预摘要维护入口。
     *
     * <p>该入口只维护当前进程内 SessionMemory，不写长期记忆。触发条件保持保守：
     * token 增量、工具调用次数或单个大工具结果达到阈值时才调用 LLM 生成预摘要。
     */
    SessionPreSummaryMaintenanceResult maintainSessionPreSummaryAfterTurn(
            List<LlmClient.Message> history,
            int turnToolCalls,
            int largestToolResultChars) {
        long requestVersion;
        synchronized (sessionPreSummaryScheduleLock) {
            if (sessionPreSummaryClosed) {
                return SessionPreSummaryMaintenanceResult.SKIPPED_DISABLED;
            }
            requestVersion = ++sessionPreSummaryRequestVersion;
        }
        return maintainSessionPreSummaryForCoveredHistory(
                history, turnToolCalls, largestToolResultChars, requestVersion);
    }

    private SessionPreSummaryMaintenanceResult maintainSessionPreSummaryForCoveredHistory(
            List<LlmClient.Message> history,
            int turnToolCalls,
            int largestToolResultChars,
            long requestVersion) {
        if (!isValidPreSummaryResult(requestVersion)) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_SUPERSEDED;
        }
        if (!Boolean.parseBoolean(System.getProperty(SESSION_PRE_SUMMARY_ENABLED_PROPERTY, "true"))) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_DISABLED;
        }
        if (!com.devcli.memory.CompactionConfiguration.isCompactionEnabled(System.getProperties(), System.getenv())) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_DISABLED;
        }
        if (llmClient == null || history == null || history.isEmpty()) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_EMPTY_HISTORY;
        }
        int systemEnd = "system".equals(history.get(0).role()) ? 1 : 0;
        if (history.size() <= systemEnd) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_EMPTY_HISTORY;
        }
        List<LlmClient.Message> coveredMessages = new ArrayList<>(history.subList(systemEnd, history.size()));
        String coveredFingerprint = CompactionSummaryCache.fingerprintOf(coveredMessages);
        if (isPreSummaryFailureCoolingDown(coveredFingerprint)) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_FAILURE_COOLDOWN;
        }
        if (compactionSummaryCache.findReusablePreSummary(coveredMessages, llmClient).isPresent()) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_ALREADY_CURRENT;
        }
        int tokenEstimate = TokenBudget.estimateMessagesTokens(coveredMessages);
        int previousTokenEstimate = compactionSummaryCache.currentPreSummary()
                .map(CompactionSummaryCache.PreSummary::tokenEstimate)
                .orElse(0);
        int tokenDelta = tokenEstimate - previousTokenEstimate;
        boolean triggered = tokenDelta >= SESSION_PRE_SUMMARY_TOKEN_DELTA
                || turnToolCalls >= SESSION_PRE_SUMMARY_TOOL_CALLS
                || largestToolResultChars >= SESSION_PRE_SUMMARY_LARGE_TOOL_CHARS;
        if (!triggered) {
            return SessionPreSummaryMaintenanceResult.SKIPPED_BELOW_THRESHOLD;
        }
        try {
            Optional<CompactionSummaryCache.PreSummary> incrementalBase =
                    compactionSummaryCache.findExtendablePreSummary(coveredMessages, llmClient);
            String maintenanceMode;
            int deltaMessageCount;
            int inputTokenEstimate;
            String summary;
            if (incrementalBase.isPresent()) {
                CompactionSummaryCache.PreSummary base = incrementalBase.get();
                List<LlmClient.Message> deltaMessages =
                        coveredMessages.subList(base.messageCount(), coveredMessages.size());
                maintenanceMode = "incremental";
                deltaMessageCount = deltaMessages.size();
                inputTokenEstimate = TokenBudget.estimateMessagesTokens(deltaMessages)
                        + MemoryEntry.estimateTokens(base.summary());
                if (preSummaryInputExceedsBudget(inputTokenEstimate)) {
                    recordPreSummaryFailure(requestVersion, coveredFingerprint, true);
                    return SessionPreSummaryMaintenanceResult.SKIPPED_INPUT_TOO_LARGE;
                }
                summary = sessionPreSummaryCompactor.summarizeIncremental(
                        base.summary(), deltaMessages);
            } else {
                maintenanceMode = "full";
                deltaMessageCount = coveredMessages.size();
                inputTokenEstimate = TokenBudget.estimateMessagesTokens(coveredMessages);
                if (preSummaryInputExceedsBudget(inputTokenEstimate)) {
                    recordPreSummaryFailure(requestVersion, coveredFingerprint, true);
                    return SessionPreSummaryMaintenanceResult.SKIPPED_INPUT_TOO_LARGE;
                }
                summary = sessionPreSummaryCompactor.summarizePrefix(history);
            }
            if (summary == null || summary.isBlank()) {
                recordPreSummaryFailure(requestVersion, coveredFingerprint, true);
                return SessionPreSummaryMaintenanceResult.FAILED;
            }
            RollingSummary structured = RollingSummary.parse(summary);
            if (structured.isEmpty()) {
                recordPreSummaryFailure(requestVersion, coveredFingerprint, true);
                return SessionPreSummaryMaintenanceResult.FAILED;
            }
            summary = structured.render();
            CompactionSemanticGuard.Validation validation = CompactionSemanticGuard.validateAndRepair(
                    coveredMessages, summary, ConversationHistoryCompactor.MAX_SUMMARY_CHARS);
            summary = validation.repairedSummary();
            structured = RollingSummary.parse(summary);
            if (structured.isEmpty()) {
                recordPreSummaryFailure(requestVersion, coveredFingerprint, true);
                return SessionPreSummaryMaintenanceResult.FAILED;
            }
            summary = structured.render();
            if (summary.length() > ConversationHistoryCompactor.MAX_SUMMARY_CHARS) {
                recordPreSummaryFailure(requestVersion, coveredFingerprint, true);
                return SessionPreSummaryMaintenanceResult.FAILED;
            }
            synchronized (sessionPreSummaryScheduleLock) {
                if (!isValidPreSummaryResult(requestVersion)) {
                    return SessionPreSummaryMaintenanceResult.SKIPPED_SUPERSEDED;
                }
                compactionSummaryCache.recordPreSummary(coveredMessages, summary, llmClient);
                sessionPreSummaryCommittedVersion = requestVersion;
                clearPreSummaryFailure();
                if ("incremental".equals(maintenanceMode)) {
                    preSummaryIncrementalCount.incrementAndGet();
                } else {
                    preSummaryFullCount.incrementAndGet();
                }
                lastPreSummaryMetrics = new SessionPreSummaryMetrics(
                        maintenanceMode,
                        coveredMessages.size(),
                        deltaMessageCount,
                        inputTokenEstimate,
                        summary.length(),
                        preSummaryFullCount.get(),
                        preSummaryIncrementalCount.get(),
                        preSummaryFailureCount.get(),
                        Instant.now());
            }
            return SessionPreSummaryMaintenanceResult.MAINTAINED;
        } catch (IOException | RuntimeException e) {
            boolean deterministic = !(e instanceof com.devcli.llm.LlmException failure && failure.retryable());
            recordPreSummaryFailure(requestVersion, coveredFingerprint, deterministic);
            if (e instanceof com.devcli.llm.LlmException failure && Boolean.parseBoolean(System.getProperty(
                    ConversationHistoryCompactor.COMPACTION_METRICS_PROPERTY, "false"))) {
                System.err.printf(java.util.Locale.ROOT,
                        "[context-compaction] kind=pre-summary-error code=%s status=%d%n",
                        failure.code(), failure.statusCode());
            }
            log.warn("session pre-summary maintenance failed", e);
            return SessionPreSummaryMaintenanceResult.FAILED;
        }
    }

    private boolean isPreSummaryFailureCoolingDown(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return false;
        }
        long until = preSummaryFailureUntilMillis;
        if (until <= System.currentTimeMillis()) {
            clearPreSummaryFailure();
            return false;
        }
        return fingerprint.equals(preSummaryFailureFingerprint);
    }

    private boolean isValidPreSummaryResult(long requestVersion) {
        synchronized (sessionPreSummaryScheduleLock) {
            return !sessionPreSummaryClosed
                    && requestVersion > sessionPreSummaryInvalidatedVersion
                    && requestVersion > sessionPreSummaryCommittedVersion;
        }
    }

    private void recordPreSummaryFailure(long requestVersion, String fingerprint, boolean deterministic) {
        synchronized (sessionPreSummaryScheduleLock) {
            if (!isValidPreSummaryResult(requestVersion)) {
                return;
            }
            preSummaryFailureCount.incrementAndGet();
            if (deterministic && fingerprint != null && !fingerprint.isBlank()) {
                preSummaryFailureFingerprint = fingerprint;
                preSummaryFailureUntilMillis = System.currentTimeMillis()
                        + SESSION_PRE_SUMMARY_FAILURE_COOLDOWN_MILLIS;
            }
        }
    }

    private void clearPreSummaryFailure() {
        preSummaryFailureFingerprint = "";
        preSummaryFailureUntilMillis = 0L;
    }

    private boolean preSummaryInputExceedsBudget(int inputTokenEstimate) {
        boolean exceeded = inputTokenEstimate
                > Math.max(256, contextProfile.get().compressionTriggerTokens());
        if (exceeded) {
            log.info("skip session pre-summary because source exceeds the local input budget");
        }
        return exceeded;
    }

    private void recordPreSummaryUsage(LlmClient.ChatResponse response) {
        if (response == null) return;
        tokenBudget.get().recordUsage(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
        if (!Boolean.parseBoolean(System.getProperty(
                ConversationHistoryCompactor.COMPACTION_METRICS_PROPERTY, "false"))) {
            return;
        }
        System.err.printf(java.util.Locale.ROOT,
                "[context-compaction] kind=pre-summary-call inputTokens=%d outputTokens=%d cachedInputTokens=%d%n",
                response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
    }

    CompletableFuture<SessionPreSummaryMaintenanceResult> maintainSessionPreSummaryAfterTurnAsync(
            List<LlmClient.Message> history,
            int turnToolCalls,
            int largestToolResultChars) {
        return maintainSessionPreSummaryAfterTurnAsync(history, turnToolCalls, largestToolResultChars,
                contextProfile.get().compressionTriggerTokens(), () -> true, response -> { });
    }

    CompletableFuture<SessionPreSummaryMaintenanceResult> maintainSessionPreSummaryAfterTurnAsync(
            List<LlmClient.Message> history, int turnToolCalls, int largestToolResultChars,
            int triggerTokens, java.util.function.BooleanSupplier callGuard,
            java.util.function.Consumer<LlmClient.ChatResponse> usageConsumer) {
        SessionPreSummaryTask task;
        SessionPreSummaryTask superseded = null;
        synchronized (sessionPreSummaryScheduleLock) {
            List<LlmClient.Message> snapshot = sessionPreSummaryCompactor.preSummaryHistory(history, triggerTokens);
            long requestVersion = sessionPreSummaryClosed ? -1 : ++sessionPreSummaryRequestVersion;
            task = new SessionPreSummaryTask(
                    snapshot, turnToolCalls, largestToolResultChars, callGuard, usageConsumer,
                    requestVersion);
            if (sessionPreSummaryClosed) {
                task.complete(SessionPreSummaryMaintenanceResult.SKIPPED_DISABLED);
                return task.result;
            }
            if (runningSessionPreSummary == null) {
                runningSessionPreSummary = task;
                sessionPreSummaryExecutor.execute(task);
            } else {
                superseded = pendingSessionPreSummary;
                pendingSessionPreSummary = task;
            }
        }
        if (superseded != null) {
            superseded.complete(SessionPreSummaryMaintenanceResult.SKIPPED_SUPERSEDED);
        }
        return task.result;
    }

    private final class SessionPreSummaryTask implements Runnable {
        private final List<LlmClient.Message> history;
        private final int turnToolCalls;
        private final int largestToolResultChars;
        private final java.util.function.BooleanSupplier callGuard;
        private final java.util.function.Consumer<LlmClient.ChatResponse> usageConsumer;
        private final long requestVersion;
        private final CompletableFuture<SessionPreSummaryMaintenanceResult> result =
                new CompletableFuture<>();

        private SessionPreSummaryTask(List<LlmClient.Message> history,
                                     int turnToolCalls,
                                     int largestToolResultChars,
                                     java.util.function.BooleanSupplier callGuard,
                                     java.util.function.Consumer<LlmClient.ChatResponse> usageConsumer,
                                     long requestVersion) {
            this.history = history;
            this.turnToolCalls = turnToolCalls;
            this.largestToolResultChars = largestToolResultChars;
            this.callGuard = callGuard;
            this.usageConsumer = usageConsumer;
            this.requestVersion = requestVersion;
        }

        @Override
        public void run() {
            try {
                sessionPreSummaryCompactor.setSummaryCallGuard(callGuard);
                sessionPreSummaryCompactor.setSummaryUsageConsumer(response -> {
                    recordPreSummaryUsage(response);
                    usageConsumer.accept(response);
                });
                complete(maintainSessionPreSummaryForCoveredHistory(
                        history, turnToolCalls, largestToolResultChars, requestVersion));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            } finally {
                sessionPreSummaryCompactor.setSummaryCallGuard(null);
                sessionPreSummaryCompactor.setSummaryUsageConsumer(SessionPreSummaryScheduler.this::recordPreSummaryUsage);
                scheduleNextPreSummary(this);
            }
        }

        private void complete(SessionPreSummaryMaintenanceResult maintenanceResult) {
            result.complete(maintenanceResult);
        }
    }

    private void scheduleNextPreSummary(SessionPreSummaryTask completed) {
        synchronized (sessionPreSummaryScheduleLock) {
            if (runningSessionPreSummary != completed) {
                return;
            }
            runningSessionPreSummary = null;
            if (sessionPreSummaryClosed || pendingSessionPreSummary == null) {
                return;
            }
            runningSessionPreSummary = pendingSessionPreSummary;
            pendingSessionPreSummary = null;
            sessionPreSummaryExecutor.execute(runningSessionPreSummary);
        }
    }

    @Override
    public void close() {
        SessionPreSummaryTask discarded;
        synchronized (sessionPreSummaryScheduleLock) {
            sessionPreSummaryClosed = true;
            sessionPreSummaryInvalidatedVersion = ++sessionPreSummaryRequestVersion;
            discarded = pendingSessionPreSummary;
            pendingSessionPreSummary = null;
            sessionPreSummaryExecutor.shutdownNow();
        }
        if (discarded != null) {
            discarded.complete(SessionPreSummaryMaintenanceResult.SKIPPED_SUPERSEDED);
        }
    }
}
