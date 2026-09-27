package com.devcli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                    "hard_deny": ["execute_command(git push:*)"],
                    "soft_deny": ["write_file(.github/**)"],
                    "allow": ["Edit(src/**)", "execute_command(mvn test:*)"],
                    "environment": ["生产集群是共享资源"]
                  }
                }
                """;

        DevCliConfig.PermissionsConfig permissions =
                MAPPER.readValue(json, DevCliConfig.class).getPermissions();

        assertEquals(List.of("execute_command(git push:*)"), permissions.getHardDeny());
        assertEquals(List.of("write_file(.github/**)"), permissions.getSoftDeny());
        assertEquals(List.of("Edit(src/**)", "execute_command(mvn test:*)"), permissions.getAllow());
        assertEquals(List.of("生产集群是共享资源"), permissions.getEnvironment());
    }

    @Test
    void absentPermissionRulesYieldEmptyLists() throws Exception {
        DevCliConfig.PermissionsConfig permissions =
                MAPPER.readValue("{}", DevCliConfig.class).getPermissions();

        assertTrue(permissions.getHardDeny().isEmpty());
        assertTrue(permissions.getSoftDeny().isEmpty());
        assertTrue(permissions.getAllow().isEmpty());
        assertTrue(permissions.getEnvironment().isEmpty());
    }

    @Test
    void explicitNullPermissionRulesDoNotBreakConfig() throws Exception {
        String json = """
                {"permissions": {"hard_deny": null, "soft_deny": null, "allow": null, "environment": null}}
                """;

        DevCliConfig.PermissionsConfig permissions =
                MAPPER.readValue(json, DevCliConfig.class).getPermissions();

        assertTrue(permissions.getHardDeny().isEmpty());
        assertTrue(permissions.getSoftDeny().isEmpty());
        assertTrue(permissions.getAllow().isEmpty());
        assertTrue(permissions.getEnvironment().isEmpty());
    }

    @Test
    void legacyPermissionKeysAreRejected() {
        String json = "{\"permissions\":{\"deny\":[\"write_file\"],\"ask\":[]}}";

        assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
                () -> MAPPER.readValue(json, DevCliConfig.class));
    }
}
