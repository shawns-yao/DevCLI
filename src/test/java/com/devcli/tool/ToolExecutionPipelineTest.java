package com.devcli.tool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolExecutionPipelineTest {

    @Test
    void executesMiddlewareByStageAndAllowsArgumentReplacement() {
        List<String> events = new ArrayList<>();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(context -> {
            events.add("execute:" + context.argumentsJson());
            return ToolOutput.success(context.argumentsJson());
        });
        pipeline.register(ToolExecutionPipeline.Stage.AUDIT, (context, chain) -> {
            events.add("audit-before");
            ToolOutput output = chain.proceed(context);
            events.add("audit-after");
            return output;
        });
        pipeline.register(ToolExecutionPipeline.Stage.HITL, (context, chain) -> {
            events.add("hitl");
            context.replaceArguments("{\"approved\":true}");
            return chain.proceed(context);
        });
        pipeline.register(ToolExecutionPipeline.Stage.ARGUMENT_VALIDATION, (context, chain) -> {
            events.add("validate");
            return chain.proceed(context);
        });

        ToolOutput output = pipeline.execute("write_file", "{}", "call_1");

        assertEquals("{\"approved\":true}", output.text());
        assertEquals(List.of(
                "validate",
                "hitl",
                "audit-before",
                "execute:{\"approved\":true}",
                "audit-after"
        ), events);
    }

    @Test
    void shortCircuitStopsLaterMiddlewareAndExecution() {
        List<String> events = new ArrayList<>();
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(context -> {
            events.add("execute");
            return ToolOutput.success("unexpected");
        });
        pipeline.register(ToolExecutionPipeline.Stage.CANCELLATION,
                (context, chain) -> ToolOutput.cancelled("cancelled"));
        pipeline.register(ToolExecutionPipeline.Stage.EXISTENCE, (context, chain) -> {
            events.add("existence");
            return chain.proceed(context);
        });

        ToolOutput output = pipeline.execute("read_file", "{}", null);

        assertEquals(ToolStatus.CANCELLED, output.status());
        assertEquals(List.of(), events);
    }
}
