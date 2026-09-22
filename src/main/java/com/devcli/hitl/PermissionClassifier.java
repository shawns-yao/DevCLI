package com.devcli.hitl;

import java.io.IOException;

/**
 * 权限分类器：对求值链末端仍为「询问」的动作做一次判断。
 *
 * <p>它是 {@code auto} 模式的收口实现。调用方只在「没有任何规则或安全机制要求询问、
 * 仅仅因为默认策略才需要询问」的动作上回调它，且只接收放行与拒绝两种结果——
 * 分类器没有「继续询问」这个选项，否则 {@code auto} 就退化成了多一次往返的 {@code default}。</p>
 *
 * <p>实现不得依赖会话历史或记忆：判断必须只依据单次动作本身。这既是可复现性的要求，
 * 也是「最小权限」的要求——上下文越宽，越容易把此前某次无关的授权当成这次的理由。</p>
 */
public interface PermissionClassifier {

    /**
     * 待判断的动作。
     *
     * <p>字段是刻意收窄的：工具名、参数、项目路径、当前模式。不放会话消息、不放工具输出、
     * 不放已授权的资源清单。</p>
     */
    record Request(String toolName, String argumentsJson, String projectPath, String mode) {
    }

    /** 判定结果。只有放行与拒绝两种。 */
    record Verdict(boolean allow, String reason) {
        public Verdict {
            reason = reason == null ? "" : reason;
        }

        public static Verdict allow(String reason) {
            return new Verdict(true, reason);
        }

        public static Verdict deny(String reason) {
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
     */
    default long decisionTimeoutMillis() {
        return 0L;
    }
}
