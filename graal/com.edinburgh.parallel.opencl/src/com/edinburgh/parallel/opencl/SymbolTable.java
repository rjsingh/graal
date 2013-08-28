package com.edinburgh.parallel.opencl;

import java.util.*;

import com.edinburgh.parallel.opencl.phases.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;

/**
 * Symbol Table class to store and lookup variable information.
 * 
 * @author Ranjeet Singh
 */
public class SymbolTable {

    private LinkedList<Map<String, Node>> nameToNode;
    private LinkedList<Map<Node, String>> nodeToName;
    private LinkedList<Map<String, Kind>> nameToType;
    private Map<LocalNode, Integer[]> arraySize;
    private Map<Node, ArrayDepth> arrayAccDim;
    private Map<Node, Integer> arrayDimension;

    public static final String LOOP_VAR = "loop";
    public static final String CONDITION_VAR = "cond";
    public static final String CAST = "cast";
    public static final String PHI = "phi";
    public static final String RESULT_VAR = "result";
    public static final String FUNCTION_RESULT = "fuctresult";
    public static final String ARRAY_ELEMENT = "array_elem_val";
    public static final String PARAM_VALUE = "param";

    @SuppressWarnings("unused")
    public SymbolTable(Map<LocalNode, Integer[]> arraySize, Map<Node, ArrayDepth> arrayAccDim, Map<Node, Integer> arrayDimension) {
        nameToNode = new LinkedList<>();
        nodeToName = new LinkedList<>();
        nameToType = new LinkedList<>();
        enterScope();
        this.arraySize = arraySize;
        this.arrayAccDim = arrayAccDim;
        this.arrayDimension = arrayDimension;
    }

    public void add(String variableName, Node n, Kind type) {
        nameToNode.getLast().put(variableName, n);
        nodeToName.getLast().put(n, variableName);
        nameToType.getLast().put(variableName, type);
    }

    public void enterScope() {
        nameToNode.add(new HashMap<String, Node>());
        nodeToName.add(new HashMap<Node, String>());
        nameToType.add(new HashMap<String, Kind>());
    }

    public void exitScope() {
        nameToNode.removeLast();
        nodeToName.removeLast();
        nameToType.removeLast();
    }

    public boolean exists(Node n) {
        return lookupName(n) != null;
    }

    public String lookupName(Node n) {
        for (Map<Node, String> m : nodeToName) {
            if (m.containsKey(n)) {
                return m.get(n);
            }
        }
        return null;
    }

    public Kind lookupType(String varName) {
        for (Map<String, Kind> m : nameToType) {
            if (m.containsKey(varName)) {
                return m.get(varName);
            }
        }
        return null;
    }

    public Node lookupNode(String varName) {
        for (Map<String, Node> m : nameToNode) {
            if (m.containsKey(varName)) {
                return m.get(varName);
            }
        }
        return null;
    }

    public Integer[] lookupArraySize(LocalNode ln) {
        return arraySize.get(ln);
    }

    public int lookupArrayDimension(Node n) {
        return arrayDimension.get(n);
    }

    public ArrayDepth lookupArrayAccessInfo(Node n) {
        return arrayAccDim.get(n);
    }

    public String newVariable(String type) {
        int count = 0;
        String var = type + "_" + count;
        while (lookupNode(var) != null) {
            count++;
            var = type + "_" + count;
        }
        return var;
    }
}
