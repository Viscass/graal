package org.graalvm.compiler.core.interpreter;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.PrimitiveConstant;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.debug.interpreter.value.InterpreterValue;
import org.graalvm.compiler.debug.interpreter.value.InterpreterValueArray;
import org.graalvm.compiler.debug.interpreter.value.InterpreterValueFactory;
import org.graalvm.compiler.debug.interpreter.value.InterpreterValueObject;
import org.graalvm.compiler.debug.interpreter.value.InterpreterValuePrimitive;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.AbstractMergeNode;
import org.graalvm.compiler.nodes.CallTargetNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.PhiNode;
import org.graalvm.compiler.nodes.ReturnNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.UnwindNode;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.util.InterpreterState;
import org.graalvm.compiler.phases.tiers.HighTierContext;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.stream.Collectors;

public class GraalInterpreter {
    private final InterpreterStateImpl myState;
    private final InterpreterValueFactory valueFactory;
    private final HighTierContext context;

    public GraalInterpreter(HighTierContext context) {
        this.context = context;
        this.valueFactory = new InterpreterValueFactoryImpl(context);
        this.myState = new InterpreterStateImpl();
    }

    public Object executeGraph(StructuredGraph graph, Object... args) throws InvocationTargetException {
        ArrayList<InterpreterValue> evaluatedParams = new ArrayList<>();
        for (Object arg : args) {
            evaluatedParams.add(valueFactory.createFromObject(arg));
        }

        // TODO: static class initialisation? (<clinit>)

        InterpreterValue returnValue =  myState.interpretGraph(graph, evaluatedParams);

        // TODO: this needs to be implemented in InterpreterValueObject
        Object returnObject = returnValue.asObject();
        if (returnValue.isException()) {
            GraalError.guarantee(returnObject instanceof Exception, "isException returned true but underlying interpreter value is not an Exception object");
            throw new InvocationTargetException((Exception) returnValue.asObject());
        } else {
            return returnObject;
        }
    }

    private final class InterpreterStateImpl implements InterpreterState {
        final Map<Node, InterpreterValue> heap = new HashMap<>();
        final Stack<ActivationRecord> activations = new Stack<>();

        private void checkActivationsNotEmpty() {
            GraalError.guarantee(!activations.isEmpty(), "Activation ");
        }

        void addActivation(List<InterpreterValue> args) {
            activations.add(new ActivationRecord(args));
        }

        private ActivationRecord popActivation() {
            checkActivationsNotEmpty();
            return activations.pop();
        }

        @Override
        public void setHeapValue(Node node, InterpreterValue value) {
            heap.put(node, value);
        }

        @Override
        public InterpreterValue getHeapValue(Node node) {
            GraalError.guarantee(heap.containsKey(node), "Accessing non-existing heap entry for node: " + node.toString());
            return heap.get(node);
        }

        @Override
        public void setNodeLookupValue(Node node, InterpreterValue value) {
            checkActivationsNotEmpty();
            activations.peek().setNodeValue(node, value);
        }

        @Override
        public InterpreterValue getNodeLookupValue(Node node) {
            checkActivationsNotEmpty();
            return activations.peek().getNodeValue(node);
        }

        @Override
        public void setMergeNodeIncomingIndex(AbstractMergeNode node, int index) {
            checkActivationsNotEmpty();
            activations.peek().setMergeIndex(node, index);
        }

        int getMergeIndex(AbstractMergeNode node) {
            checkActivationsNotEmpty();
            return activations.peek().getMergeIndex(node);
        }

        @Override
        public void visitMerge(AbstractMergeNode node) {
            // Gets the index from which this merge node was reached.
            int accessIndex = getMergeIndex(node);
            // Get all associated phi nodes of this merge node
            // Evaluate all associated phi nodes with merge access index as their input
            // store all the INITIAL values (before updating) in local mapping then apply from that
            // to avoid mapping new state when the prev state should have been used
            // e.g. a phi node with data input from a phi node.
            Map<Node, InterpreterValue> prevValues = new HashMap<>();

            // Collecting previous values:
            for (PhiNode phi : node.phis()) {
                InterpreterValue prevVal = this.interpretDataflowNode(node);
                if (prevVal != null) {
                    // Only maps if the evaluation yielded a value
                    // (i.e. not the first time the phi has been evaluated)
                    prevValues.put(phi, prevVal);
                }
            }

            for (PhiNode phi : node.phis()) {
                ValueNode val = phi.valueAt(accessIndex);
                InterpreterValue phiVal;
                if (prevValues.containsKey(val)) {
                    phiVal = prevValues.get(val);
                } else {
                    phiVal = this.interpretDataflowNode(val);
                }

                this.setNodeLookupValue(phi, phiVal);
            }
        }

