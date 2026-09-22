package com.devcli.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限模式的定向测试：解析、别名、放行策略与帮助文本。
 *
 * <p>重点钉住两件事。一是未知模式必须抛错而不是静默回落到默认值——一个拼错的模式名若悄悄变成
 * {@code default}，用户会以为自己仍在只读模式里。二是 {@code acceptEdits} 的「编辑类工具」判定
 * 只认资源槽为 {@link ToolResourceSlot#PATH} 的工具：删除文件不在「接受编辑」的语义内。</p>
 */
class PermissionModeTest {

    @Test
    void everyDeclaredIdParsesBackToItsMode() {
        for (PermissionMode mode : PermissionMode.values()) {
            assertEquals(mode, PermissionMode.parse(mode.id()), mode.id());
        }
    }

    @Test
    void aliasesResolveToTheirCanonicalMode() {
        assertEquals(PermissionMode.PLAN, PermissionMode.parse("readonly"));
        assertEquals(PermissionMode.PLAN, PermissionMode.parse("read-only"));
        assertEquals(PermissionMode.ACCEPT_EDITS, PermissionMode.parse("accept-edits"));
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, PermissionMode.parse("bypass"));
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, PermissionMode.parse("bypass-permissions"));
        assertEquals(PermissionMode.DONT_ASK, PermissionMode.parse("dont-ask"));
    }

    @Test
    void parsingIgnoresCaseAndSurroundingWhitespace() {
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, PermissionMode.parse("  BypassPermissions  "));
        assertEquals(PermissionMode.DEFAULT, PermissionMode.parse("DEFAULT"));
    }

    @Test
    void unknownModeIsRejectedInsteadOfFallingBack() {
        IllegalArgumentException invalid =
                assertThrows(IllegalArgumentException.class, () -> PermissionMode.parse("maybe"));
        assertTrue(invalid.getMessage().contains("maybe"), invalid.getMessage());
        for (String id : PermissionMode.ids()) {
            assertTrue(invalid.getMessage().contains(id),
                    "错误提示要列出可选值，否则用户只能去翻文档: " + invalid.getMessage());
        }
    }

    @Test
    void blankModeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PermissionMode.parse(null));
        assertThrows(IllegalArgumentException.class, () -> PermissionMode.parse("   "));
    }

    @Test
    void defaultModeNeverAutoAllows() {
        PermissionMode mode = PermissionMode.DEFAULT;
        assertFalse(mode.autoAllows("write_file"));
        assertFalse(mode.autoAllows("execute_command"));
        assertFalse(mode.autoAllows("delete_files"));
    }

    @Test
    void bypassPermissionsAutoAllowsEverything() {
        PermissionMode mode = PermissionMode.BYPASS_PERMISSIONS;
        for (String tool : List.of("write_file", "edit_file", "delete_files",
                "create_project", "execute_command", "web_fetch")) {
            assertTrue(mode.autoAllows(tool), tool);
        }
    }

    @Test
    void dontAskNeverAutoAllows() {
        // dontAsk 的语义是「不询问」，不是「都放行」：它把未决动作收口为拒绝
        PermissionMode mode = PermissionMode.DONT_ASK;
        assertFalse(mode.autoAllows("write_file"));
        assertFalse(mode.autoAllows("execute_command"));
    }

    @Test
    void autoNeverAutoAllowsSoClassifierDecides() {
        // auto 的价值在于「不确定时让分类器判断」，不是「自动放行」：它在规则层短路点必须留手，
        // 把动作让给求值链末端的分类器。同时它也不能收口为拒绝，否则分类器根本没机会判定。
        PermissionMode mode = PermissionMode.AUTO;
        for (String tool : List.of("write_file", "edit_file", "delete_files",
                "create_project", "execute_command", "web_fetch")) {
            assertFalse(mode.autoAllows(tool), tool);
        }
        assertTrue(mode.autoAllowReason().isEmpty());
        assertTrue(mode.denyReason().isEmpty(), "auto 不能收口为拒绝，否则分类器没有机会判定");
    }

    @Test
    void acceptEditsOnlyAutoAllowsPathSlotTools() {
        PermissionMode mode = PermissionMode.ACCEPT_EDITS;
        assertTrue(mode.autoAllows("write_file"));
        assertTrue(mode.autoAllows("edit_file"));
        assertFalse(mode.autoAllows("delete_files"),
                "接受编辑不等于同意删除文件");
        assertFalse(mode.autoAllows("create_project"),
                "create_project 会在项目名下写出整个骨架，不是编辑单个文件");
        assertFalse(mode.autoAllows("execute_command"));
        assertFalse(mode.autoAllows("web_fetch"));
    }

    @Test
    void onlyDontAskCarriesADenialReason() {
        assertFalse(PermissionMode.DONT_ASK.denyReason().isBlank());
        for (PermissionMode mode : PermissionMode.values()) {
            if (mode == PermissionMode.DONT_ASK) {
                continue;
            }
            assertTrue(mode.denyReason().isEmpty(), mode.id() + " 不该把未决动作收口为拒绝");
        }
    }

    @Test
    void autoAllowReasonIsEmptyForModesThatNeverAutoAllow() {
        assertTrue(PermissionMode.DEFAULT.autoAllowReason().isEmpty());
        assertTrue(PermissionMode.DONT_ASK.autoAllowReason().isEmpty());
        assertFalse(PermissionMode.BYPASS_PERMISSIONS.autoAllowReason().isBlank());
        assertFalse(PermissionMode.ACCEPT_EDITS.autoAllowReason().isBlank());
    }

    @Test
    void onlyPlanRestrictsCapability() {
        assertEquals(PermissionMode.Capability.READ_ONLY, PermissionMode.PLAN.capability());
        for (PermissionMode mode : PermissionMode.values()) {
            if (mode == PermissionMode.PLAN) {
                continue;
            }
            assertEquals(PermissionMode.Capability.FULL, mode.capability(), mode.id());
        }
    }

    @Test
    void usageCoversEveryIdAndDescriptionWithoutHandWrittenDrift() {
        String usage = PermissionMode.usage();
        for (PermissionMode mode : PermissionMode.values()) {
            assertTrue(usage.contains(mode.id()), "帮助文本漏了 " + mode.id() + ": " + usage);
            assertTrue(usage.contains(mode.description()),
                    "帮助文本的说明要与枚举保持一致，避免手写漂移: " + usage);
        }
    }
}
