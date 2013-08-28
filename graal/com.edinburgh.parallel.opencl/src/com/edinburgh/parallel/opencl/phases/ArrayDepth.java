package com.edinburgh.parallel.opencl.phases;

import com.oracle.graal.nodes.*;

public class ArrayDepth {

    private LocalNode node;
    private int dimension;

    public ArrayDepth(LocalNode ln, int dim) {
        node = ln;
        dimension = dim;
    }

    public LocalNode getNode() {
        return node;
    }

    public int getDimensionAccessedAt() {
        return dimension;
    }

    @Override
    public String toString() {
        return "LocalNode: " + node + " Depth: " + dimension;
    }
}
