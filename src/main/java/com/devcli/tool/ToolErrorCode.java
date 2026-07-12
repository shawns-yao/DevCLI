package com.devcli.tool;

/** 工具失败原因的稳定分类，供预算、恢复、审计和编排逻辑使用。 */
public enum ToolErrorCode {
    NONE,
    UNKNOWN_TOOL,
    INVALID_ARGUMENTS,
    SKILL_PERMISSION_DENIED,
    HITL_REJECTED,
    POLICY_DENIED,
    RESOURCE_CONFLICT,
    EXECUTION_FAILED,
    MCP_ERROR,
    CANCELLED,
    TIMEOUT
}
