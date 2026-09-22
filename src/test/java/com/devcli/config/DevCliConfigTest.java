package com.devcli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code permissions} 段的配置文件契约测试：持久授权基线与用户规则层。
 *
 * <p>两者都是跨会话生效的授权声明，读错却静默降级会让用户以为授权在生效，因此这里覆盖
 * 「字段被正确读出」与「缺省/显式 null 不产生空指针」两类路径。</p>
 */
class DevCliConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readsPermissionRulesFromJson() throws Exception {
        String json = """
                {
                  "permissions": {
                    "deny": ["execute_command(git push:*)"],
                    "ask": ["write_file(.github/**)"],
                    "allow": ["Edit(src/**)", "execute_command(mvn test:*)"]
                  }
                }
                """;

        DevCliConfig.PermissionsConfig permissions =
                MAPPER.readValue(json, DevCliConfig.class).getPermissions();

        assertEquals(List.of("execute_command(git push:*)"), permissions.getDeny());
        assertEquals(List.of("write_file(.github/**)"), permissions.getAsk());
        assertEquals(List.of("Edit(src/**)", "execute_command(mvn test:*)"), permissions.getAllow());
    }

    @Test
    void absentPermissionRulesYieldEmptyLists() throws Exception {
        DevCliConfig.PermissionsConfig permissions =
                MAPPER.readValue("{}", DevCliConfig.class).getPermissions();

        assertTrue(permissions.getDeny().isEmpty());
        assertTrue(permissions.getAsk().isEmpty());
        assertTrue(permissions.getAllow().isEmpty());
    }

    @Test
    void explicitNullPermissionRulesDoNotBreakConfig() throws Exception {
        String json = """
                {"permissions": {"deny": null, "ask": null, "allow": null}}
                """;

        DevCliConfig.PermissionsConfig permissions =
                MAPPER.readValue(json, DevCliConfig.class).getPermissions();

        assertTrue(permissions.getDeny().isEmpty());
        assertTrue(permissions.getAsk().isEmpty());
        assertTrue(permissions.getAllow().isEmpty());
    }
}
