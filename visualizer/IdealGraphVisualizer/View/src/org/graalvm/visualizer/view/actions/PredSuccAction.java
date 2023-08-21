/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package org.graalvm.visualizer.view.actions;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import org.graalvm.visualizer.view.DiagramViewModel;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.awt.ActionRegistration;
import org.openide.awt.ActionState;
import org.openide.util.NbBundle;

@NbBundle.Messages({
    "ACTION_PredSuccAction=Semitransparent nodes",
})
@ActionID(category="Diagram", id = PredSuccAction.ID)
@ActionRegistration(displayName =  "#ACTION_PredSuccAction", iconBase = "org/graalvm/visualizer/view/images/predsucc.gif",
        checkedOn = @ActionState(property = "showNodeHull"))
@ActionReferences({
    @ActionReference(path = "NodeGraphViewer/Actions", position = 5500),
    @ActionReference(path = "Menu/View", position = 2150)
})
public final class PredSuccAction implements ActionListener {
    static final String ID = "org.graalvm.visualizer.view.actions.PredSuccAction"; // NOI18N

    private final DiagramViewModel model;
    
    public PredSuccAction(DiagramViewModel model) {
        this.model = model;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        model.setShowNodeHull(!model.getShowNodeHull());
    }
}
