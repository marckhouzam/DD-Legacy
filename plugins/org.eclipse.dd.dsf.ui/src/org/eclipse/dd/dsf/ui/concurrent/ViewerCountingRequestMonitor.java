/*******************************************************************************
 * Copyright (c) 2007 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.ui.concurrent;

import java.util.concurrent.Executor;

import org.eclipse.dd.dsf.concurrent.CountingRequestMonitor;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate;

/**
 * Counting multi data request monitor that takes a <code>IViewerUpdate</code> 
 * as a parent. If the IViewerUpdate is canceled, this request monitor becomes 
 * canceled as well. 
 * 
 * @see IViewerUpdate.
 */
@SuppressWarnings("restriction")
public class ViewerCountingRequestMonitor extends CountingRequestMonitor {

    private final IViewerUpdate fUpdate;
    public ViewerCountingRequestMonitor(Executor executor, IViewerUpdate update) {
        super(executor, null);
        fUpdate = update;
    }
    
    @Override
    public synchronized boolean isCanceled() { 
        return fUpdate.isCanceled() || super.isCanceled();
    }
    
    @Override
    protected void handleOK() {
        fUpdate.done();
    }

    @Override
    protected void handleError() {
        fUpdate.setStatus(getStatus());
        fUpdate.done();
    }
    
    @Override
    protected void handleCancel() {
        fUpdate.setStatus(getStatus());
        fUpdate.done();
    }
}