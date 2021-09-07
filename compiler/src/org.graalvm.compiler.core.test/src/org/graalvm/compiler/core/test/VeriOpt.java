/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.core.test;

import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.PrimitiveConstant;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.graalvm.compiler.core.test.veriopt.VeriOptDynamicNodeTranslator;
import org.graalvm.compiler.core.test.veriopt.VeriOptNodeBuilder;
import org.graalvm.compiler.core.test.veriopt.VeriOptValueEncoder;
import org.graalvm.compiler.graph.Graph;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.iterators.NodeIterable;
import org.graalvm.compiler.nodes.BeginNode;
import org.graalvm.compiler.nodes.BinaryOpLogicNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.DeoptimizeNode;
import org.graalvm.compiler.nodes.EndNode;
import org.graalvm.compiler.nodes.FixedGuardNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.InvokeNode;
import org.graalvm.compiler.nodes.InvokeWithExceptionNode;
import org.graalvm.compiler.nodes.KillingBeginNode;
import org.graalvm.compiler.nodes.LogicConstantNode;
import org.graalvm.compiler.nodes.LogicNegationNode;
import org.graalvm.compiler.nodes.LoopBeginNode;
import org.graalvm.compiler.nodes.LoopEndNode;
import org.graalvm.compiler.nodes.LoopExitNode;
import org.graalvm.compiler.nodes.MergeNode;
import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.PiNode;
import org.graalvm.compiler.nodes.ReturnNode;
import org.graalvm.compiler.nodes.StartNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.UnwindNode;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.ValueProxyNode;
import org.graalvm.compiler.nodes.calc.BinaryNode;
import org.graalvm.compiler.nodes.calc.ConditionalNode;
import org.graalvm.compiler.nodes.calc.FixedBinaryNode;
import org.graalvm.compiler.nodes.calc.IntegerDivRemNode;
import org.graalvm.compiler.nodes.calc.IsNullNode;
import org.graalvm.compiler.nodes.calc.UnaryNode;
import org.graalvm.compiler.nodes.debug.ControlFlowAnchorNode;
import org.graalvm.compiler.nodes.extended.BoxNode;
import org.graalvm.compiler.nodes.extended.BranchProbabilityNode;
import org.graalvm.compiler.nodes.extended.BytecodeExceptionNode;
import org.graalvm.compiler.nodes.extended.ClassIsArrayNode;
import org.graalvm.compiler.nodes.extended.GetClassNode;
import org.graalvm.compiler.nodes.extended.IntegerSwitchNode;
import org.graalvm.compiler.nodes.extended.MembarNode;
import org.graalvm.compiler.nodes.extended.OpaqueNode;
import org.graalvm.compiler.nodes.extended.RawLoadNode;
import org.graalvm.compiler.nodes.extended.RawStoreNode;
import org.graalvm.compiler.nodes.extended.StateSplitProxyNode;
import org.graalvm.compiler.nodes.extended.UnboxNode;
import org.graalvm.compiler.nodes.java.ArrayLengthNode;
import org.graalvm.compiler.nodes.java.DynamicNewArrayNode;
import org.graalvm.compiler.nodes.java.ExceptionObjectNode;
import org.graalvm.compiler.nodes.java.FinalFieldBarrierNode;
import org.graalvm.compiler.nodes.java.InstanceOfNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.LoadIndexedNode;
import org.graalvm.compiler.nodes.java.MethodCallTargetNode;
import org.graalvm.compiler.nodes.java.MonitorEnterNode;
import org.graalvm.compiler.nodes.java.MonitorExitNode;
import org.graalvm.compiler.nodes.java.MonitorIdNode;
import org.graalvm.compiler.nodes.java.NewArrayNode;
import org.graalvm.compiler.nodes.java.NewInstanceNode;
import org.graalvm.compiler.nodes.java.NewMultiArrayNode;
import org.graalvm.compiler.nodes.java.StoreFieldNode;
import org.graalvm.compiler.nodes.java.StoreIndexedNode;
import org.graalvm.compiler.nodes.java.UnsafeCompareAndSwapNode;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class VeriOpt {
    public static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("uq.debug", "false"));
    public static final boolean ENCODE_FLOAT_STAMPS = Boolean.parseBoolean(System.getProperty("uq.encode_float_stamps", "true"));
    public static final String IRNODES_FILES = System.getProperty("uq.irnodes", "");
    private static String irNodes = null;

    private static HashSet<String> binaryNodes;
    static {
        binaryNodes = new HashSet<>();
        // add just the binary nodes that we currently handle, with x,y fields only.
        binaryNodes.add("AddNode");
        binaryNodes.add("AndNode");
        binaryNodes.add("BinaryMathIntrinsicNode");
        binaryNodes.add("FloatEqualsNode");
        binaryNodes.add("FloatLessThanNode");
        binaryNodes.add("FloatNormalizeCompareNode");
        binaryNodes.add("IntegerBelowNode");
        binaryNodes.add("IntegerEqualsNode");
        binaryNodes.add("IntegerLessThanNode");
        binaryNodes.add("IntegerTestNode");
        binaryNodes.add("LeftShiftNode");
        binaryNodes.add("MulNode");
        binaryNodes.add("ObjectEqualsNode");
        binaryNodes.add("OrNode");
        binaryNodes.add("RightShiftNode");
        binaryNodes.add("ShortCircuitOrNode");
        binaryNodes.add("SubNode");
        binaryNodes.add("UnsignedMulHighNode");
        binaryNodes.add("UnsignedRightShiftNode");
        binaryNodes.add("XorNode");
    }

    private static HashSet<String> unaryNodes;
    static {
        unaryNodes = new HashSet<>();
        // add just the unary nodes that we currently handle, with value fields only.
        unaryNodes.add("AbsNode");
        unaryNodes.add("FloatConvertNode");
        unaryNodes.add("NarrowNode");
        unaryNodes.add("NegateNode");
        unaryNodes.add("NotNode");
        unaryNodes.add("SignExtendNode");
        unaryNodes.add("SqrtNode");
        unaryNodes.add("ReverseBytesNode");
        unaryNodes.add("UnaryMathIntrinsicNode");
        unaryNodes.add("ZeroExtendNode");
    }

    /**
     *
     */
    private static HashSet<String> dynamicNodes;
    static {
        dynamicNodes = new HashSet<>();
        // add just the nodes that we currently handle
        dynamicNodes.add("ArrayCopyNode");
        dynamicNodes.add("AssertionNode");
    }

    private StringBuilder stringBuilder = new StringBuilder();

    /**
     * Dump multiple IRGraphs as a single Program.
     *
     * @param graphs The graphs to dump
     * @return A definition of the graphs as a Program in isabelle syntax, with {name} representing
     *         the name of the graph
     */
    public String dumpProgram(Graph... graphs) {
        stringBuilder.setLength(0);
        stringBuilder.append("definition {name} :: Program where\n");
        stringBuilder.append("  \"{name} = Map.empty (\n");

        for (Graph graph : graphs) {
            String graphName = getGraphName(graph);
            stringBuilder.append("  ''" + graphName + "'' \\<mapsto> irgraph ");
            writeNodeArray(graph);
            stringBuilder.append(",\n");
        }
        stringBuilder.setLength(stringBuilder.length() - 2); // remove last comma

        stringBuilder.append("\n  )\"");
        return stringBuilder.toString();
    }

    /**
     * Get a reasonable name for a graph.
     *
     * @param graph The graph to get a name for
     * @return Either Graph.name, StructuredGraph.method().getName(), or null
     */
    public String getGraphName(Graph graph) {
        if (graph.name != null) {
            return graph.name;
        }

        if (graph instanceof StructuredGraph && ((StructuredGraph) graph).method() != null) {
            return formatMethod(((StructuredGraph) graph).method());
        }

        return null;
    }

    /**
     * Dump a single IRGraph.
     *
     * @param graph The graph to dump
     * @return A definition of the graph as an IRGraph in isabelle syntax, with {name} representing
     *         the name of the graph
     */
    public String dumpGraph(Graph graph) {
        stringBuilder.setLength(0);

        stringBuilder.append("definition {name} :: IRGraph where");
        stringBuilder.append("  \"{name} = irgraph ");
        writeNodeArray(graph);

        stringBuilder.append("\"");
        return stringBuilder.toString();
    }

    /**
     * Dump a single IRGraph with the specified name.
     *
     * @param graph The graph to dump
     * @param name The name to give the graph
     * @return A definition of the graph as an IRGraph in isabelle syntax
     */
    public String dumpGraph(Graph graph, String name) {
        return dumpGraph(graph).replace("{name}", name);
    }

    private static HashSet<Class<? extends Node>> nodesGeneratedCodeFor = new HashSet<>();

    /**
     * Generate code that could be used to translate a node.
     *
     * @param node The node to generate translation code for
     */
    private static void generateCode(Node node) {
        if (nodesGeneratedCodeFor.add(node.getClass())) {
            System.out.printf("} else if (node instanceof %s) {\n", node.getClass().getSimpleName());
            System.out.printf("    %s n = (%s) node;\n", node.getClass().getSimpleName(), node.getClass().getSimpleName());
            System.out.print("    nodeDef(n");

            Class<?> clazz = node.getClass();
            while (Node.class.isAssignableFrom(clazz)) {
                for (Field field : clazz.getDeclaredFields()) {
                    // boolean isNode = Node.class.isAssignableFrom(field.getType());
                    boolean isNodeList = NodeIterable.class.isAssignableFrom(field.getType());
                    String method = null;
                    if (field.getAnnotation(Node.Input.class) != null || field.getAnnotation(Node.Successor.class) != null) {
                        if (isNodeList) {
                            method = "idList";
                        } else {
                            method = "id";
                        }
                    } else if (field.getAnnotation(Node.OptionalInput.class) != null) {
                        if (isNodeList) {
                            method = "optIdList";
                        } else {
                            method = "optId";
                        }
                    }
                    if (method != null) {
                        System.out.printf(", %s(n.%s())", method, field.getName());
                    }
                }

                clazz = clazz.getSuperclass();
            }

            System.out.println(");");
        }
    }

    /**
     * Returns the [node...] string.
     *
     * @param graph The graph to write
     */
    private void writeNodeArray(Graph graph) {
        stringBuilder.append("[");
        for (Node node : graph.getNodes()) {
            VeriOptNodeBuilder builder = new VeriOptNodeBuilder(node);
            if (!isInIrNodes(node)) {
                throw new IllegalArgumentException("node type " + node + " (" + node.getClass().getSimpleName() + ") is not in -Duq.irnodes=file.");
            } else if (node instanceof ArrayLengthNode) {
                ArrayLengthNode n = (ArrayLengthNode) node;
                builder.id(n.array()).id(n.next());
            } else if (node instanceof BeginNode) {
                BeginNode n = (BeginNode) node;
                builder.id(n.next());
            } else if (node instanceof BoxNode) {
                BoxNode n = (BoxNode) node;
                builder.id(n.getValue()).optIdAsNode(n.getLastLocationAccess()).id(n.next());
            } else if (node instanceof BytecodeExceptionNode) {
                BytecodeExceptionNode n = (BytecodeExceptionNode) node;
                builder.idList(n.getArguments()).optId(n.stateAfter()).id(n.next());
            } else if (node instanceof ClassIsArrayNode) {
                ClassIsArrayNode n = (ClassIsArrayNode) node;
                builder.id(n.getValue());
            } else if (node instanceof ConditionalNode) {
                ConditionalNode n = (ConditionalNode) node;
                builder.id(n.condition()).id(n.trueValue()).id(n.falseValue());
            } else if (node instanceof ConstantNode) {
                ConstantNode n = (ConstantNode) node;
                Constant c = n.getValue();
                if (c instanceof PrimitiveConstant) {
                    builder.value(((PrimitiveConstant) c).asBoxedPrimitive());
                } else if (c instanceof JavaConstant && ((JavaConstant) c).isNull()) {
                    builder.optId(null);
                } else {
                    // builder."(constant type " + c + " (" + c.getClass().getName() + ") not
                    // implemented yet)");
                    throw new IllegalArgumentException("constant type " + c + " (" + c.getClass().getName() + ") not implemented yet.");
                }
            } else if (node instanceof ControlFlowAnchorNode) {
                ControlFlowAnchorNode n = (ControlFlowAnchorNode) node;
                builder.id(n.next());
            } else if (node instanceof DeoptimizeNode) {
                DeoptimizeNode n = (DeoptimizeNode) node;
                builder.optId(n.stateBefore());
            } else if (node instanceof DynamicNewArrayNode) {
                DynamicNewArrayNode n = (DynamicNewArrayNode) node;
                builder.id(n.getElementType()).id(n.length()).optId(n.getVoidClass()).optId(n.stateBefore()).id(n.next());
            } else if (node instanceof ExceptionObjectNode) {
                ExceptionObjectNode n = (ExceptionObjectNode) node;
                builder.optId(n.stateAfter()).id(n.next());
            } else if (node instanceof FinalFieldBarrierNode) {
                FinalFieldBarrierNode n = (FinalFieldBarrierNode) node;
                builder.optId(n.getValue()).id(n.next());
            } else if (node instanceof FixedGuardNode) {
                FixedGuardNode n = (FixedGuardNode) node;
                builder.id(n.condition()).optId(n.stateBefore()).id(n.next());
            } else if (node instanceof FrameState) {
                FrameState n = (FrameState) node;
                if (n.monitorIds() == null) {
                    builder.arg("[]");
                } else {
                    builder.idList(n.monitorIds());
                }
                builder.optId(n.outerFrameState()).optIdList(n.values()).optIdList(n.virtualObjectMappings());
            } else if (node instanceof GetClassNode) {
                GetClassNode n = (GetClassNode) node;
                builder.id(n.getObject());
            } else if (node instanceof IfNode) {
                IfNode n = (IfNode) node;
                builder.id(n.condition()).id(n.trueSuccessor()).id(n.falseSuccessor());
            } else if (node instanceof InstanceOfNode) {
                InstanceOfNode n = (InstanceOfNode) node;
                builder.optIdAsNode(n.getAnchor()).id(n.getValue());
            } else if (node instanceof IntegerDivRemNode) {
                // SignedDivNode, SignedRemNode, UnsignedDivNode, UnsignedRemNode
                IntegerDivRemNode n = (IntegerDivRemNode) node;
                builder.id(n).id(n.getX()).id(n.getY()).optIdAsNode(n.getZeroCheck()).optId(n.stateBefore()).id(n.next());
            } else if (node instanceof IntegerSwitchNode) {
                IntegerSwitchNode n = (IntegerSwitchNode) node;
                builder.idList(n.successors()).id(n.value());
            } else if (node instanceof InvokeNode) {
                InvokeNode n = (InvokeNode) node;
                builder.id(n).id(n.callTarget()).optId(n.classInit()).optId(n.stateDuring()).optId(n.stateAfter()).id(n.next());
            } else if (node instanceof InvokeWithExceptionNode) {
                InvokeWithExceptionNode n = (InvokeWithExceptionNode) node;
                builder.id(n).id(n.callTarget()).optId(n.classInit()).optId(n.stateDuring()).optId(n.stateAfter()).id(n.next()).id(n.exceptionEdge());
            } else if (node instanceof IsNullNode) {
                IsNullNode n = (IsNullNode) node;
                builder.id(n.getValue());
            } else if (node instanceof KillingBeginNode) {
                KillingBeginNode n = (KillingBeginNode) node;
                builder.id(n.next());
            } else if (node instanceof LoadFieldNode) {
                LoadFieldNode n = (LoadFieldNode) node;
                builder.id(n).fieldRef(n.field()).optId(n.object()).id(n.next());
            } else if (node instanceof LoadIndexedNode) {
                LoadIndexedNode n = (LoadIndexedNode) node;
                builder.id(n.index()).optIdAsNode(n.getBoundsCheck()).id(n.array()).id(n.next());
            } else if (node instanceof LogicConstantNode) {
                LogicConstantNode n = (LogicConstantNode) node;
                builder.value(n.getValue());
            } else if (node instanceof LogicNegationNode) {
                LogicNegationNode n = (LogicNegationNode) node;
                builder.id(n.getValue());
            } else if (node instanceof LoopBeginNode) {
                LoopBeginNode n = (LoopBeginNode) node;
                ArrayList<Node> endNodes = new ArrayList<>();
                n.cfgPredecessors().forEach(endNodes::add);
                n.loopEnds().forEach(endNodes::add);
                builder.idList(endNodes).optIdAsNode(n.getOverflowGuard()).optId(n.stateAfter()).id(n.next());
            } else if (node instanceof LoopEndNode) {
                LoopEndNode n = (LoopEndNode) node;
                builder.id(n.loopBegin());
            } else if (node instanceof LoopExitNode) {
                LoopExitNode n = (LoopExitNode) node;
                builder.id(n.loopBegin()).optId(n.stateAfter()).id(n.next());
            } else if (node instanceof MergeNode) {
                MergeNode n = (MergeNode) node;
                builder.idList(n.cfgPredecessors()).optId(n.stateAfter()).id(n.next());
            } else if (node instanceof MembarNode) {
                MembarNode n = (MembarNode) node;
                builder.id(n.next());
            } else if (node instanceof MethodCallTargetNode) {
                MethodCallTargetNode n = (MethodCallTargetNode) node;
                builder.methodRef(n.targetMethod()).idList(n.arguments());
            } else if (node instanceof MonitorEnterNode) {
                MonitorEnterNode n = (MonitorEnterNode) node;
                builder.optId(n.stateBefore()).id(n.object()).id(n.getMonitorId()).optId(n.getObjectData()).optId(n.stateAfter()).id(n.next());
            } else if (node instanceof MonitorExitNode) {
                MonitorExitNode n = (MonitorExitNode) node;
                builder.optId(n.stateBefore()).id(n.object()).id(n.getMonitorId()).optId(n.getObjectData()).optId(n.stateAfter()).id(n.next());
            } else if (node instanceof NewArrayNode) {
                NewArrayNode n = (NewArrayNode) node;
                builder.id(n.length()).optId(n.stateBefore()).id(n.next());
            } else if (node instanceof NewMultiArrayNode) {
                NewMultiArrayNode n = (NewMultiArrayNode) node;
                builder.id(n).typeRef(n.type()).idList(n.dimensions()).optId(n.stateBefore()).id(n.next());
            } else if (node instanceof NewInstanceNode) {
                NewInstanceNode n = (NewInstanceNode) node;
                builder.id(n).typeRef(n.instanceClass()).optId(n.stateBefore()).id(n.next());
            } else if (node instanceof OpaqueNode) {
                OpaqueNode n = (OpaqueNode) node;
                builder.id(n.getValue());
            } else if (node instanceof ParameterNode) {
                ParameterNode n = (ParameterNode) node;
                builder.arg(Integer.toString(n.index()));
            } else if (node instanceof PiNode) {
                PiNode n = (PiNode) node;
                builder.id(n.object()).optIdAsNode(n.getGuard());
            } else if (node instanceof RawLoadNode) {
                RawLoadNode n = (RawLoadNode) node;
                builder.id(n.object()).id(n.offset()).id(n.next());
            } else if (node instanceof RawStoreNode) {
                RawStoreNode n = (RawStoreNode) node;
                builder.id(n.value()).optId(n.stateAfter()).id(n.object()).id(n.offset()).id(n.next());
            } else if (node instanceof ReturnNode) {
                ReturnNode n = (ReturnNode) node;
                builder.optId(n.result()).optId(n.getMemoryMap());
            } else if (node instanceof StartNode) {
                StartNode n = (StartNode) node;
                builder.optId(n.stateAfter()).id(n.next());
            } else if (node instanceof StateSplitProxyNode) {
                StateSplitProxyNode n = (StateSplitProxyNode) node;
                builder.optId(n.stateAfter()).optId(n.object()).id(n.next());
            } else if (node instanceof StoreFieldNode) {
                StoreFieldNode n = (StoreFieldNode) node;
                builder.id(n).fieldRef(n.field()).id(n.value()).optId(n.stateAfter()).optId(n.object()).id(n.next());
            } else if (node instanceof StoreIndexedNode) {
                StoreIndexedNode n = (StoreIndexedNode) node;
                builder.optIdAsNode(n.getStoreCheck()).id(n.value()).optId(n.stateAfter()).id(n.index()).optIdAsNode(n.getBoundsCheck()).id(n.array()).id(n.next());
            } else if (node instanceof UnboxNode) {
                UnboxNode n = (UnboxNode) node;
                builder.id(n.getValue()).optIdAsNode(n.getLastLocationAccess()).id(n.next());
            } else if (node instanceof UnsafeCompareAndSwapNode) {
                UnsafeCompareAndSwapNode n = (UnsafeCompareAndSwapNode) node;
                builder.id(n.object()).id(n.offset()).id(n.expected()).id(n.newValue()).optId(n.stateAfter()).id(n.next());
            } else if (node instanceof UnwindNode) {
                UnwindNode n = (UnwindNode) node;
                builder.id(n.exception());
            } else if (node instanceof ValuePhiNode) {
                ValuePhiNode n = (ValuePhiNode) node;
                builder.id(n).idList(n.values()).id(n.merge());
            } else if (node instanceof ValueProxyNode) {
                ValueProxyNode n = (ValueProxyNode) node;
                builder.id(n.value()).id(n.proxyPoint());
            } else if (node instanceof BranchProbabilityNode) {
                // Skip, we don't need this node
                continue;
            } else if (node instanceof BinaryNode && binaryNodes.contains(node.getClass().getSimpleName())) {
                BinaryNode n = (BinaryNode) node;
                builder.id(n.getX()).id(n.getY());
            } else if (node instanceof BinaryOpLogicNode && binaryNodes.contains(node.getClass().getSimpleName())) {
                BinaryOpLogicNode n = (BinaryOpLogicNode) node;
                builder.id(n.getX()).id(n.getY());
            } else if (node instanceof FixedBinaryNode && binaryNodes.contains(node.getClass().getSimpleName())) {
                FixedBinaryNode n = (FixedBinaryNode) node;
                builder.id(n.getX()).id(n.getY());
            } else if (node instanceof UnaryNode && unaryNodes.contains(node.getClass().getSimpleName())) {
                UnaryNode n = (UnaryNode) node;
                builder.id(n.getValue());
            } else if (dynamicNodes.contains(node.getClass().getSimpleName())) {
                // Dynamically produce this node
                VeriOptDynamicNodeTranslator.generateNode(node, builder);
            } else if (!(node instanceof MonitorIdNode) && !(node instanceof EndNode)) {
                generateCode(node);
                throw new IllegalArgumentException("node type " + node + " (" + node.getClass().getSimpleName() + ") not implemented yet.");
            }
            stringBuilder.append(builder);
        }
        stringBuilder.setLength(stringBuilder.length() - 1); // remove last comma
        stringBuilder.append("\n  ]");
    }

    public String checkResult(Object obj, String id) {
        Map<String, String> fields = new HashMap<>();
        StringBuilder check = new StringBuilder();
        String sep = "";

        if (obj.getClass().isArray()) {
            throw new IllegalArgumentException("unsupported checkResult type: " + obj.getClass().getName());
        }

        getFieldsRecursively(obj, obj.getClass(), fields, "");

        for (Map.Entry<String, String> field : fields.entrySet()) {
            check.append(sep);
            check.append("h_load_field ''");
            check.append(field.getKey());
            check.append("'' x h = ");
            check.append(field.getValue());

            sep = " \\<and> ";
        }

        return String.format("fun check_result_%s :: \"Value \\<Rightarrow> FieldRefHeap \\<Rightarrow> bool\" where\n" + "  \"check_result_%s (ObjRef x) h = (%s)\" |\n" +
                        "  \"check_result_%s _ _ = False\"\n", id, id, check.toString(), id);
    }

    /**
     * Lists all public and private fields for a class and any super classes, and their values for
     * the specified object.
     *
     * @param object The object to retrieve the value for
     * @param clazz The class to retrieve the fields for
     */
    private void getFieldsRecursively(Object object, Class<?> clazz, Map<String, String> fields, String prefix) {
        if (clazz.getName().equals("java.lang.Object") || clazz.getName().equals("java.lang.Class")) {
            // Ignore these classes (openjdk 8 doesn't like them)
            return;
        }

        System.out.println("Class: " + clazz.getName());
        // Add this class' fields
        for (Field field : clazz.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                try {
                    field.setAccessible(true); // Let us get the value of private fields
                } catch (RuntimeException ignored) {
                }
                try {
                    Object value = field.get(object);
                    String name = clazz.getName() + "::" + field.getName();
                    if (value == null) {
                        fields.put(name, "None");
                    } else if (!(value instanceof Number) && !(value instanceof String) && !(value instanceof Boolean)) {
                        getFieldsRecursively(value, value.getClass(), fields, name + ".");
                    } else {
                        fields.put(name, VeriOptValueEncoder.value(value));
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }

        // Add the super class' fields
        if (clazz.getSuperclass() != null) {
            getFieldsRecursively(object, clazz.getSuperclass(), fields, prefix);
        }
    }

    public String valueList(Object[] args) {
        if (args.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (Object obj : args) {
            sb.append(VeriOptValueEncoder.value(obj));
            sb.append(", ");
        }
        sb.setLength(sb.length() - 2); // remove last separator
        sb.append("]");
        return sb.toString();
    }

    public static String formatMethod(ResolvedJavaMethod method) {
        return method.format("%H.%n") + method.getSignature().toMethodDescriptor();
    }

    private static boolean isInIrNodes(Node node) {
        if (irNodes == null) {
            // Load the IRNodes for the first time
            if (IRNODES_FILES.isEmpty()) {
                // File not specified, leave empty
                irNodes = "";
            } else {
                try {
                    irNodes = new String(Files.readAllBytes(new File(IRNODES_FILES).toPath()), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    // Not a file, leave empty
                    irNodes = "";
                }
            }
        }

        if (irNodes.isEmpty()) {
            // No IRNodes specified, skip this step
            return true;
        }

        String name = node.getClass().getSimpleName();

        // Simply check if the name is mentioned in the file (case-sensitive)
        return irNodes.contains(name);
    }
}
