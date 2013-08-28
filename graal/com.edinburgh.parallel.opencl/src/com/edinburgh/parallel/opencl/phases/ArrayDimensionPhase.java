package com.edinburgh.parallel.opencl.phases;

import java.util.*;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.phases.*;

public class ArrayDimensionPhase extends Phase {

    private Map<Node, Integer> arrayDimensions;

    public ArrayDimensionPhase() {
        arrayDimensions = new HashMap<>();
    }

    @Override
    protected void run(StructuredGraph graph) {
        for (LocalNode ln : graph.getNodes(LocalNode.class)) {
            if (!ln.kind().isPrimitive() && ln.objectStamp().type().isArray()) {
                int dim = ln.objectStamp().type().getName().length() - 1;
                arrayDimensions.put(ln, dim);
            }
        }
    }

    public Map<Node, Integer> getDimensions() {
        return arrayDimensions;
    }

}
