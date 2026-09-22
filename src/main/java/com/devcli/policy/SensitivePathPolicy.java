package com.devcli.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/**
 * 受保护路径策略：项目内的凭据、私钥与版本库元数据不允许被写入。
 *
 * <p>定位与 {@link PathGuard} 同级：策略层硬边界，由工具执行器调用，因此与 HITL 开关无关——
 * 关闭人工审批不会绕过它。用户也无法在审批里放行（策略拒绝不可批准）。</p>
 *
 * <p>文件名单与隔离工作区物化共用同一份实现（{@link #isSensitiveFile}）：物化用
 * {@code allowTemplates=false} 保持既有"不把本地凭据带进工作区"的行为，写入判定用
 * {@code allowTemplates=true}，放行 {@code .env.example} 这类本来就要提交的模板。</p>
 *
 * <p>仅保护 {@code .devcli} 下实际加载的安全配置，不封禁整个配置目录。
 * {@code target} / {@code node_modules} 仍不是安全边界。</p>
 */
public final class SensitivePathPolicy {

    /** 整棵目录受保护：任何写入都拒绝。 */
    private static final Set<String> PROTECTED_ROOTS = Set.of(".git", ".ssh");
    private static final Set<String> AGENT_CONFIG_NAMES = Set.of(
            "hooks.json", "mcp.json", "config.json");

    /** 允许写入的模板文件：它们本来就是要提交的示例配置。 */
    private static final Set<String> TEMPLATE_NAMES = Set.of(
            ".env.example", ".env.sample", ".env.template", ".env.dist");

    private static final Set<String> SENSITIVE_NAMES = Set.of(
            ".env", ".netrc", ".npmrc", ".pypirc",
            "credentials.json", "service-account.json",
            "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa");

    private static final Set<String> SENSITIVE_SUFFIXES = Set.of(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".ppk");

    private SensitivePathPolicy() {
    }

    public static boolean isDenied(Path projectRelative) {
        return denyReason(projectRelative) != null;
    }

    /**
     * 写入被拒原因；允许写入时返回 null。
     *
     * @param projectRelative 项目相对路径（由调用方保证已在项目根内）
     */
    public static String denyReason(Path projectRelative) {
        if (projectRelative == null || projectRelative.getNameCount() == 0) {
            return null;
        }
        for (Path segment : projectRelative) {
            String name = segment.toString().toLowerCase(Locale.ROOT);
            if (PROTECTED_ROOTS.contains(name)) {
                return "受保护目录 " + segment + " 不允许写入";
            }
        }
        if (isAgentConfigPath(projectRelative.normalize())) {
            return "受保护的 Agent 安全配置不允许由工具修改，请通过人工设置入口调整";
        }
        if (isSensitiveFile(projectRelative, true)) {
            return "受保护文件 " + projectRelative.getFileName() + " 不允许写入（凭据或私钥类）";
        }
        return null;
    }

    /**
     * Check both logical names and physical aliases before a tool or patch writes.
     * The custom Hook path follows HookConfigLoader's process-working-directory semantics.
     */
    public static String agentConfigDenyReason(Path projectRoot, Path target) {
        Path absolute = target.toAbsolutePath().normalize();
        String denied = "受保护的 Agent 安全配置不允许由工具修改，请通过人工设置入口调整";
        if (isAgentConfigPath(absolute)) return denied;
        var protectedPaths = new ArrayList<Path>();
        for (String name : AGENT_CONFIG_NAMES) {
            protectedPaths.add(projectRoot.resolve(".devcli").resolve(name));
            protectedPaths.add(Path.of(System.getProperty("user.home"), ".devcli", name));
        }
        String configured = System.getProperty("devcli.hooks.file");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("DEVCLI_HOOKS_FILE");
        }
        try {
            if (configured != null && !configured.isBlank()) {
                protectedPaths.add(Path.of(configured.trim()));
            }
            Path realTarget = canonicalPath(absolute);
            for (Path config : protectedPaths) {
                Path logicalConfig = config.toAbsolutePath().normalize();
                Path realConfig = canonicalPath(logicalConfig);
                if (logicalConfig.startsWith(absolute) || realConfig.startsWith(realTarget)
                        || (Files.exists(absolute) && Files.exists(logicalConfig)
                        && Files.isSameFile(absolute, logicalConfig))) {
                    return denied;
                }
            }
        } catch (IOException | RuntimeException failure) {
            return "受保护的 Agent 安全配置路径无法确认，拒绝写入";
        }
        return null;
    }

    private static boolean isAgentConfigPath(Path path) {
        for (int i = 0; i < path.getNameCount(); i++) {
            if (!".devcli".equalsIgnoreCase(path.getName(i).toString())) continue;
            if (i == path.getNameCount() - 1
                    || AGENT_CONFIG_NAMES.contains(path.getName(i + 1).toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Path canonicalPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) return absolute;
        return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
    }

    /**
     * 是否为敏感文件。
     *
     * @param allowTemplates true 时放行 {@code .env.example} 等模板；隔离工作区物化传 false
     */
    public static boolean isSensitiveFile(Path projectRelative, boolean allowTemplates) {
        if (projectRelative == null || projectRelative.getNameCount() == 0) {
            return false;
        }
        String name = projectRelative.getFileName().toString().toLowerCase(Locale.ROOT);
        if (SENSITIVE_NAMES.contains(name)) {
            return true;
        }
        if (name.startsWith(".env.")) {
            return !(allowTemplates && TEMPLATE_NAMES.contains(name));
        }
        for (String suffix : SENSITIVE_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
