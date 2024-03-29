/*******************************************************************************
 * Copyright (c) 2007, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.ui.viewmodel;

import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IHasChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate;
import org.eclipse.jface.viewers.TreePath;

/** 
 * Helper class implementation of the {@link IHasChildrenUpdate} update object.
 * 
 * @see VMViewerUpdate
 */
@SuppressWarnings("restriction")
public class VMHasChildrenUpdate extends VMViewerUpdate implements IHasChildrenUpdate {

    final private DataRequestMonitor<Boolean> fHasElemsRequestMonitor;
    
    public VMHasChildrenUpdate(IViewerUpdate clientUpdate, DataRequestMonitor<Boolean> rm) {
        super(clientUpdate, rm);
        fHasElemsRequestMonitor = rm;
    }
    
    public VMHasChildrenUpdate(IModelDelta delta, IPresentationContext presentationContext, DataRequestMonitor<Boolean> rm) {
        super(delta, presentationContext, rm);
        fHasElemsRequestMonitor = rm;
    }

    public VMHasChildrenUpdate(TreePath elementPath, Object viewerInput, IPresentationContext presentationContext, DataRequestMonitor<Boolean> rm) {
        super(elementPath, viewerInput, presentationContext, rm);
        fHasElemsRequestMonitor = rm;        
    }

    public void setHasChilren(boolean hasChildren) {
        fHasElemsRequestMonitor.setData(hasChildren);
    }

    @Override
    public String toString() {
        return "VMHasChildrenUpdate: " + getElement(); //$NON-NLS-1$
    }
    
    @Override
    public void done() {
        assert isCanceled() || fHasElemsRequestMonitor.getData() != null || !fHasElemsRequestMonitor.isSuccess();
        super.done();            
    }
}
