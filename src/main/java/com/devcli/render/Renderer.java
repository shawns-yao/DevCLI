package com.devcli.render;

import com.devcli.hitl.ApprovalRequest;
import com.devcli.hitl.ApprovalResult;
import com.devcli.llm.LlmClient;
import com.devcli.render.state.RunSnapshot;
import com.devcli.runtime.event.RunEventSink;
import org.jline.reader.LineReader;

import java.io.PrintStream;
import java.util.List;

/**
 * 终端渲染器抽象。
 *
 * <p>把对话流核心交互（流式输出、工具调用、HITL、状态栏、行内 diff、palette）
 * 收口到一个接口，方便 inline 流式与 plain 两种形态切换。
 *
 * <p>启动期一次性输出（banner、MCP/Skill 摘要等）仍可走原有 stdout；
 * 对话期的流、工具块、状态栏、输入区应尽量经过 Renderer，避免多个组件
 * 直接争抢终端光标。
 *
 * <p>线程模型：所有方法应在调用方线程同步返回。
 */
public interface Renderer extends AutoCloseable {

    /** 启动渲染器（例如设置滚动区域、启动 GUI 主循环）。Main 必须先调用一次。 */
    void start();

    /** 开始一次用户任务输出。默认 no-op；inline renderer 用它重置本轮可重绘 transcript。 */
    default void beginTurn() {
    }

    /** 进入用户输入前。inline renderer 用它刷新输入周边状态。 */
    default void beforeInput() {
    }

    /** 用户输入结束后。inline renderer 用它恢复输入周边状态。 */
    default void afterInput() {
    }

    /** 当前渲染器是否支持独立的模型思考面板。 */
    default boolean supportsThinkingPanel() {
        return false;
    }

    /** 开始显示模型思考面板。plain renderer 保持 no-op，继续用正文流式输出。 */
    default void beginThinking(String label) {
    }

    /** 追加模型 reasoning delta 到思考面板。 */
    default void appendThinking(String delta) {
    }

    /** 结束并清理模型思考面板。 */
    default void endThinking() {
    }

    /** 绑定交互循环使用的 LineReader，审批等嵌套输入必须复用同一读取入口。 */
    default void bindLineReader(LineReader lineReader) {
    }

    /** 当前渲染器希望 LineReader 使用的左侧输入提示。 */
    default String inputPrompt() {
        return "> ";
    }

    /** 当前渲染器希望 LineReader 使用的右侧提示；返回 null 表示不显示。 */
    default String inputRightPrompt() {
        return null;
    }

    @Override
    void close();

    /**
     * 流式输出的目标 PrintStream。
     *
     * <p>Agent.StreamRenderer / PlanExecuteAgent.TaskStreamRenderer / SubAgent.SubAgentStreamRenderer
     * 把流式 reasoning / content 写到这里。
     *
     * <p>对 InlineRenderer / PlainRenderer 而言这就是终端正文输出流。
     */
    PrintStream stream();

    /**
     * 渲染一组工具调用的标签和关键参数。
     *
     * <p>InlineRenderer 把每条调用包装成可折叠块（Day 3）；
     * PlainRenderer 直接 println 当前 Agent 内已有的标签格式。
     */
    void appendToolCalls(List<LlmClient.ToolCall> toolCalls);

    /**
     * 渲染一个文件 diff 块。
     *
     * @param filePath 文件路径
     * @param before   修改前内容（null 表示新建）
     * @param after    修改后内容（null 表示删除）
     */
    void appendDiff(String filePath, String before, String after);

    /** 更新底部状态栏。允许频繁调用，渲染器内部自行节流。 */
    void updateStatus(StatusInfo status);

    /** UI 状态只从统一 RunSnapshot 进入；旧 StatusInfo 仅作为环境兼容输入。 */
    default void renderSnapshot(RunSnapshot snapshot) {
    }

    /** Renderer 消费强类型事件并投影为 RunSnapshot。 */
    default RunEventSink eventSink() {
        return RunEventSink.NO_OP;
    }

    /**
     * 同步阻塞地展示 HITL 审批请求并收集决策。
     *
     * <p>实现必须复用主 LineReader，禁止创建竞争读取 System.in 的入口。
     */
    ApprovalResult promptApproval(ApprovalRequest request);

    /**
     * 显示一个临时浮起的选择列表，等待用户选定一项或取消。
     *
     * @return 选中项的下标；用户取消（Esc）返回 -1
     */
    int openPalette(String title, List<String> items);
}
