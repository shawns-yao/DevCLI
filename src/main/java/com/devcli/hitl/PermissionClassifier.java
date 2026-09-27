package com.devcli.hitl;

import java.io.IOException;

/**
 * 权限分类器：判定一次工具调用该不该被阻止。
 *
 * <p>它是 {@code auto} 模式的判定者。该模式下的动作分两路进来：一路是求值链末端仍为「询问」的
 * 动作，另一路是命中用户 {@code allow} / {@code soft_deny} 规则的动作——{@code auto} 模式下
 * 规则层不短路，规则作为分类器输入参与同一次判定（见 {@code docs/adr/0013}）。
 * 分类器只返回阻止与放行两种结果，没有「继续询问」这个选项，否则 {@code auto} 就退化成了
 * 多一次往返的 {@code default}。</p>
 *
 * <p><b>判定取向（2026-09-23 对齐参照实现）：默认放行，只阻止明确有害的动作。</b>
 * 判据分三层——硬阻止（无条件）、软阻止（明确用户意图可清除）、允许例外（命中必须放行）。
 * 用户意图是最终信号，因此输入必须包含可信意图上下文：意图只能从真实用户消息里读出来。</p>
 *
 * <p>上下文先由 {@link TrustedIntentContext} 收窄：Assistant 自述、工具结果、系统注入、插件内容
 * 和委派报告均不进入分类器，避免把不可信文本当成授权证据。</p>
 */
public interface PermissionClassifier {

    /**
     * 待判断的动作。
     *
     * <p>字段是判定所需的最小集：工具名、参数、项目路径、当前模式、可信意图上下文与用户规则全集。</p>
     *
     * <p>上下文与规则都是必填的；没有规则时传 {@code PermissionRuleSet.EMPTY}。</p>
     */
    record Request(String toolName, String argumentsJson, String projectPath, String mode,
                   String intentContext, com.devcli.policy.PermissionRuleSet rules) {
        public Request {
            java.util.Objects.requireNonNull(intentContext, "intentContext");
            java.util.Objects.requireNonNull(rules, "rules");
        }
    }

    /**
     * 判定结果。只有阻止与放行两种——没有「判断不出来，还是问一下用户」。
     *
     * <p>{@code block} 的语义与参照实现的 {@code shouldBlock} 一致：判定的是「要不要阻止」，
     * 不是「要不要放行」。这样默认值就是 {@code false}（放行），与「默认放行」的取向自洽。</p>
     */
    record Verdict(boolean block, String reason) {
        public Verdict {
            reason = reason == null ? "" : reason;
        }

        /** 阻止。理由会展示给用户，必须具体。 */
        public static Verdict block(String reason) {
            return new Verdict(true, reason);
        }

        /**
         * 放行。
         *
         * <p>参照实现在放行时不要求理由。DevCLI 要求给：自动放行必须可见且可归因
         * （见 {@link HitlHandler#onTaskGrantAllow}），否则用户看到动作被跳过却无从知道
         * 是谁放行的。这是有意的偏离，见 {@code docs/adr/0011}。</p>
         */
        public static Verdict allow(String reason) {
            return new Verdict(false, reason);
        }
    }

    /**
     * 判断一次动作。
     *
     * @throws IOException 调用失败、超时或响应不可解析。调用方一律按拒绝处理，不做猜测。
     */
    Verdict classify(Request request) throws IOException;

    /**
     * 一次判定可能占用的最长时间；返回 0 表示实现没有声明固定预算。
     * 调用方用它在启动请求前检查工具剩余期限。
     *
     * <p>因此实现的预算必须明显小于工具批次预算（默认 90 秒）：预算一旦不小于工具剩余期限，
     * 调用方会直接拒绝且**根本不启动判定**，实现等于失效。默认实现取 30 秒，见
     * {@link LlmPermissionClassifier}。</p>
     */
    default long decisionTimeoutMillis() {
        return 0L;
    }
}
