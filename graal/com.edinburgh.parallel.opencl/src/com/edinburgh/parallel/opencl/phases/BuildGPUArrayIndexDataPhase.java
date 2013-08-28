package com.edinburgh.parallel.opencl.phases;

import java.lang.reflect.*;
import java.util.*;

import com.edinburgh.parallel.opencl.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.phases.*;

public class BuildGPUArrayIndexDataPhase extends Phase {

    private Object[] params;
    private Map<LocalNode, Integer[]> arraySizes;

    public BuildGPUArrayIndexDataPhase(Object[] params) {
        this.params = params;
        arraySizes = new HashMap<>();
    }

    @Override
    protected void run(StructuredGraph graph) {
        for (LocalNode ln : graph.getNodes(LocalNode.class)) {
            if (!ln.kind().isPrimitive() && ln.objectStamp().type().isArray()) {
                Object param = params[ln.index() - 1];
                Integer[] indexData = buildIndexData(param);
                arraySizes.put(ln, indexData);
            }
        }
    }

    public Map<LocalNode, Integer[]> getIndexData() {
        return arraySizes;
    }

    public Integer[] buildIndexData(Object srcArray) {
        List<Integer> indexData = new ArrayList<>();
        int dimSize = ArrayUtil.getNumArrayDimensions(srcArray);
        getIndexData(srcArray, indexData, 1, dimSize, 0, 0);

        Integer[] result = new Integer[indexData.size()];
        indexData.toArray(result);
        return result;
    }

    protected int getIndexData(Object srcArray, List<Integer> indexData, int currentDepth, final int depthLevel, int nextIndex, int copySize) {
        if (srcArray == null) {
            indexData.add(-1); // for null reference.
            return 0;
        } else if (srcArray.getClass().isArray()) {
            int length = Array.getLength(srcArray);
            indexData.add(nextIndex, length); // add size of array first.
            int nextLoc = indexData.size();
            if (currentDepth == depthLevel) {
                // then we have reached last dimension so have a pointer from here to data array.
                indexData.add(copySize);
                return length;
            } else {
                // allocate space for the number of arrays in this current array.
                for (int i = 0; i < length; i++) {
                    indexData.add(0);
                }
                // recursivly build rest of index table.
                int currentSize = copySize;
                for (int i = 0; i < length; i++) {
                    int nextBase = indexData.size();
                    // set start of next array of arrays.
                    indexData.set(nextLoc + i, nextBase);
                    // recurse.
                    currentSize += getIndexData(Array.get(srcArray, i), indexData, currentDepth + 1, depthLevel, nextBase, currentSize);
                }
            }
        }
        return 0;
    }

}
