/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, Arm Limited. All rights reserved.
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

package org.graalvm.compiler.nodes.calc;

import org.graalvm.compiler.core.common.type.ArithmeticOpTable.BinaryOp;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.lir.gen.ArithmeticLIRGeneratorTool;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.spi.Canonicalizable;
import org.graalvm.compiler.nodes.spi.CanonicalizerTool;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Value;

@NodeInfo(shortName = "MinMax")
public abstract class MinMaxNode<OP> extends BinaryArithmeticNode<OP> implements NarrowableArithmeticNode, Canonicalizable.BinaryCommutative<ValueNode> {

    @SuppressWarnings("rawtypes") public static final NodeClass<MinMaxNode> TYPE = NodeClass.create(MinMaxNode.class);

    protected MinMaxNode(NodeClass<? extends BinaryArithmeticNode<OP>> c, BinaryOp<OP> opForStampComputation, ValueNode x, ValueNode y) {
        super(c, opForStampComputation, x, y);
    }

    @Override
    public ValueNode canonical(CanonicalizerTool tool, ValueNode forX, ValueNode forY) {
        ValueNode ret = super.canonical(tool, forX, forY);
        if (ret != this) {
            return ret;
        }

        NodeView view = NodeView.from(tool);
        if (forX.isConstant()) {
            ValueNode result = tryCanonicalizeWithConstantInput(forX, forY);
            if (result != this) {
                return result;
            }
        } else if (forY.isConstant()) {
            ValueNode result = tryCanonicalizeWithConstantInput(forY, forX);
            if (result != this) {
                return result;
            }
        }
        return reassociateMatchedValues(this, ValueNode.isConstantPredicate(), forX, forY, view);
    }

    @Override
    public void generate(NodeLIRBuilderTool nodeValueMap, ArithmeticLIRGeneratorTool gen) {
        Value op1 = nodeValueMap.operand(getX());
        assert op1 != null : getX() + ", this=" + this;
        Value op2 = nodeValueMap.operand(getY());
        if (shouldSwapInputs(nodeValueMap)) {
            Value tmp = op1;
            op1 = op2;
            op2 = tmp;
        }
        if (this instanceof MaxNode) {
            nodeValueMap.setResult(this, gen.emitMathMax(op1, op2));
        } else if (this instanceof MinNode) {
            nodeValueMap.setResult(this, gen.emitMathMin(op1, op2));
        } else {
            throw GraalError.shouldNotReachHere();
        }
    }

    private ValueNode tryCanonicalizeWithConstantInput(ValueNode constantValue, ValueNode otherValue) {
        if (constantValue.isJavaConstant()) {
            JavaConstant constant = constantValue.asJavaConstant();
            JavaKind kind = constant.getJavaKind();
            assert kind == JavaKind.Float || kind == JavaKind.Double;
            if ((kind == JavaKind.Float && Float.isNaN(constant.asFloat())) || (kind == JavaKind.Double && Double.isNaN(constant.asDouble()))) {
                // If either value is NaN, then the result is NaN.

                // NaN is on the left-hand side.
                // veriopt: FloatDoubleLHSMinNaNIsNaN: Math.min(x, y) |-> NaN (when (is_Float x | is_Double x) && is_NaN x)
                // veriopt: FloatDoubleLHSMaxNaNIsNaN: Math.max(x, y) |-> NaN (when (is_Float x | is_Double x) && is_NaN x)

                // NaN is on the right-hand side
                // veriopt: FloatDoubleRHSMinNaNIsNaN: Math.min(x, y) |-> NaN (when (is_Float y | is_Double y) && is_NaN y)
                // veriopt: FloatDoubleRHSMaxNaNIsNaN: Math.max(x, y) |-> NaN (when (is_Float y | is_Double y) && is_NaN y)
                return constantValue;
            } else if (this instanceof MaxNode) {
                if ((kind == JavaKind.Float && constant.asFloat() == Float.NEGATIVE_INFINITY) || (kind == JavaKind.Double && constant.asDouble() == Double.NEGATIVE_INFINITY)) {
                    // Math.max/max(-Infinity, other) == other.

                    // veriopt: FloatLHSMaxOfNegInfinity: Math.max(-inf, y) |-> y
                    // veriopt: FloatRHSMaxOfNegInfinity: Math.max(y, -inf) |-> y
                    return otherValue;
                }
            } else if (this instanceof MinNode) {
                if ((kind == JavaKind.Float && constant.asFloat() == Float.POSITIVE_INFINITY) || (kind == JavaKind.Double && constant.asDouble() == Double.POSITIVE_INFINITY)) {
                    // Math.min/max(Infinity, other) == other.

                    // veriopt: FloatLHSMinOfPosInfinity: Math.min(inf, y) |-> y
                    // veriopt: FloatRHSMinOfPosInfinity: Math.min(y, inf) |-> y
                    return otherValue;
                }
            }
        }
        return this;
    }
}
