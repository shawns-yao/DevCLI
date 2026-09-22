package com.devcli.tool.patch;

import java.util.List;

/**
 * apply_patch 文本解析后的结构化变更单元。
 *
 * <p>对应官方 Lark grammar 的 {@code hunk: add_hunk | delete_hunk | update_hunk}。
 * 解析阶段不接触文件系统，能否真正应用由 {@link ApplyPatchCompiler} 判定。</p>
 */
sealed interface PatchHunk {

    /** 该 hunk 声明的目标路径，取自补丁原文，尚未做路径策略校验。 */
    String path();

    /** {@code *** Add File:} — 以给定内容创建文件。 */
    record AddFile(String path, List<String> contents) implements PatchHunk {
        public AddFile {
            contents = List.copyOf(contents);
        }
    }

    /** {@code *** Delete File:} — 删除文件。 */
    record DeleteFile(String path) implements PatchHunk {
    }

    /**
     * {@code *** Update File:} — 就地修改，可选 {@code *** Move to:} 改名。
     *
     * @param movePath 非空表示改名到该路径
     * @param chunks   按文件顺序排列的修改块；顺序错乱会被拒绝
     */
    record UpdateFile(String path, String movePath, List<UpdateFileChunk> chunks) implements PatchHunk {
        public UpdateFile {
            chunks = List.copyOf(chunks);
        }
    }

    /**
     * 一个 {@code @@} 修改块。
     *
     * @param changeContext {@code @@} 后跟的定位文字（通常是类名/方法名），可为 null
     * @param oldLines      待匹配的原文行
     * @param newLines      替换后的行
     * @param endOfFile     {@code *** End of File} 声明的"必须位于文件末尾"
     */
    record UpdateFileChunk(String changeContext, List<String> oldLines, List<String> newLines,
                           boolean endOfFile) {
        public UpdateFileChunk {
            oldLines = List.copyOf(oldLines);
            newLines = List.copyOf(newLines);
        }
    }
}
