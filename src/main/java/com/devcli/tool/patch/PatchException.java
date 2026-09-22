package com.devcli.tool.patch;

/**
 * apply_patch 解析或编译失败。
 *
 * <p>两类失败对模型都是"可自行纠正"的：补丁文本不符合格式，或补丁声明的路径/上下文
 * 与项目当前内容不一致。因此调用方统一映射为可重试的参数类拒绝，而不是执行失败。</p>
 */
class PatchException extends RuntimeException {

    PatchException(String message) {
        super(message);
    }

    PatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
