package com.devcli.render;

import com.devcli.config.ConfigResolver;
import com.devcli.render.inline.InlineRenderer;
import com.devcli.render.inline.TerminalCapabilities;
import org.jline.terminal.Terminal;

/** 启动时选择 Inline 或 Plain；旧 lanterna/tui 配置在兼容期映射到 Inline。 */
public final class RendererFactory {

    public enum Mode {
        INLINE, PLAIN
    }

    private RendererFactory() {
    }

    public static Mode resolveMode() {
        String configured = ConfigResolver.optional("devcli.renderer", "DEVCLI_RENDERER");
        if (configured != null) {
            return parse(configured);
        }
        if (ConfigResolver.booleanValue("devcli.tui", "DEVCLI_TUI", false)) {
            System.err.println("DEVCLI_TUI=true 已废弃，当前统一使用 inline 渲染器");
            return Mode.INLINE;
        }
        return Mode.INLINE;
    }

    private static Mode parse(String raw) {
        return switch (raw.trim().toLowerCase()) {
            case "lanterna", "tui" -> {
                System.err.println("lanterna 渲染器已停用，当前统一使用 inline 渲染器");
                yield Mode.INLINE;
            }
            case "plain" -> Mode.PLAIN;
            case "inline" -> Mode.INLINE;
            default -> throw new IllegalArgumentException(
                    "非法配置 devcli.renderer/DEVCLI_RENDERER=" + raw
                            + "，必须为 inline、plain 或兼容值 lanterna");
        };
    }

    /** 创建 CLI 渲染器；Inline 在终端不支持 ANSI 时降级为 Plain。 */
    public static Renderer create(Mode mode, Terminal terminal) {
        return switch (mode) {
            case PLAIN -> new PlainRenderer();
            case INLINE -> {
                if (TerminalCapabilities.supportsAnsi(terminal)) {
                    yield new InlineRenderer(terminal);
                }
                System.err.println("终端不支持 ANSI，inline 模式回退到 plain");
                yield new PlainRenderer();
            }
        };
    }
}