        @Override
        public InterpreterValue interpretMethod(CallTargetNode target, List<ValueNode> argumentNodes) {
            List<InterpreterValue> evaluatedArgs = argumentNodes.stream()
                    .map(this::interpretDataflowNode)
                    .collect(Collectors.toList());

            StructuredGraph methodGraph = new StructuredGraph.Builder(target.getOptions(),
                    target.getDebug(), StructuredGraph.AllowAssumptions.YES)
                    .method(target.targetMethod())
                    .build();
            context.getGraphBuilderSuite().apply(methodGraph, context);

            return interpretGraph(methodGraph, evaluatedArgs);
        }

        @Override
        public InterpreterValueFactory getRuntimeValueFactory() {
            return valueFactory;
        }

        @Override
        public InterpreterValue interpretDataflowNode(Node node) {
            GraalError.guarantee(node instanceof ValueNode, "Tried to interpret non ValueNode as a dataflow node");
            return ((ValueNode) node).interpretDataFlow(this);
        }

        @Override
        public InterpreterValue loadStaticFieldValue(ResolvedJavaField field) {
            return fieldMap.getOrDefault(field, null);
        }

        @Override
        public void storeStaticFieldValue(ResolvedJavaField field, InterpreterValue value) {
            fieldMap.put(field, value);
        }

        @Override
        public InterpreterValue getParameter(int index) {
            if (index < 0 || index >= activations.peek().evaluatedParams.size()) {
                throw new IllegalArgumentException();
            }
            return activations.peek().evaluatedParams.get(index);
        }

        private InterpreterValue interpretGraph(StructuredGraph graph, List<InterpreterValue> evaluatedParams) {
            addActivation(evaluatedParams);

            // TODO: rethink this method
            loadStaticFields(graph);

            FixedNode next = graph.start();
            InterpreterValue returnVal = null;

            while (next != null) {
                if (next instanceof ReturnNode || next instanceof UnwindNode) {
                    next.interpretControlFlow(myState);
                    returnVal = myState.getNodeLookupValue(next);
                    break;
                }

                next = next.interpretControlFlow(myState);
            }

            popActivation();
            return returnVal;
        }

        private final Map<ResolvedJavaField, InterpreterValue> fieldMap = new HashMap<>();

        private void loadStaticFields(StructuredGraph graph) {
            // Get class of graph's root method. e.g. rootClass.getJavaMirror();
            JavaType hotSpotClassObjectType = graph.asJavaMethod().getDeclaringClass();
            Object hotSpotClassObjectConstant = null;
            try {
                Field classMirror = hotSpotClassObjectType.getClass().getDeclaredField("mirror");
                classMirror.setAccessible(true);
                hotSpotClassObjectConstant = classMirror.get(hotSpotClassObjectType);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                e.printStackTrace();
                GraalError.unimplemented();
                // TODO
            }

            Class<?> actualDeclaringClass = null;
            try {
                Field objectField = hotSpotClassObjectConstant.getClass().getDeclaredField("object");
                objectField.setAccessible(true);
                actualDeclaringClass = (Class<?>) objectField.get(hotSpotClassObjectConstant);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                e.printStackTrace();
                GraalError.unimplemented();
                // TODO
            }

            // TODO: use .getFields()
            assert actualDeclaringClass != null;
            Field[] fields = actualDeclaringClass.getDeclaredFields();

            // Store these fields with their resolvedField types in the field map.
            for (Field currentField : fields) {
                try {
                    currentField.setAccessible(true);
                    ResolvedJavaField resolvedField = context.getMetaAccess().lookupJavaField(currentField);
                    if (resolvedField.isStatic()) {
                        fieldMap.put(resolvedField, valueFactory.createFromObject(currentField.get(null)));
                    }
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                    GraalError.unimplemented();
                    // TODO
                }
            }
        }
    }

