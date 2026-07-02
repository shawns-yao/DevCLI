package com.devcli.tool.provider;

import com.devcli.browser.BrowserConnector;
import com.devcli.tool.ToolRegistry;

public final class BrowserToolProvider implements ToolProvider {
    @Override
    public void register(ToolContext context) {
        context.registerTool(new ToolRegistry.Tool(
                "browser_connect",
                "当浏览器页面返回登录页、权限不足或明确需要登录态时，自动连接已允许远程调试的本机 Chrome 并复用其登录态；公开页面不要提前调用。",
                context.createToolParameters(),
                args -> {
                    BrowserConnector connector = context.browserConnector();
                    return connector == null
                            ? "浏览器连接器未初始化，无法自动切换 shared 模式"
                            : connector.connectDefault();
                }
        ));
        context.registerTool(new ToolRegistry.Tool(
                "browser_disconnect",
                "完成登录态页面访问后，可切回 isolated 浏览器模式。",
                context.createToolParameters(),
                args -> {
                    BrowserConnector connector = context.browserConnector();
                    return connector == null
                            ? "浏览器连接器未初始化，无法切回 isolated 模式"
                            : connector.disconnect();
                }
        ));
        context.registerTool(new ToolRegistry.Tool(
                "browser_status",
                "查看当前浏览器 MCP 模式、autoConnect 引导和旧式 CDP 端口探活状态。",
                context.createToolParameters(),
                args -> {
                    BrowserConnector connector = context.browserConnector();
                    return connector == null
                            ? "浏览器连接器未初始化，无法查看浏览器状态"
                            : connector.status();
                }
        ));
    }
}
