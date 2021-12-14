/*
 * Copyright (c) 2016, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.pointsto;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugHandlersFactory;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.word.WordBase;

import com.oracle.graal.pointsto.constraints.UnsupportedFeatureException;
import com.oracle.graal.pointsto.meta.AnalysisField;
import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.util.AnalysisError;
import com.oracle.graal.pointsto.util.CompletionExecutor;
import com.oracle.graal.pointsto.util.CompletionExecutor.DebugContextRunnable;

import jdk.vm.ci.code.BytecodePosition;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;

/**
 * Provides functionality for scanning constant objects.
 *
 * The scanning is done in parallel. The set of visited elements is a special data structure whose
 * structure can be reused over multiple scanning iterations to save CPU resources. (For details
 * {@link ReusableSet}).
 */
public abstract class ObjectScanner {

    protected final BigBang bb;
    private final ReusableSet scannedObjects;
    private final CompletionExecutor executor;
    private final Deque<WorklistEntry> worklist;
    private final ObjectScanningObserver scanningObserver;

    public ObjectScanner(BigBang bb, CompletionExecutor executor, ReusableSet scannedObjects, ObjectScanningObserver scanningObserver) {
        this.bb = bb;
        this.scanningObserver = scanningObserver;
        if (executor != null) {
            this.executor = executor;
            this.worklist = null;
        } else {
            this.executor = null;
            this.worklist = new ConcurrentLinkedDeque<>();
        }
        this.scannedObjects = scannedObjects;
    }

    public void scanBootImageHeapRoots(Comparator<AnalysisField> fieldComparator, Comparator<BytecodePosition> embeddedRootComparator) {
        // scan the original roots
        // the original roots are all the static fields, of object type, that were accessed
        Collection<AnalysisField> fields = bb.getUniverse().getFields();
        if (fieldComparator != null) {
            ArrayList<AnalysisField> fieldsList = new ArrayList<>(fields);
            fieldsList.sort(fieldComparator);
            fields = fieldsList;
        }
        for (AnalysisField field : fields) {
            if (Modifier.isStatic(field.getModifiers()) && field.getJavaKind() == JavaKind.Object && field.isRead()) {
                execute(() -> scanRootField(field));
            }
        }

        // scan the constant nodes
        Map<JavaConstant, BytecodePosition> embeddedRoots = bb.getUniverse().getEmbeddedRoots();
        if (embeddedRootComparator != null) {
            embeddedRoots.entrySet().stream().sorted(Map.Entry.comparingByValue(embeddedRootComparator))
                            .forEach(entry -> execute(() -> scanEmbeddedRoot(entry.getKey(), entry.getValue())));
        } else {
            embeddedRoots.forEach((key, value) -> execute(() -> scanEmbeddedRoot(key, value)));
        }

        finish();
    }

    private void execute(Runnable runnable) {
        if (executor != null) {
            executor.execute((ObjectScannerRunnable) debug -> runnable.run());
        } else {
            runnable.run();
        }
    }

    private interface ObjectScannerRunnable extends DebugContextRunnable {
        @Override
        default DebugContext getDebug(OptionValues options, List<DebugHandlersFactory> factories) {
            return DebugContext.disabled(null);
        }
    }

    private void scanEmbeddedRoot(JavaConstant root, BytecodePosition position) {
        AnalysisMethod method = (AnalysisMethod) position.getMethod();
        try {
            scanConstant(root, new MethodScan(method, position));
        } catch (UnsupportedFeatureException ex) {
            bb.getUnsupportedFeatures().addMessage(method.format("%H.%n(%p)"), method, ex.getMessage(), null, ex);
        }
    }

    /**
     * Scans the value of a root field.
     *
     * @param field the scanned root field
     */
    protected final void scanRootField(AnalysisField field) {
        scanField(field, null, null);
    }

