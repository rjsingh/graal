package com.edinburgh.parallel.opencl;

import java.lang.reflect.*;

import com.edinburgh.parallel.opencl.phases.*;
import com.oracle.graal.api.meta.*;
import com.oracle.graal.graph.*;
import com.oracle.graal.graph.Node.Verbosity;
import com.oracle.graal.loop.*;
import com.oracle.graal.nodes.*;
import com.oracle.graal.nodes.calc.*;
import com.oracle.graal.nodes.cfg.*;
import com.oracle.graal.nodes.java.*;

/**
 * @author Ranjeet Singh Generates OpenCL code when given Graal IR.
 */
public class OpenCLCodeGen implements NodeVisitor {

    private CodeGenBuffer buffer;
    private SymbolTable table;
    private String kernelName;
    private LoopsData loopsData;
    private ControlFlowGraph cfg;

    public OpenCLCodeGen(boolean commentsEnabled, SymbolTable table) {
        buffer = new CodeGenBuffer(commentsEnabled);
        this.table = table;
    }

    private void addInitCode(StructuredGraph sg) {
        // add 1D dimension access variables.
        for (LocalNode ln : sg.getNodes(LocalNode.class)) {
            if (!ln.kind().isPrimitive() && ln.objectStamp().type().isArray()) {
                String name = table.lookupName(ln);
                buffer.emitString("int " + name + "_dim_1 = 0;");
            }
        }
    }

    private void addLocalVariables(StructuredGraph sg) {
        for (PhiNode phiNode : sg.getNodes(PhiNode.class)) {
            if (phiNode.kind().isPrimitive()) {
                String localVarName = table.newVariable(SymbolTable.PHI);
                table.add(localVarName, phiNode, phiNode.kind());
                buffer.emitString(phiNode.kind().getJavaName() + " " + localVarName + ";");
            }
        }
    }

