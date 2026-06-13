package com.devcli.web;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;

/**
 * OkHttp 拦截器：仅对网络/基础设施层错误做指数退避重试。
 *
 * <p>分工边界（重要）：
 * <ul>
 *   <li>本拦截器 = Layer 1：处理 IOException / 5xx / 408 / 429。
 *       这些都是"传输层抖动"——LLM 看到了也帮不上忙，重试一下大概率成功</li>
 *   <li>工具层（{@code ToolRegistry}）= Layer 2：业务错误（参数错、policy 拒绝、4xx）
 *       <b>不</b>重试，原样回灌给 LLM。让 LLM 看到错误自己决定 retry / 换路径 / 报告失败</li>
 *   <li>Agent 层（ReAct 循环）= Layer 3：依赖 LLM 的语义重规划（semantic retry），
 *       不做工程级 retry——避免破坏 LLM 因果推理</li>
 * </ul>
 *
 * <p>什么会重试：
 * <ul>
 *   <li>{@link IOException}（含 {@link SocketTimeoutException}、connection reset、broken pipe）</li>
 *   <li>HTTP 5xx（服务端错误）</li>
 *   <li>HTTP 408 Request Timeout</li>
 *   <li>HTTP 429 Too Many Requests（带 Retry-After 时优先用 server 指示）</li>
 * </ul>
 *
 * <p>什么<b>不</b>重试：
 * <ul>
 *   <li>HTTP 4xx 除 408/429（是业务错误，重试一次大概率仍失败）</li>
 *   <li>3xx（OkHttp 自动跟随重定向）</li>
 *   <li>非幂等请求方法的写类工具调用：本拦截器不区分方法，因为 DevCLI 的写类工具
 *       （write_file、execute_command）<b>不走 HTTP</b>，没有"写类 HTTP 请求被自动重试"的风险</li>
 * </ul>
 *
 * <p>退避策略：指数退避 + 抖动。第 N 次失败后等 {@code baseBackoffMs * 2^N + jitter}。
 * 默认 base=500ms，最多重试 2 次（含首次共 3 次尝试）。
 */
public class RetryInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(RetryInterceptor.class);

    private final int maxRetries;
    private final long baseBackoffMs;

    public RetryInterceptor() {
        this(2, 500);
    }

    public RetryInterceptor(int maxRetries, long baseBackoffMs) {
        this.maxRetries = Math.max(0, maxRetries);
        this.baseBackoffMs = Math.max(0, baseBackoffMs);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        IOException lastIoException = null;
        Response lastResponse = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                long backoff = computeBackoff(attempt, lastResponse);
                if (log.isDebugEnabled()) {
                    log.debug("RetryInterceptor sleeping {}ms before attempt {} for {}",
                            backoff, attempt + 1, request.url());
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("retry interrupted", ie);
                }
                if (lastResponse != null) {
                    lastResponse.close();
                    lastResponse = null;
                }
            }
            try {
                Response response = chain.proceed(request);
                if (!isRetriableStatus(response.code()) || attempt == maxRetries) {
                    return response;
                }
                log.info("RetryInterceptor: HTTP {} for {}, will retry (attempt {}/{})",
                        response.code(), request.url(), attempt + 1, maxRetries + 1);
                lastResponse = response;
            } catch (IOException ioe) {
                lastIoException = ioe;
                if (attempt == maxRetries) {
                    throw ioe;
                }
                log.info("RetryInterceptor: {} for {}, will retry (attempt {}/{})",
                        ioe.getClass().getSimpleName(), request.url(), attempt + 1, maxRetries + 1);
            }
        }
        // 走到这里说明 maxRetries=0 直接出去；防御性返回
        if (lastResponse != null) {
            return lastResponse;
        }
        if (lastIoException != null) {
            throw lastIoException;
        }
        // 不应该到这里
        throw new IOException("RetryInterceptor reached unreachable branch");
    }

    private static boolean isRetriableStatus(int code) {
        if (code == 408 || code == 429) return true;
        return code >= 500 && code < 600;
    }

    /**
     * 计算重试间隔。优先使用 server 在 429 响应里返回的 Retry-After（秒），
     * 否则用指数退避 + 0~25% 抖动。
     */
    private long computeBackoff(int attemptIndex, Response lastResponse) {
        if (lastResponse != null && lastResponse.code() == 429) {
            String retryAfter = lastResponse.header("Retry-After");
            if (retryAfter != null && !retryAfter.isBlank()) {
                try {
                    long seconds = Long.parseLong(retryAfter.trim());
                    if (seconds > 0 && seconds <= 30) {
                        return seconds * 1000L;
                    }
                } catch (NumberFormatException ignored) {
                    // 忽略非数字 Retry-After（如 HTTP-date 格式），走默认退避
                }
            }
        }
        long base = baseBackoffMs * (1L << Math.min(attemptIndex - 1, 6));
        long jitter = (long) (Math.random() * base * 0.25);
        return base + jitter;
    }
}
