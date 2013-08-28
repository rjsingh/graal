package com.edinburgh.parallel.opencl;

import com.oracle.graal.graph.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.java.*;

public interface NodeVisitor extends Visitor {

    public void visit(ArrayLengthNode arrayLengthNode);

    public void visit(BeginNode beginNode);

    public void visit(ConditionalNode conditionalNode);

    public void visit(ConstantNode constantNode);

    public void visit(ConvertNode convertNode);

    public void visit(EndNode endNode);

    public void visit(FloatAddNode floatAddNode);

    public void visit(FloatDivNode floatDivNode);

    public void visit(FloatMulNode floatMulNode);

    public void visit(FloatSubNode floatSubNode);

    public void visit(IfNode ifNode);

    public void visit(IntegerAddNode integerAddNode);

    public void visit(IntegerBelowThanNode integerBelowThanNode);

    public void visit(IntegerDivNode integerDivNode);

    public void visit(IntegerEqualsNode integerEqualsNode);

    public void visit(IntegerLessThanNode integerLessThanNode);

    public void visit(IntegerMulNode integerMulNode);

    public void visit(IntegerRemNode integerRemNode);

    public void visit(IntegerSubNode integerSubNode);

    public void visit(IsNullNode isNullNode);

    public void visit(InvokeWithExceptionNode invokeWithExceptionNode);

    public void visit(LoadIndexedNode loadIndexedNode);

    public void visit(LogicConstantNode logicConstantNode);

    public void visit(LoopBeginNode loopBeginNode);

    public void visit(LoopEndNode loopEndNode);

    public void visit(LoopExitNode loopExitNode);

    public void visit(MergeNode mergeNode);

    public void visit(NormalizeCompareNode normalizeCompareNode);

    public void visit(ReturnNode returnNode);

    public void visit(StartNode startNode);

    public void visit(StoreIndexedNode storeIndexedNode);

    public void visit(Node n);
}
