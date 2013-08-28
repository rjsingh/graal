package com.edinburgh.parallel.opencl.phases;

import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.java.*;
import com.oracle.graal.phases.*;
import java.util.*;

public class ArrayDepthBuilderPhase extends Phase {

    private Map<Node, ArrayDepth> depthInfo;

    public ArrayDepthBuilderPhase() {
        depthInfo = new HashMap<>();
    }

    @Override
    protected void run(StructuredGraph graph) {
        List<Node> arrayRefNodes = new ArrayList<>();

        for (LocalNode ln : graph.getNodes(LocalNode.class)) {
            if (!ln.kind().isPrimitive() && ln.objectStamp().type().isArray()) {
                depthInfo.put(ln, new ArrayDepth(ln, 0));
                arrayRefNodes.add(ln);
            }
        }

        for (Node n : graph.getNodes()) {
            if (n instanceof AccessIndexedNode) {
                AccessIndexedNode ain = (AccessIndexedNode) n;
                // get distance to the root (LocalNodes)
                int dist = distanceToTop(n);
                // get the origin of the array e.g. input parameter.
                LocalNode ln = origin(n);
                depthInfo.put(n, new ArrayDepth(ln, dist));
                arrayRefNodes.add(ain);
            }
        }

        for (Node n : arrayRefNodes) {
            buildDepthInfo(n);
        }
        for (LocalNode ln : graph.getNodes(LocalNode.class)) {
            if (!ln.kind().isPrimitive() && ln.objectStamp().type().isArray()) {
                depthInfo.put(ln, new ArrayDepth(ln, 1));
            }
        }
    }

    // Use DFS to build depth info table.
    protected void buildDepthInfo(Node start) {
        ArrayDepth ad = depthInfo.get(start);
        for (Node usage : start.usages()) {
            // check if already visited.
            if (!depthInfo.containsKey(usage)) {
                depthInfo.put(usage, new ArrayDepth(ad.getNode(), ad.getDimensionAccessedAt() + 1));
            }
        }
    }

    protected LocalNode origin(Node n) {
        if (n instanceof LocalNode) {
            return (LocalNode) n;
        }
        if (n instanceof AccessIndexedNode) {
            return origin(((AccessIndexedNode) n).array());
        }
        return null;
    }

    protected int distanceToTop(Node n) {
        if (n instanceof AccessIndexedNode) {
            AccessIndexedNode ain = (AccessIndexedNode) n;
            return 1 + distanceToTop(ain.array());
        }
        return 0;
    }

    public Map<Node, ArrayDepth> getDepthInfo() {
        return depthInfo;
    }
}
