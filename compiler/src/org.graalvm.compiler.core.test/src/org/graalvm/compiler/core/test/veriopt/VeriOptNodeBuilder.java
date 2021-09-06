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
package org.graalvm.compiler.core.test.veriopt;

import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.iterators.NodeIterable;
import org.graalvm.compiler.nodeinfo.Verbosity;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.ValueNodeInterface;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Build an Isabelle representation of a node.
 */
public class VeriOptNodeBuilder {
    private String clazz;
    private String id;
    private List<String> args = new ArrayList<>();
    private String stamp = "IllegalStamp";

    public VeriOptNodeBuilder(Node node) {
        clazz = node.getClass().getSimpleName();
        id = node.toString(Verbosity.Id);
        if (node instanceof ValueNode) {
            stamp = VeriOptStampEncoder.encodeStamp(((ValueNode) node).stamp(NodeView.DEFAULT));
        }
    }

    /**
     * Append a raw argument to the node's arguments.
     *
     * @param argument The argument to be appended
     * @return This builder
     */
    public VeriOptNodeBuilder arg(String argument) {
        args.add(argument);
        return this;
    }

    /**
     * Append an id of a node to the node's arguments.
     *
     * @param node The node whose id to be appended
     * @return This builder
     */
    public VeriOptNodeBuilder id(Node node) {
        return arg(node.toString(Verbosity.Id));
    }

    /**
     * Append an optional id of a node to the node's arguments.
     *
     * @param optional The node whose optional id to be appended (Can be null)
     * @return This builder
     */
    public VeriOptNodeBuilder optId(Node optional) {
        return arg(optional == null ? "None" : "(Some " + id(optional) + ")");
    }

    /**
     * Append an optional id of a node to the node's arguments.
     *
     * @param optional The node whose optional id to be appended (Can be null)
     * @return This builder
     */
    public VeriOptNodeBuilder optIdAsNode(ValueNodeInterface optional) {
        if (optional == null) {
            return arg("None");
        } else {
            return optId(optional.asNode());
        }
    }

    /**
     * Append an list of node ids to the node's arguments.
     *
     * @param nodes The nodes whose id to be appended
     * @return This builder
     */
    public <T extends Node> VeriOptNodeBuilder idList(Iterable<T> nodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        Iterator<T> iter = nodes.iterator();
        while (iter.hasNext()) {
            T n = iter.next();
            if (n == null) {
                throw new IllegalArgumentException("null found in Node list");
            }
            sb.append(n.toString(Verbosity.Id));
            if (iter.hasNext()) {
                sb.append(", ");
            }
        }
        sb.append("]");
        return arg(sb.toString());
    }

    /**
     * Translates an optional list of nodes.
     *
     * @param nodes The nodes whose optional id to be appended
     * @param <T> Type of nodes
     * @return A list of optional ids
     */
    public <T extends Node> VeriOptNodeBuilder optIdList(NodeIterable<T> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return arg("None");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("(Some [");
            Iterator<T> iter = nodes.iterator();
            while (iter.hasNext()) {
                T n = iter.next();
                if (n == null) {
                    sb.append("None");
                } else {
                    sb.append("(Some ").append(n.toString(Verbosity.Id)).append(")");
                }
                if (iter.hasNext()) {
                    sb.append(", ");
                }
            }
            sb.append("])");
            return arg(sb.toString());
        }
    }

    /**
     * Append an value of an object to the node's arguments.
     *
     * @param object The object whose value is to be appended
     * @return This builder
     */
    public VeriOptNodeBuilder value(Object object) {
        return arg(VeriOptValueEncoder.value(object));
    }

    /**
     * Append a field reference to the node's arguments.
     *
     * @param field The field reference to be appended
     * @return This builder
     */
    public VeriOptNodeBuilder fieldRef(ResolvedJavaField field) {
        return arg("''" + field.getDeclaringClass().toClassName() + "::" + field.getName() + "''");
    }

    /**
     * Append a type reference to the node's arguments.
     *
     * @param type The type reference to be appended
     * @return This builder
     */
    public VeriOptNodeBuilder typeRef(ResolvedJavaType type) {
        return arg("''" + type.toClassName() + "''");
    }

    /**
     * Append a method reference to the node's arguments.
     *
     * @param method The method reference to be appended
     * @return This builder
     */
    public VeriOptNodeBuilder methodRef(ResolvedJavaMethod method) {
        return arg("''" + method.format("%H.%n") + method.getSignature().toMethodDescriptor() + "''");
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("\n  (");
        stringBuilder.append(id);
        stringBuilder.append(", (");
        stringBuilder.append(clazz);
        for (String arg : args) {
            stringBuilder.append(" ");
            stringBuilder.append(arg);
        }
        stringBuilder.append("), ");
        stringBuilder.append(stamp);
        stringBuilder.append("),");
        return stringBuilder.toString();
    }
}
