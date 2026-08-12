package com.devcli.runtime;

/** 同一 Attempt 模型中的不同业务语义，禁止把修复或恢复伪装成网络重试。 */
public enum AttemptKind {
    INITIAL,
    INFRASTRUCTURE_RETRY,
    CORRECTION,
    RECOVERY
}