    private static final class ActivationRecord {
        private final List<InterpreterValue> evaluatedParams;
        private final Map<Node, InterpreterValue> localState = new HashMap<>();
        private final Map<AbstractMergeNode, Integer> mergeIndexes = new HashMap<>();

        ActivationRecord(List<InterpreterValue> evaluatedParams) {
            this.evaluatedParams = evaluatedParams;
        }

        void setNodeValue(Node node, InterpreterValue value) {
            localState.put(node, value);
        }

        InterpreterValue getNodeValue(Node node) {
            if (!localState.containsKey(node)) {
                throw new IllegalArgumentException();
            }
            return localState.get(node);
        }

        int getMergeIndex(AbstractMergeNode node) {
            if (!mergeIndexes.containsKey(node)) {
                throw new IllegalArgumentException();
            }
            return mergeIndexes.get(node);
        }

        void setMergeIndex(AbstractMergeNode node, int index) {
            mergeIndexes.put(node, index);
        }
    }

    private static class InterpreterValueFactoryImpl implements InterpreterValueFactory {
        private final HighTierContext context;

        private InterpreterValueFactoryImpl(HighTierContext context) {
            this.context = context;
        }

        @Override
        public InterpreterValueObject createObject(ResolvedJavaType type) {
            return new InterpreterValueObject(type, context.getMetaAccessExtensionProvider()::getStorageKind);
        }

        private InterpreterValueArray createArray(ResolvedJavaType componentType, int length, boolean populateWithDefaults) {
            return new InterpreterValueArray(componentType, length,
                    populateWithDefaults ? context.getMetaAccessExtensionProvider().getStorageKind(componentType) : null,
                    populateWithDefaults);
        }

        @Override
        public InterpreterValueArray createArray(ResolvedJavaType componentType, int length) {
            return createArray(componentType, length, true);
        }

        @Override
        public InterpreterValueArray createMultiArray(ResolvedJavaType elementalType, int[] dimensions) {
            // The current logic only makes sense if the type given is the elemental type - that is, the non-array type in the last dimension
            if (!elementalType.getElementalType().equals(elementalType)) {
                throw new IllegalArgumentException();
            }

            // Get the overall type for a multidimensional array with this many dimensions
            ResolvedJavaType overallType = elementalType;
            for (int i = 0; i < dimensions.length; i++) {
                overallType = overallType.getArrayClass();
            }

            return createMultiArray(overallType.getComponentType(), dimensions, 0);
        }

        private InterpreterValueArray createMultiArray(ResolvedJavaType componentType, int[] dimensions, int dimensionIndex) {
            if (dimensionIndex >= dimensions.length || dimensions[dimensionIndex] < 0) {
                throw new IllegalArgumentException();
            }

            if (dimensionIndex == dimensions.length - 1) {
                if (componentType.isArray() || dimensions[dimensionIndex] < 0) {
                    throw new IllegalArgumentException();
                }
                return createArray(componentType, dimensions[dimensionIndex]);
            }

            InterpreterValueArray array = new InterpreterValueArray(componentType, dimensions[dimensionIndex], null, false);
            for (int i = 0; i < dimensions[dimensionIndex]; i++) {
                array.setAtIndex(i, createMultiArray(componentType.getComponentType(), dimensions, dimensionIndex + 1));
            }
            return array;
        }

        @Override
        public InterpreterValue createFromObject(Object value) {
            if (value == null) {
                return InterpreterValue.InterpreterValueNullPointer.INSTANCE;
            }

            PrimitiveConstant unboxed = JavaConstant.forBoxedPrimitive(value);
            if (unboxed != null) {
                return InterpreterValuePrimitive.ofPrimitiveConstant(unboxed);
            }

            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                InterpreterValueArray createdArray = createArray(context.getMetaAccess().lookupJavaType(value.getClass().getComponentType()), length, false);

                for (int i = 0; i < length; i++) {
                    createdArray.setAtIndex(i, createFromObject(Array.get(value, i)));
                }

                return createdArray;
            }

            ResolvedJavaType resolvedType = context.getMetaAccess().lookupJavaType(value.getClass());
            InterpreterValueObject createdObject = createObject(resolvedType);

            for (ResolvedJavaField field : resolvedType.getInstanceFields(true)) {
                // TODO: how to get this field out of object and stick into createdObject
            }

            return createdObject;
        }
    }
}