    public void beginCodeGen(Node start, StructuredGraph sg) {
        // create loops data stuff.
        loopsData = new LoopsData(sg);
        cfg = loopsData.controlFlowGraph();

        // generate function signature.
        buffer.emitString("__kernel void "); // OpenCL header.

        // set the kernel name variable.
        this.kernelName = sg.method().getName() + "Kernel";

        // work out parameter list.
        String fsignature = this.kernelName + "(";

        // LocalNodes are types used by Graal to represent method parameters.
        int paramCount = 0;

        // iterate through local nodes to generate parameter list.
        for (LocalNode ln : sg.getNodes(LocalNode.class)) {
            if (ln.kind().isPrimitive()) {
                fsignature += "__global " + ln.kind().getJavaName() + " *p" + paramCount;
                fsignature += ",";

                // add to symbole table.
                table.add("*p" + paramCount, ln, ln.kind());

                paramCount++;
            } else if (table.lookupArraySize(ln) != null) {
                /***
                 * THIS IS A HACKY WAY OF FINDING OUT IF AN ARRAY IS OF TYPE PRIMATIVE, THERE MUST
                 * BE A BETTER WAY OF DOING THIS, BUT DON'T KNOW HOW TO YET.
                 ***/

                // must find out if the array type is a primative type.
                String type = ln.objectStamp().type().getArrayClass().asExactType().getName();
                // [[?, must get ? so char at last position.
                buffer.emitComment(type);
                Kind type_kind = Kind.fromTypeString("" + type.charAt(type.length() - 1));

                if (type_kind.isPrimitive()) {
                    // if isn't primative but is array and array is of primative type then convert.
                    fsignature += "__global " + type_kind.getJavaName() + "  *p" + paramCount;
                    fsignature += ",";

                    // add to symbole table.
                    table.add("p" + paramCount, ln, type_kind);

                    paramCount++;
                }
            }
        }

        // remove last comma in parameter list.
        if (paramCount > 0) {
            fsignature = fsignature.substring(0, fsignature.length() - 1);
        }

        // add the index data also for any array nodes in the method parameter list.
        for (LocalNode ln : sg.getNodes(LocalNode.class)) {
            if (!ln.kind().isPrimitive() && ln.objectStamp().type().isArray()) {
                String name = table.lookupName(ln);

                // create variable name for holding the dimension info for the flattenend array.
                String newVarName = name + "_index_data";

                // create code.
                fsignature += ",__global int *" + newVarName;

            }
        }

        // close parameter list
        fsignature += ")";

        buffer.emitString(fsignature);

        // add all constants to the symbol table as well.
        // can't do sg.getNodes(ConstantNode.class) again.
        for (Node n : sg.getNodes()) {
            if (n instanceof ConstantNode) {
                ConstantNode cn = (ConstantNode) n;
                if (cn.kind() == Kind.Int) {
                    table.add("" + cn.value.asInt(), cn, Kind.Int);
                } else if (cn.kind() == Kind.Boolean) {
                    table.add("" + cn.value.asBoolean(), cn, Kind.Boolean);
                } else if (cn.kind() == Kind.Long) {
                    table.add("" + cn.value.asLong(), cn, Kind.Long);
                } else if (cn.kind() == Kind.Float) {
                    table.add("" + cn.value.asFloat(), cn, Kind.Float);
                } else if (cn.kind() == Kind.Double) {
                    table.add("" + cn.value.asDouble(), cn, Kind.Double);
                }
            }
        }

        // Now generate body of method.

        buffer.emitString("{");

        buffer.beginBlock();

        // add code at the beginning for the 1st dimension size access information.
        addInitCode(sg);

        // create a variable for all phi nodes in the graph. (PhiNodes represent local variables)
        addLocalVariables(sg);

        // add variables for non array input parameters - this will speed up code execution as we
        // are not fetching all data from main memory can be cached locally into registers.
        for (LocalNode ln : sg.getNodes(LocalNode.class)) {
            if (ln.kind().isPrimitive()) {
                String paramName = table.lookupName(ln);
                String newParamName = table.newVariable(SymbolTable.PARAM_VALUE);

                table.add(newParamName, ln, ln.kind());

                buffer.emitString(ln.kind().getJavaName() + " " + newParamName + " = " + paramName + ";");
            }
        }

        // add required variables for OpenCL.
        buffer.emitString("int gs = get_global_size(0);");
        // buffer.emitString("int gs = 1;");
        start.accept(this);

        buffer.endBlock();

        buffer.emitString("}");
    }

    public String endCodeGen() {
        return buffer.getCode();
    }

    public String getKernelName() {
        return kernelName;
    }

    /*
     * (non-Javadoc) Uses the Java Reflection API to find the right visit() method to call given the
     * Node object. This was done so that I do not have to add an accept method to every Node type.
     * Will eventually change it so that it doesn't use Java Reflection by adding an accept method
     * to every Node type when project is complete.
     * 
     * @see com.oracle.graal.graph.Visitor#dispatch(com.oracle.graal.graph.Node)
     */
    @Override
    public void dispatch(Node n) {
        Method m;
        try {
            m = this.getClass().getMethod("visit", new Class[]{n.getClass()});
            m.invoke(this, n);
        } catch (NoSuchMethodException e) {
            this.visit(n);
        } catch (SecurityException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            // TODO Auto-generated catch block
            System.out.println("Exceptionz: " + n.getClass());
        }
    }

    /**
     * What follows is the visit method implementations for all the Graal StructuredGraph IR Nodes.
     */

