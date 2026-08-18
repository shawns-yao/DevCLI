package com.devcli.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionGraphTest {

    @Test
    void schedulesNormalNodesBeforeFinalIntegration() {
        List<Node> nodes = List.of(
                new Node("a", List.of(), ExecutionGraph.NodeState.COMPLETED, false),
                new Node("b", List.of("a"), ExecutionGraph.NodeState.PENDING, false),
                new Node("final", List.of("b"), ExecutionGraph.NodeState.PENDING, true)
        );

        List<Node> ready = ExecutionGraph.ready(
                nodes, Node::id, Node::dependencies, Node::state, Node::finalStep);

        assertEquals(List.of("b"), ready.stream().map(Node::id).toList());
    }

    @Test
    void finalIntegrationMayRunAfterFailedDependencies() {
        List<Node> nodes = List.of(
                new Node("a", List.of(), ExecutionGraph.NodeState.FAILED, false),
                new Node("final", List.of("a"), ExecutionGraph.NodeState.PENDING, true)
        );

        List<Node> ready = ExecutionGraph.ready(
                nodes, Node::id, Node::dependencies, Node::state, Node::finalStep);

        assertEquals(List.of("final"), ready.stream().map(Node::id).toList());
    }

    @Test
    void validationReportsMissingDependencyAndCycle() {
        ExecutionGraph.ValidationResult missing = ExecutionGraph.validate(
                List.of(new Node("a", List.of("missing"), ExecutionGraph.NodeState.PENDING, false)),
                Node::id, Node::dependencies);
        assertFalse(missing.valid());
        assertTrue(missing.errors().get(0).contains("missing"));

        ExecutionGraph.ValidationResult cycle = ExecutionGraph.validate(
                List.of(
                        new Node("a", List.of("b"), ExecutionGraph.NodeState.PENDING, false),
                        new Node("b", List.of("a"), ExecutionGraph.NodeState.PENDING, false)
                ), Node::id, Node::dependencies);
        assertFalse(cycle.valid());
        assertTrue(cycle.errors().stream().anyMatch(error -> error.contains("cycle")));
    }

    @Test
    void executionNodeOverloadsShareSchedulingAndValidation() {
        List<GraphNode> nodes = List.of(
                new GraphNode("a", List.of(),
                        ExecutionArtifact.completed("a", "done", "done", List.of()), false),
                new GraphNode("b", List.of("a"), ExecutionArtifact.pending("b"), false)
        );

        List<GraphNode> ready = ExecutionGraph.ready(nodes, GraphNode::finalStep);

        assertEquals(List.of("b"), ready.stream().map(GraphNode::id).toList());
        assertTrue(ExecutionGraph.validate(nodes).valid());
        assertEquals(List.of("a", "b"), ExecutionGraph.topologicalOrder(nodes));
    }

    private record Node(String id, List<String> dependencies,
                        ExecutionGraph.NodeState state, boolean finalStep) {
    }

    private record GraphNode(String id, List<String> dependencies,
                             ExecutionArtifact artifact, boolean finalStep)
            implements ExecutionNode {
        @Override
        public String description() {
            return id;
        }
    }
}