    /**
     * Scans the value of a field giving a receiver object.
     *
     * @param field the scanned field
     * @param receiver the receiver object
     * @param previous reference to the work list entry containing parent object
     */
    protected final void scanField(AnalysisField field, JavaConstant receiver, WorklistEntry previous) {
        ScanReason reason = new FieldScan(field);
        try {
            JavaConstant fieldValue = bb.getConstantReflectionProvider().readFieldValue(field, receiver);

            if (fieldValue == null) {
                StringBuilder backtrace = new StringBuilder();
                buildObjectBacktrace(reason, previous, backtrace);
                throw AnalysisError.shouldNotReachHere("Could not find field " + field.format("%H.%n") +
                                (receiver == null ? "" : " on " + constantType(bb, receiver).toJavaName()) +
                                System.lineSeparator() + backtrace);
            }

            if (fieldValue.getJavaKind() == JavaKind.Object && bb.getHostVM().isRelocatedPointer(constantAsObject(bb, fieldValue))) {
                scanningObserver.forRelocatedPointerFieldValue(receiver, field, fieldValue);
            } else if (fieldValue.isNull()) {
                scanningObserver.forNullFieldValue(receiver, field);
            } else if (fieldValue.getJavaKind() == JavaKind.Object) {
                /* Scan the field value. */
                scanConstant(fieldValue, reason, previous);
                /* Process the field value. */
                scanningObserver.forNonNullFieldValue(receiver, field, fieldValue);
            }

        } catch (UnsupportedFeatureException ex) {
            unsupportedFeature(field.format("%H.%n"), ex.getMessage(), reason, previous);
        }
    }

    /**
     * Scans constant arrays, one element at the time.
     *
     * @param array the array to be scanned
     * @param previous reference to the work list entry containing parent object
     */
    protected final void scanArray(JavaConstant array, WorklistEntry previous) {

        Object valueObj = constantAsObject(bb, array);
        AnalysisType arrayType = analysisType(bb, valueObj);
        assert valueObj instanceof Object[];

        ScanReason reason = new ArrayScan(arrayType);
        Object[] arrayObject = (Object[]) valueObj;
        for (int idx = 0; idx < arrayObject.length; idx++) {
            Object e = arrayObject[idx];
            try {
                if (e == null) {
                    scanningObserver.forNullArrayElement(array, arrayType, idx);
                } else {
                    Object element = bb.getUniverse().replaceObject(e);
                    JavaConstant elementConstant = bb.getSnippetReflectionProvider().forObject(element);
                    AnalysisType elementType = analysisType(bb, element);

                    /* Scan the array element. */
                    scanConstant(elementConstant, reason, previous);
                    /* Process the array element. */
                    scanningObserver.forNonNullArrayElement(array, arrayType, elementConstant, elementType, idx);
                }
            } catch (UnsupportedFeatureException ex) {
                unsupportedFeature(arrayType.toJavaName(true), ex.getMessage(), reason, previous);
            }
        }
    }

    public final void scanConstant(JavaConstant value, ScanReason reason) {
        scanConstant(value, reason, null);
    }

    public final void scanConstant(JavaConstant value, ScanReason reason, WorklistEntry previous) {
        Object valueObj = constantAsObject(bb, value);
        if (valueObj == null || valueObj instanceof WordBase) {
            return;
        }
        if (!bb.scanningPolicy().scanConstant(bb, value)) {
            analysisType(bb, valueObj).registerAsInHeap();
            return;
        }
        if (scannedObjects.putAndAcquire(valueObj) == null) {
            try {
                scanningObserver.forScannedConstant(value, reason);
            } finally {
                scannedObjects.release(valueObj);
                WorklistEntry worklistEntry = new WorklistEntry(previous, value, reason);
                if (executor != null) {
                    executor.execute((ObjectScannerRunnable) debug -> doScan(worklistEntry));
                } else {
                    worklist.push(worklistEntry);
                }
            }
        }
    }

    private void unsupportedFeature(String key, String message, ScanReason reason, WorklistEntry entry) {
        StringBuilder objectBacktrace = new StringBuilder();
        AnalysisMethod method = buildObjectBacktrace(reason, entry, objectBacktrace);
        bb.getUnsupportedFeatures().addMessage(key, method, message, objectBacktrace.toString());
    }

