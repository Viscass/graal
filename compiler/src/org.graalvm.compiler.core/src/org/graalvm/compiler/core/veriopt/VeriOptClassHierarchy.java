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
package org.graalvm.compiler.core.veriopt;

import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.java.MethodCallTargetNode;
import org.graalvm.compiler.nodes.java.NewInstanceNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;

public class VeriOptClassHierarchy {

    private static final HashSet<String> classesIncluded = new HashSet<>();
    private static final File outputFile = new File("_CLASS_HIERARCHY.test");
    private static String seperator = "";
    private static final VeriOptGraphCache veriOptGraphCache = new VeriOptGraphCache(null);

    // Prepare class hierarchy file
    static {
        // Remove old file
        if (outputFile.isFile()) {
            Date date = new Date(outputFile.lastModified());
            outputFile.renameTo(new File("_CLASS_HIERARCHY_" + new SimpleDateFormat("yyyy-MM-dd_HH:mm:ss").format(date) + ".test"));
        }

        // Write header info
        try {
            Files.write(outputFile.toPath(), ("definition class_hierarchy :: Program where\n" + "  \"class_hierarchy = Map.empty (").getBytes(),
                            StandardOpenOption.APPEND, StandardOpenOption.CREATE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Close class hierarchy file
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.write(outputFile.toPath(), ("\n  )\"\n").getBytes(),
                                StandardOpenOption.APPEND, StandardOpenOption.CREATE);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }));
    }

    /**
     * Return true if methods of this class will be included in the class hierarchy.
     *
     * @param className The name of the class to determine whether the methods will be included
     * @return true if the methods will be included, false otherwise
     */
    public static boolean areClassMethodsInHeirachy(String className) {
        return VeriOpt.USE_CLASS_HIERARCHY && (className.startsWith("java.") || className.startsWith("sun."));
    }

    public static void processClass(ResolvedJavaType type) {
        if (type != null && classesIncluded.add(type.toClassName())) {
            System.out.println("Adding class " + type.toClassName() + " to hierarchy");

            if (areClassMethodsInHeirachy(type.toClassName())) {
                processMethods(type);
            }

            processClass(type.getSuperclass());
        }
    }

    private static void processMethods(ResolvedJavaType type) {
        for (ResolvedJavaMethod method : type.getDeclaredMethods()) {
            processMethod(method);
        }
    }

    private static void processMethod(ResolvedJavaMethod method) {
        byte[] code = method.getCode();
        if (code != null) {
            StructuredGraph graph = veriOptGraphCache.getGraph(method);

            for (Node node : graph.getNodes()) {
                if (node instanceof NewInstanceNode) {
                    processClass(((NewInstanceNode) node).instanceClass());
                }

                if (node instanceof MethodCallTargetNode) {
                    processClass(((MethodCallTargetNode) node).targetMethod().getDeclaringClass());
                }
            }

            dumpGraph(graph, VeriOpt.formatMethod(method));
        }
    }

    private static void dumpGraph(StructuredGraph graph, String name) {
        try {
            String nodeArray = veriOptGraphCache.getNodeArray(graph);

            if (nodeArray != null) {
                String toWrite = seperator + "\n" + "''" + name + "'' \\<mapsto> irgraph " + nodeArray;

                Files.write(outputFile.toPath(), toWrite.getBytes(), StandardOpenOption.APPEND, StandardOpenOption.CREATE);

                seperator = ",";
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
