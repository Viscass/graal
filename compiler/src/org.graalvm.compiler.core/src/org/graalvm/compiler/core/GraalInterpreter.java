package org.graalvm.compiler.core;


import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.graalvm.compiler.core.common.InterpreterFrame;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.iterators.NodeIterable;
//import org.graalvm.compiler.interpreter.NodeVisitor; todo

import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.ControlSinkNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.LogicNode;
import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.ReturnNode;
import org.graalvm.compiler.nodes.StartNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.AddNode;
import org.graalvm.compiler.nodes.calc.FloatingNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;


// todo ensure we are working with the 1st graph! (initial "After Parsing" graph state)
//  -> to avoid machine specific optimisations.

//todo use evaluationVisitor pattern!
//todo look into extending phases?
//todo look into GraalCompilerTest for some interesting test methods e.g. parseEager()


/**
 * Evaluate all of the floating nodes / input nodes to values then supply these to the fixed nodes.
 * Should have a visit method for almost every single type of possible instantiated *leaf* node
 * e.g. No FixedNode but would have a visit method for IfNode.
 *
 * todo Use annotations to indicate non leaf visit methods
 * todo some form of dynamic casting to actual class type
 * todo use node.getClass() or node.getNodeClass().getJavaClass()) and combine with dispatch map or use interface
 * todo implement specific visitors and visit pattern e.g. visitStartNode()
 *
 */
public class GraalInterpreter{ //implements NodeVisitor {

    private InterpreterFrame currentFrame;
    private Map<Integer, Object> state = new HashMap<>();
    private StructuredGraph currentGraph;

    public Map<Integer, Object> getState(){
        return state;
    }

    public void executeGraph(StructuredGraph graph){
        //todo set up stack and heap
        currentGraph = graph;
        System.out.println("Now executing graph\n");
        currentFrame = new InterpreterFrame(0);

        visit(graph.start());

        System.out.printf("The return value was: %d", (int)state.get(-1));
    }

    // Global / Most generic visit pattern
    // todo could act as a sort of switch for visiting nodes (down casts based on runtime instance type)
    public Object visit(Node node) {
        if (node instanceof ValueNode){
            return visit((ValueNode) node);
        }

        System.err.println("Unhandled visit for: " + node.toString() + " of class " + node.getClass());
        return null;
    }

    public Object visit(ValueNode node){
//        System.out.println("Of type ValueNode, deciding subclass: (Actually)" + node.getClass() + "\n");

        if (node instanceof FixedNode){
            return visit((FixedNode) node);
        }
        else if (node instanceof FloatingNode) {
            return visit((FloatingNode) node);
        }
        System.err.println("Unhandled visit for: " + node.toString() + " of class " + node.getClass());
        return null;
    }

    public Object visit(FloatingNode node){
//        System.out.println("Of type FloatingNode, deciding subclass: (Actually)" + node.getClass() + "\n");
        if (node instanceof ConstantNode){
            return visit((ConstantNode) node);
        }
        else if (node instanceof AddNode){
            return visit((AddNode) node);
        }

        else if (node instanceof ParameterNode){
            return visit((ParameterNode) node);
        }

        System.err.println("Unhandled visit for: " + node.toString() + " of class " + node.getClass());
        return null;
    }

    public Object visit(FixedNode node) {
//        System.out.println("Of type FixedNode, deciding subclass: (Actually)" + node.getClass() + "\n");

        if (node instanceof ControlSinkNode){
            return visit((ControlSinkNode) node);
        } else if (node instanceof LoadFieldNode){
            return visit((LoadFieldNode) node);
        } else if (node instanceof IfNode){
            return visit((IfNode) node);
        }

        System.err.println("Unhandled visit for: " + node.toString() + " of class " + node.getClass());
        return null;
    }

    /**
     * Entry point for most graphs.
     */
    public Object visit(StartNode node) {
        System.out.println("Visiting " + node.getNodeClass().shortName() + "\n");

        return visit(node.next());
    }

