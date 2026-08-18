package com.devcli.plan;

import java.util.List;

/** Plan 与 Team 执行节点共用的只读图视图。 */
public interface ExecutionNode {
    String id();

    String description();

    List<String> dependencies();

    ExecutionArtifact artifact();
}