    @Override
    public void visit(ArrayLengthNode arrayLengthNode) {
        buffer.emitComment("visited ArrayLengthNode");

        // is the array coming in from the parameter list?
        if (table.lookupArrayAccessInfo(arrayLengthNode) != null) {
            ArrayDepth arrayDepth = table.lookupArrayAccessInfo(arrayLengthNode);
            Node arrayNode = arrayDepth.getNode();
            int dim = arrayDepth.getDimensionAccessedAt();
            String srcArrayName = table.lookupName(arrayNode);
            String newVar = srcArrayName + "_len_dim_" + dim;

            buffer.emitString("int " + newVar + " = " + srcArrayName + "_index_data[" + srcArrayName + "_dim_" + dim + "];");
            table.add(newVar, arrayLengthNode, Kind.Int);

            for (Node succ : arrayLengthNode.cfgSuccessors()) {
                succ.accept(this);
            }
        } else {
            buffer.emitComment("ArrayLengthNode() - not of type LocalNode");
        }
    }

    @Override
    public void visit(BeginNode beginNode) {
        buffer.emitComment("visited BeginNode");
        table.enterScope();
        for (Node succ : beginNode.cfgSuccessors()) {
            succ.accept(this);
        }
        table.exitScope();
    }

    @Override
    public void visit(ConstantNode constantNode) {
    }

    @Override
    public void visit(ConditionalNode conditionalNode) {
        buffer.emitComment("visited ConditionalNode");

        if (!table.exists(conditionalNode.x()))
            conditionalNode.x().accept(this);

        if (!table.exists(conditionalNode.y()))
            conditionalNode.y().accept(this);

        String xVar = table.lookupName(conditionalNode.x());
        String yVar = table.lookupName(conditionalNode.y());

        String condition = conditionalNode.condition().toString();
        String conditionVar = table.newVariable(SymbolTable.CONDITION_VAR);

        table.add(conditionVar, conditionalNode, Kind.Boolean);
        buffer.emitString("bool " + conditionVar + " = " + xVar + " " + condition + " " + yVar);
    }

    @Override
    public void visit(ConvertNode convertNode) {
        buffer.emitComment("visited ConvertNode");

        String varToCast = table.lookupName(convertNode.value());
        String varName = table.newVariable(SymbolTable.CAST);
        String toType = convertNode.opcode.to.getJavaName();

        table.add(varName, convertNode, convertNode.opcode.to);
        buffer.emitString(toType + " " + varName + " = (" + toType + ") " + varToCast + ";");
    }