    private AnalysisMethod buildObjectBacktrace(ScanReason reason, WorklistEntry entry, StringBuilder objectBacktrace) {
        WorklistEntry cur = entry;
        objectBacktrace.append("Object was reached by ").append(System.lineSeparator());
        objectBacktrace.append('\t').append(asString(reason));
        ScanReason rootReason = null;
        while (cur != null) {
            objectBacktrace.append(System.lineSeparator());
            objectBacktrace.append("\t\t").append("constant ").append(asString(cur.constant)).append(" reached by ").append(System.lineSeparator());
            objectBacktrace.append('\t').append(asString(cur.reason));
            rootReason = cur.reason;
            cur = cur.previous;
        }
        if (rootReason instanceof MethodScan) {
            /* The root constant was found during scanning of 'method'. */
            return ((MethodScan) rootReason).method;
        }
        /* The root constant was not found during method scanning. */
        return null;
    }

    String asString(ScanReason reason) {
        if (reason instanceof FieldScan) {
            FieldScan fieldScan = (FieldScan) reason;
            if (fieldScan.field.isStatic()) {
                return "reading field " + reason;
            } else {
                /* Instance field scans must have a receiver, hence the 'of'. */
                return "reading field " + reason + " of";
            }
        } else if (reason instanceof MethodScan) {
            return "scanning method " + reason;
        } else if (reason instanceof ArrayScan) {
            return "indexing into array";
        } else {
            return reason.toString();
        }
    }

    private String asString(JavaConstant constant) {
        Object obj = constantAsObject(bb, constant);
        return obj.getClass().getTypeName() + '@' + Integer.toHexString(System.identityHashCode(obj));
    }

    /**
     * Processes one constant entry. If the constant has an instance class then it scans its fields,
     * using the constant as a receiver. If the constant has an array class then it scans the array
     * element constants.
     */
    private void doScan(WorklistEntry entry) {
        Object valueObj = constantAsObject(bb, entry.constant);

        try {
            AnalysisType type = analysisType(bb, valueObj);
            type.registerAsReachable();

            if (type.isInstanceClass()) {
                /* Scan constant's instance fields. */
                for (AnalysisField field : type.getInstanceFields(true)) {
                    if (field.getJavaKind() == JavaKind.Object && field.isRead()) {
                        assert !Modifier.isStatic(field.getModifiers());
                        scanField(field, entry.constant, entry);
                    }
                }
            } else if (type.isArray() && bb.getProviders().getWordTypes().asKind(type.getComponentType()) == JavaKind.Object) {
                /* Scan the array elements. */
                scanArray(entry.constant, entry);
            }
        } catch (UnsupportedFeatureException ex) {
            unsupportedFeature("", ex.getMessage(), entry.reason, entry.previous);
        }
    }

    /**
     * Process all consequences for scanned fields. This is done in parallel. Buckets of fields are
     * emitted into the {@code exec}, to mitigate the calling overhead.
     *
     * Processing fields can issue new fields to be scanned so we always add the check for workitems
     * at the end of the worklist.
     */
    protected void finish() {
        if (executor == null) {
            while (!worklist.isEmpty()) {
                int size = worklist.size();
                for (int i = 0; i < size; i++) {
                    doScan(worklist.remove());
                }
            }
        }
    }

    protected static AnalysisType analysisType(BigBang bb, Object constant) {
        return bb.getMetaAccess().lookupJavaType(constant.getClass());
    }

    protected static AnalysisType constantType(BigBang bb, JavaConstant constant) {
        return bb.getMetaAccess().lookupJavaType(constant);
    }

    protected static Object constantAsObject(BigBang bb, JavaConstant constant) {
        return bb.getSnippetReflectionProvider().asObject(Object.class, constant);
    }

    static class WorklistEntry {
        /** The previously processed entry. */
        private final WorklistEntry previous;
        /** The constant to be scanned. */
        private final JavaConstant constant;
        /**
         * The reason this constant was scanned, i.e., either reached from a method scan, from a
         * static field, from an instance field resolved on another constant, or from a constant
         * array indexing.
         */
        private final ScanReason reason;