    public Object visit(LogicNode node){

        System.out.println("Visiting " + node.getNodeClass().shortName() + "\n");
        System.out.println("Returning True");
        return true;
    }

    public Object visit(IfNode node){
        System.out.println("Visiting " + node.getNodeClass().shortName() + "\n");
        LogicNode condition = node.condition();
        AbstractBeginNode trueSucc = node.trueSuccessor();
        AbstractBeginNode falseSucc = node.falseSuccessor();

        boolean isTrue = (boolean) visit(condition);

        if (isTrue){
            return visit(trueSucc);
        }

        return visit(falseSucc);
    }

    public Object visit(LoadFieldNode node){
        if (state.containsKey(node.id())){
            System.out.printf("Visiting %s - Already evaluated value\n\n", node.getNodeClass().shortName());
            return 1;
        }
        System.out.println("Visiting " + node.getNodeClass().shortName() + "\n");

//        System.out.println("The value of the field node is " + node.field());
        state.put(node.id(),  node.field()); // store value in variable.
//        System.out.println("State is currently " + state);

        return visit(node.next());
    }

    public Object visit(ControlSinkNode node){
        if (node instanceof ReturnNode){
            return visit((ReturnNode) node);
        }
        return null;
    }

    public Object visit(ReturnNode node) {
        System.out.println("Visiting " + node.getNodeClass().shortName() + "\n");
        // todo using -1 as signifier for return -> should use unique node id.
        state.put(-1, visit(node.result()));
        return null;
    }

    public Object visit(AddNode node){
        System.out.println("Visiting " + node.getNodeClass().shortName() + "\n");

        ValueNode nodeX = node.getX();
        ValueNode nodeY = node.getY();
        Object x = visit(nodeX);
        Object y = visit(nodeY);

        int sum = 0;

        if (x != null && y != null){
            int xint = (int)x;
            int yint = (int)y;
            sum = xint + yint;
//            System.out.printf("x + y: %d + %d = %d\n", xint, yint, sum);
        }
        state.put(node.id(), sum);
        return sum;
    }

    public Object visit(ConstantNode node) {
        System.out.println("Visiting " + node.getNodeClass().shortName() + "\n");
        System.out.println("Value was " + node.asConstant().toValueString());
        return node.getValue();
    }

    public Object visit(ParameterNode node){
        System.out.println("Visiting " + node.getNodeClass().shortName() + "\n");
        //todo get the value stored in the parameter node. Consider index position?

        System.out.println("Creation position: " + node.getCreationPosition());
        System.out.println("insertion position: " + node.getInsertionPosition());
        System.out.println("node source position: " + node.getNodeSourcePosition());
        System.out.println("java kind: " + node.getStackKind());
        System.out.println("debug>" + node.getDebugProperties());
        System.out.println("index " + node.index());
        System.out.println("Is node constant?" + node.isJavaConstant());

        //todo access framestate node to extract values?
        NodeIterable<FrameState> out = node.graph().getNodes(FrameState.TYPE);

        for (FrameState fr : out){
            System.out.println("Framestate node" + fr);
        }

        // The index of the current parameter node in the current method
        int index = node.index();

        ResolvedJavaMethod method = node.graph().method();
        ResolvedJavaMethod.Parameter[] parameters = node.graph().method().getParameters();
        System.out.println("The parameters are: " + Arrays.toString(parameters));

        return 5;
//            return paramValue;
    }


    public void outputGraphInfo(StructuredGraph graph){
        System.out.println("Now outputting Graph Info\n-------------------------------------");

        StartNode start = graph.start();

        System.out.println("Predecessors:" + start.cfgPredecessors());
        System.out.println("Successors: " + start.cfgSuccessors());
        System.out.println("The inputs of start are " + start.inputs());
        System.out.println("The successors of start are " + start.successors());

        FixedNode current_node = start.next();
        System.out.println("the next node is " + current_node);

        System.out.println("Get nodes gives");
        for (Node node : graph.getNodes()) {
            System.out.println(node);
        }

        System.out.println("State after is " + start.stateAfter());

        System.out.println("-----------------------------------------------\n\n");
    }
}