    @Override
    public void visit(EndNode endNode) {
        buffer.emitComment("visited EndNode");

        for (PhiNode phiNode : endNode.merge().phis()) {
            String phiVar = table.lookupName(phiNode);
            ValueNode vn = phiNode.valueAt(endNode);

            if (!table.exists(vn))
                vn.accept(this);

            String vnVar = table.lookupName(vn);

            buffer.emitString(phiVar + " = " + vnVar + ";");
        }
        for (Node succ : endNode.cfgSuccessors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(FloatAddNode floatAddNode) {
        buffer.emitComment("visited FloatAddNode");

        if (!table.exists(floatAddNode.x()))
            floatAddNode.x().accept(this);

        if (!table.exists(floatAddNode.y()))
            floatAddNode.y().accept(this);

        String xVar = table.lookupName(floatAddNode.x());
        String yVar = table.lookupName(floatAddNode.y());

        // Should output int result = xVar + yVar;
        String resultVar = table.newVariable(SymbolTable.RESULT_VAR);
        table.add(resultVar, floatAddNode, Kind.Float);
        String type = floatAddNode.kind().getJavaName();
        buffer.emitString(type + " " + resultVar + " = " + xVar + " + " + yVar + ";");

        for (Node succ : floatAddNode.cfgSuccessors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(FloatDivNode floatDivNode) {
        buffer.emitComment("visited FloatDivNode");

        if (!table.exists(floatDivNode.x()))
            floatDivNode.x().accept(this);

        if (!table.exists(floatDivNode.y()))
            floatDivNode.y().accept(this);

        String xVar = table.lookupName(floatDivNode.x());
        String yVar = table.lookupName(floatDivNode.y());

        // Should output int result = xVar + yVar;
        String resultVar = table.newVariable(SymbolTable.RESULT_VAR);
        table.add(resultVar, floatDivNode, Kind.Float);
        String type = floatDivNode.kind().getJavaName();
        buffer.emitString(type + " " + resultVar + " = " + xVar + " / " + yVar + ";");

        for (Node succ : floatDivNode.cfgSuccessors()) {
            succ.accept(this);
        }

    }

    @Override
    public void visit(FloatMulNode floatMulNode) {
        buffer.emitComment("visited FloatMulNode");

        if (!table.exists(floatMulNode.x()))
            floatMulNode.x().accept(this);

        if (!table.exists(floatMulNode.y()))
            floatMulNode.y().accept(this);

        String xVar = table.lookupName(floatMulNode.x());
        String yVar = table.lookupName(floatMulNode.y());

        // Should output int result = xVar + yVar;
        String resultVar = table.newVariable(SymbolTable.RESULT_VAR);
        table.add(resultVar, floatMulNode, Kind.Float);
        String type = floatMulNode.kind().getJavaName();
        buffer.emitString(type + " " + resultVar + " = " + xVar + " * " + yVar + ";");

        for (Node succ : floatMulNode.cfgSuccessors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(FloatSubNode floatSubNode) {
        buffer.emitComment("visited FloatSubNode");

        if (!table.exists(floatSubNode.x()))
            floatSubNode.x().accept(this);

        if (!table.exists(floatSubNode.y()))
            floatSubNode.y().accept(this);

        String xVar = table.lookupName(floatSubNode.x());
        String yVar = table.lookupName(floatSubNode.y());

        // Should output int result = xVar + yVar;
        String resultVar = table.newVariable(SymbolTable.RESULT_VAR);
        table.add(resultVar, floatSubNode, floatSubNode.kind());
        String type = floatSubNode.kind().getJavaName();
        buffer.emitString(type + " " + resultVar + " = " + xVar + " - " + yVar + ";");

        for (Node succ : floatSubNode.cfgSuccessors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(IfNode ifNode) {
        buffer.emitComment("visited IfNode");

        ifNode.condition().accept(this);
        String conditionVar = table.lookupName(ifNode.condition());

        // condition
        buffer.emitString("if (" + conditionVar + ")");

        // true block.
        buffer.emitString("{");
        buffer.beginBlock();

        ifNode.trueSuccessor().accept(this);

        buffer.endBlock();
        buffer.emitString("}");

        // else
        buffer.emitString("else");

        // false block.
        buffer.emitString("{");
        buffer.beginBlock();

        ifNode.falseSuccessor().accept(this);

        buffer.endBlock();
        buffer.emitString("}");
    }

    @Override
    public void visit(IntegerAddNode integerAddNode) {
        buffer.emitComment("visited IntegerAddNode");

        if (!table.exists(integerAddNode.x()))
            integerAddNode.x().accept(this);

        if (!table.exists(integerAddNode.y()))
            integerAddNode.y().accept(this);

        String xVar = table.lookupName(integerAddNode.x());
        String yVar = table.lookupName(integerAddNode.y());

        // Should output int result = xVar + yVar;
        String resultVar = table.newVariable(SymbolTable.RESULT_VAR);
        table.add(resultVar, integerAddNode, Kind.Int);
        buffer.emitString("int " + resultVar + " = " + xVar + " + " + yVar + ";");

        for (Node succ : integerAddNode.cfgSuccessors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(IntegerBelowThanNode integerBelowThanNode) {
        buffer.emitComment("visited IntegerBelowThanNode");

        if (!table.exists(integerBelowThanNode.x()))
            integerBelowThanNode.x().accept(this);

        if (!table.exists(integerBelowThanNode.y()))
            integerBelowThanNode.y().accept(this);

        String xVar = table.lookupName(integerBelowThanNode.x());
        String yVar = table.lookupName(integerBelowThanNode.y());

        String conditionVar = table.newVariable(SymbolTable.CONDITION_VAR);
        table.add(conditionVar, integerBelowThanNode, Kind.Boolean);

        buffer.emitString("bool " + conditionVar + " = " + xVar + " < " + yVar + ";");

        for (Node succ : integerBelowThanNode.cfgSuccessors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(IntegerDivNode integerDivNode) {
        buffer.emitComment("visited IntegerDivNode");

        if (!table.exists(integerDivNode.x()))
            integerDivNode.x().accept(this);

        if (!table.exists(integerDivNode.y()))
            integerDivNode.y().accept(this);

        String xVar = table.lookupName(integerDivNode.x());
        String yVar = table.lookupName(integerDivNode.y());

        // Should output int result = xVar + yVar;
        String resultVar = table.newVariable(SymbolTable.RESULT_VAR);
        table.add(resultVar, integerDivNode, Kind.Int);
        buffer.emitString("int " + resultVar + " = " + xVar + " / " + yVar + ";");

        for (Node succ : integerDivNode.cfgSuccessors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(IntegerEqualsNode integerEqualsNode) {
        buffer.emitComment("visited IntegerEqualsNode");

        if (!table.exists(integerEqualsNode.x()))
            integerEqualsNode.x().accept(this);

        if (!table.exists(integerEqualsNode.y()))
            integerEqualsNode.y().accept(this);

        String xVar = table.lookupName(integerEqualsNode.x());
        String yVar = table.lookupName(integerEqualsNode.y());

        // Should output int result = xVar + yVar;
        String varName = table.newVariable(SymbolTable.CONDITION_VAR);
        table.add(varName, integerEqualsNode, Kind.Boolean);
        buffer.emitString("bool " + varName + " = " + xVar + " == " + yVar + ";");

        for (Node succ : integerEqualsNode.cfgSuccessors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(IntegerLessThanNode integerLessThanNode) {
        buffer.emitComment("visited IntegerLessThanNode");

        if (!table.exists(integerLessThanNode.x()))
            integerLessThanNode.x().accept(this);

        if (!table.exists(integerLessThanNode.y()))
            integerLessThanNode.y().accept(this);

        String xVar = table.lookupName(integerLessThanNode.x());
        String yVar = table.lookupName(integerLessThanNode.y());

        String conditionVar = table.newVariable(SymbolTable.CONDITION_VAR);
        table.add(conditionVar, integerLessThanNode, Kind.Boolean);
        buffer.emitString("bool " + conditionVar + " = " + xVar + " < " + yVar + ";");

        for (Node succ : integerLessThanNode.cfgSuccessors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(IntegerMulNode integerMulNode) {
        buffer.emitComment("visited IntegerMulNode");

        if (!table.exists(integerMulNode.x()))
            integerMulNode.x().accept(this);

        if (!table.exists(integerMulNode.y()))
            integerMulNode.y().accept(this);

        String xVar = table.lookupName(integerMulNode.x());
        String yVar = table.lookupName(integerMulNode.y());

        // Should output int result = xVar + yVar;
        String resultVar = table.newVariable(SymbolTable.RESULT_VAR);
        table.add(resultVar, integerMulNode, Kind.Int);

        buffer.emitString("int " + resultVar + " = " + xVar + " * " + yVar + ";");

        for (Node succ : integerMulNode.cfgSuccessors()) {
            succ.accept(this);
        }

    }

    @Override
    public void visit(IntegerRemNode integerRemNode) {
        buffer.emitComment("visited IntegerRemNode");

        if (!table.exists(integerRemNode.x()))
            integerRemNode.x().accept(this);

        if (!table.exists(integerRemNode.y()))
            integerRemNode.y().accept(this);

        String xVar = table.lookupName(integerRemNode.x());
        String yVar = table.lookupName(integerRemNode.y());

        // Should output int result = xVar + yVar;
        String resultVar = table.newVariable(SymbolTable.RESULT_VAR);
        table.add(resultVar, integerRemNode, Kind.Float);
        buffer.emitString("int " + resultVar + " = " + xVar + " % " + yVar + ";");

        for (Node succ : integerRemNode.cfgSuccessors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(IntegerSubNode integerSubNode) {
        buffer.emitComment("visited IntegerSubNode");

        if (!table.exists(integerSubNode.x()))
            integerSubNode.x().accept(this);

        if (!table.exists(integerSubNode.y()))
            integerSubNode.y().accept(this);

        String xVar = table.lookupName(integerSubNode.x());
        String yVar = table.lookupName(integerSubNode.y());

        // Should output int result = xVar + yVar;
        String resultVar = table.newVariable(SymbolTable.RESULT_VAR);
        table.add(resultVar, integerSubNode, Kind.Int);
        buffer.emitString("int " + resultVar + " = " + xVar + " - " + yVar + ";");

        for (Node succ : integerSubNode.cfgSuccessors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(InvokeWithExceptionNode invokeWithExceptionNode) {
        buffer.emitComment("visited InvokeWithExceptionNode");
        buffer.emitComment(invokeWithExceptionNode.methodCallTarget().targetName());
        MethodCallTargetNode mct = invokeWithExceptionNode.methodCallTarget();

        String methodArgs = "";
        for (ValueNode arg : mct.arguments()) {
            if (!table.exists(arg)) {
                arg.accept(this);
            }
            String varName = table.lookupName(arg);
            methodArgs += (varName + ",");
        }

        // remove last comma
        if (methodArgs.length() > 0) {
            methodArgs = methodArgs.substring(0, methodArgs.length() - 1);
        }

        System.out.println(mct.targetName());
        Kind returnType = mct.returnType().getKind();
        String resultVar = table.newVariable(SymbolTable.FUNCTION_RESULT);
        table.add(resultVar, invokeWithExceptionNode, returnType);

        buffer.emitString(returnType.getJavaName() + " " + resultVar + " = " + mct.targetName() + "(" + methodArgs + ");");

        for (Node succ : invokeWithExceptionNode.cfgSuccessors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(LoadIndexedNode loadIndexedNode) {
        buffer.emitComment("visited LoadIndexedNode");

        if (table.lookupArrayAccessInfo(loadIndexedNode) != null) {
            ArrayDepth arrayAccess = table.lookupArrayAccessInfo(loadIndexedNode);
            String variable = table.lookupName(arrayAccess.getNode());
            String indexVar = table.lookupName(loadIndexedNode.index());
            String arrayVar = table.lookupName(loadIndexedNode.array());
            String paramArray = table.lookupName(arrayAccess.getNode());
            int dim = arrayAccess.getDimensionAccessedAt();

            if (dim == table.lookupArrayDimension(arrayAccess.getNode())) {
                String arrayType = table.lookupType(arrayVar).getJavaName();
                String valueVar = table.newVariable(SymbolTable.ARRAY_ELEMENT);
                String code = arrayType + " " + valueVar + " = " + paramArray + "[" + variable + "_index_data[" + variable;
                code += "_dim_" + dim + " + 1] + " + indexVar + "];";
                buffer.emitString(code);
                table.add(valueVar, loadIndexedNode, Kind.Int);
            } else {
                String newVar = variable + "_dim_" + (dim + 1);
                String code = "int " + newVar + " = " + variable + "_index_data[" + variable + "_dim_" + (dim) + " + " + indexVar + " + 1];";
                buffer.emitString(code);
                table.add(newVar, loadIndexedNode, Kind.Int);
            }

            for (Node succ : loadIndexedNode.cfgSuccessors()) {
                succ.accept(this);
            }
        } else {
            buffer.emitComment("LoadIndexedNode() - Object is not an array.");
        }
    }

    @Override
    public void visit(LogicConstantNode logicConstantNode) {
        buffer.emitComment("visited LogicConstantNode");

        String varName = table.newVariable(SymbolTable.CONDITION_VAR);
        table.add(varName, logicConstantNode, Kind.Boolean);

        buffer.emitString("bool " + varName + " = " + logicConstantNode.value + ";");
    }

    @SuppressWarnings("unused")
    @Override
    public void visit(LoopBeginNode loopBeginNode) {
        buffer.emitComment("visited LoopBeginNode");
        // get current loop data for this loop.
        LoopEx thisLoop = loopsData.loop(loopBeginNode);

        // check if this loop has a parent if it has then we don't parallelise this one
        // if this loop doesn't have a parent then this is the parallelisable loop as
        // for now we only parallelise outermost loops.
        boolean isParallelLoop = (thisLoop.parent() == null);

        // generate code for phi node values.
        InductionVariable inductionVariable = null;
        for (PhiNode phi : loopBeginNode.phis()) {
            inductionVariable = thisLoop.getInductionVariables().get(phi);

            // make sure this is the iteration variable by checking for array accesses.
            boolean isIterationVar = false;
            for (Node n : phi.usages()) {
                // if this phi node is used in calls to LoadIndexed or StoreIndexed then it's used
                // for accessing an array which means it's an iteration variable.

                if (n instanceof LoadIndexedNode || n instanceof StoreIndexedNode) {
                    isIterationVar = true;
                    break;
                }
            }
            // now generate code for this node.
            String type = phi.kind().getJavaName();
            String initialValue = table.lookupName(phi.firstValue());
            if (isParallelLoop) {
                // don't use actual initial loop iteration variable value for now though.
                // use get_global_id(0) OpenCL function call.
                initialValue = "get_global_id(0)";
                String localVarName = table.newVariable(SymbolTable.LOOP_VAR);

                table.add(localVarName, phi, phi.kind());
                buffer.emitString(type + " " + localVarName + " = " + initialValue + ";");
            } else {
                String localVarName = table.newVariable(SymbolTable.LOOP_VAR);
                table.add(localVarName, phi, phi.kind());
                buffer.emitString(type + " " + localVarName + " = " + initialValue + ";");
            }
        }

        if (isParallelLoop) {
            String iterVar = table.lookupName(inductionVariable.valueNode());
            // then use the get_global_size stuff.
            buffer.emitStringNoNL("for ( ; ; " + iterVar + " += gs)");
        } else {
            buffer.emitStringNoNL("for ( ; ; )");
        }

        buffer.emitString("");
        buffer.emitString("{");
        buffer.beginBlock();

        for (Node succ : loopBeginNode.cfgSuccessors()) {
            succ.accept(this);
        }

        buffer.endBlock();
        buffer.emitString("}");
    }

    @Override
    public void visit(LoopEndNode loopEndNode) {
        buffer.emitComment("visited LoopEndNode");

        LoopBeginNode lbn = loopEndNode.loopBegin();
        if (loopsData.loop(lbn).parent() != null) {
            for (PhiNode phiNode : loopEndNode.merge().phis()) {
                phiNode.valueAt(loopEndNode).accept(this);
            }
            // print values at the end.
            for (PhiNode phiNode : loopEndNode.merge().phis()) {
                String phiVarName = table.lookupName(phiNode);
                String resultVar = table.lookupName(phiNode.valueAt(loopEndNode));
                buffer.emitString(phiVarName + " = " + resultVar + ";");
            }
        }
        for (Node succ : loopEndNode.successors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(LoopExitNode loopExitNode) {
        buffer.emitComment("visited LoopExitNode");
        buffer.emitComment(loopExitNode.toString(Verbosity.All));
        for (Node succ : loopExitNode.successors()) {
            succ.accept(this);
        }
        buffer.emitString("break;");
    }

    @Override
    public void visit(MergeNode mergeNode) {
        buffer.emitComment("visited MergeNode");
        buffer.emitComment(mergeNode.toString(Verbosity.All));

        for (Node succ : mergeNode.successors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(NormalizeCompareNode normalizeCompareNode) {
        buffer.emitComment("visited NormalizeCompareNode");
        buffer.emitComment(normalizeCompareNode.x().toString(Verbosity.All));
        buffer.emitComment(normalizeCompareNode.y().toString(Verbosity.All));

        if (!table.exists(normalizeCompareNode.x()))
            normalizeCompareNode.x().accept(this);
        if (!table.exists(normalizeCompareNode.y()))
            normalizeCompareNode.y().accept(this);

        String xVar = table.lookupName(normalizeCompareNode.x());
        String yVar = table.lookupName(normalizeCompareNode.y());

        String conditionVar = table.newVariable(SymbolTable.CONDITION_VAR);
        table.add(conditionVar, normalizeCompareNode, Kind.Int);

        // Returns -1, 0, or 1 if either x < y, x == y, or x > y
        // emitted as result = x < y ? -1 : x == y ? 0 : 1

        String code = "int " + conditionVar + " = " + xVar + " < " + yVar + " ? " + " -1 : " + xVar + " == " + yVar;
        code += " ? 0 : 1;";

        buffer.emitString(code);

    }

    @Override
    public void visit(ReturnNode returnNode) {
        buffer.emitComment("visited ReturnNode");
    }

    @Override
    public void visit(StartNode startNode) {
        for (Node succ : startNode.successors()) {
            succ.accept(this);
        }
    }

    @Override
    public void visit(StoreIndexedNode storeIndexedNode) {
        buffer.emitComment("visited StoreIndexedNode");
        buffer.emitComment(storeIndexedNode.toString(Verbosity.All));

        if (table.lookupArrayAccessInfo(storeIndexedNode) != null) {
            ArrayDepth accessInfo = table.lookupArrayAccessInfo(storeIndexedNode);

            // Get parameter array name.
            String arrayName = table.lookupName(accessInfo.getNode());

            // get index in array to store value at.
            String indexVar = table.lookupName(storeIndexedNode.index());

            String storeArray = table.lookupName(storeIndexedNode.array());

            int dim = accessInfo.getDimensionAccessedAt();

            // get value to be stored.
            if (!table.exists(storeIndexedNode.value()))
                storeIndexedNode.value().accept(this);

            String valueVar = table.lookupName(storeIndexedNode.value());
            String code = arrayName + "[" + arrayName + "_index_data[" + arrayName + "_dim_" + dim + " + 1] + " + indexVar + "] = " + valueVar + ";";

            buffer.emitString(code);

            for (Node succ : storeIndexedNode.cfgSuccessors()) {
                succ.accept(this);
            }
        } else {
            buffer.emitComment("StoreIndexedNode() - Array not in list.");
        }
    }

    @Override
    public void visit(IsNullNode isNullNode) {
        // checks if object is null.
        if (table.lookupArrayAccessInfo(isNullNode) != null) {
            ArrayDepth arrayAccess = table.lookupArrayAccessInfo(isNullNode);
            String variable = table.lookupName(arrayAccess.getNode());
            int dim = arrayAccess.getDimensionAccessedAt();
            String conditionVar = table.newVariable(SymbolTable.CONDITION_VAR);
            table.add(conditionVar, isNullNode, Kind.Boolean);
            buffer.emitString("bool " + conditionVar + " = " + variable + "_index_data[" + variable + "_dim_" + dim + "] == -1;");
        } else {
            buffer.emitComment("IsNullNode() - Object is not an array.");
        }
    }

    @Override
    public void visit(Node n) {
        String clazz_name = n.getClass().getName();
        buffer.emitComment("#WARNING: Visit method for class " + clazz_name + " has not been implemented.");
    }
}