        WorklistEntry(WorklistEntry previous, JavaConstant constant, ScanReason reason) {
            this.previous = previous;
            this.constant = constant;
            this.reason = reason;
        }

        public WorklistEntry getPrevious() {
            return previous;
        }

        public JavaConstant getConstant() {
            return constant;
        }

        public ScanReason getReason() {
            return reason;
        }
    }

    public interface ScanReason {
        OtherReason HUB = new OtherReason("Hub");
    }

    static class OtherReason implements ScanReason {
        final String reason;

        OtherReason(String reason) {
            this.reason = reason;
        }

        @Override
        public String toString() {
            return reason;
        }
    }

    protected static class FieldScan implements ScanReason {
        final AnalysisField field;

        FieldScan(AnalysisField field) {
            this.field = field;
        }

        public AnalysisField getField() {
            return field;
        }

        @Override
        public String toString() {
            return field.format("%H.%n");
        }
    }

    static class ArrayScan implements ScanReason {
        final AnalysisType arrayType;

        ArrayScan(AnalysisType arrayType) {
            this.arrayType = arrayType;
        }

        @Override
        public String toString() {
            return arrayType.toJavaName(true);
        }
    }

    protected static class MethodScan implements ScanReason {
        final AnalysisMethod method;
        final BytecodePosition sourcePosition;

        MethodScan(AnalysisMethod method, BytecodePosition nodeSourcePosition) {
            this.method = method;
            this.sourcePosition = nodeSourcePosition;
        }

        public AnalysisMethod getMethod() {
            return method;
        }

        @Override
        public String toString() {
            return sourcePosition == null ? method.format("%H.%n(%p)") : method.asStackTraceElement(sourcePosition.getBCI()).toString();
        }
    }

    /**
     * This datastructure keeps track if an object was already put or not atomically. It takes
     * advantage of the fact that each typeflow iteration adds more objects to the set but never
     * removes elements. Since insertions into maps are expensive we keep the map around over
     * multiple iterations and only update the AtomicInteger sequence number after each iteration.
     *
     * Furthermore it also serializes on the object put until the method release is called with this
     * object. So each object goes through two states:
     * <li>In flight: counter = sequence - 1
     * <li>Commited: counter = sequence
     *
     * If the object is in state in flight, all other calls with this object to putAndAcquire will
     * block until release with the object is called.
     */
    public static final class ReusableSet {
        /**
         * The storage of atomic integers. During analysis the constant count for rather large
         * programs such as the JS interpreter are 90k objects. Hence we use 64k as a good start.
         */
        private final IdentityHashMap<Object, AtomicInteger> store = new IdentityHashMap<>(65536);
        private int sequence = 0;

        public Object putAndAcquire(Object object) {
            IdentityHashMap<Object, AtomicInteger> map = this.store;
            AtomicInteger i = map.get(object);
            int seq = this.sequence;
            int inflightSequence = seq - 1;
            while (true) {
                if (i != null) {
                    int current = i.get();
                    if (current == seq) {
                        return object; // Found and is already released
                    } else {
                        if (current != inflightSequence && i.compareAndSet(current, inflightSequence)) {
                            return null; // We have successfully acquired
                        } else { // Someone else has acquired
                            while (i.get() != seq) { // Wait until released
                                Thread.yield();
                            }
                            return object; // Object has been released
                        }
                    }
                } else {
                    AtomicInteger newSequence = new AtomicInteger(inflightSequence);
                    synchronized (map) {
                        i = map.putIfAbsent(object, newSequence);
                        if (i == null) {
                            return null;
                        } else {
                            continue;
                        }
                    }
                }
            }
        }

        public void release(Object o) {
            IdentityHashMap<Object, AtomicInteger> map = this.store;
            AtomicInteger i = map.get(o);
            if (i == null) {
                // We have missed a value likely someone else has updated the map at the same time.
                // Now synchronize
                synchronized (map) {
                    i = map.get(o);
                }
            }
            i.set(sequence);
        }

        public void reset() {
            sequence += 2;
        }
    }
}
