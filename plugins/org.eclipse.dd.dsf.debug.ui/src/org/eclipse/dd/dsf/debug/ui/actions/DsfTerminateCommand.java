/*******************************************************************************
 * Copyright (c) 2006 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.ui.actions;

import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DsfExecutor;
import org.eclipse.dd.dsf.concurrent.DsfRunnable;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.debug.service.INativeProcesses;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.dsf.debug.ui.DsfDebugUIPlugin;
import org.eclipse.dd.dsf.service.DsfServicesTracker;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.dsf.ui.viewmodel.dm.AbstractDMVMLayoutNode;
import org.eclipse.dd.dsf.ui.viewmodel.dm.AbstractDMVMLayoutNode.DMVMContext;
import org.eclipse.debug.core.commands.IDebugCommandRequest;
import org.eclipse.debug.core.commands.IEnabledStateRequest;
import org.eclipse.debug.core.commands.ITerminateHandler;

public class DsfTerminateCommand implements ITerminateHandler {
    private final DsfExecutor fExecutor;
    private final DsfServicesTracker fTracker;
    
    public DsfTerminateCommand(DsfSession session) {
        fExecutor = session.getExecutor();
        fTracker = new DsfServicesTracker(DsfDebugUIPlugin.getBundleContext(), session.getId());
    }    

    public void dispose() {
        fTracker.dispose();
    }

    // Run control may not be avilable after a connection is terminated and shut down.
    public void canExecute(final IEnabledStateRequest request) {
        if (request.getElements().length != 1 || 
            !(request.getElements()[0] instanceof DMVMContext) ) 
        {
            request.setEnabled(false);
            request.done();
            return;
        }

        // Javac doesn't like the cast to "(AbstractDMVMLayoutNode<?>.DMVMContext)" need to use the 
        // construct below and suppress warnings.
        @SuppressWarnings("unchecked") 
        AbstractDMVMLayoutNode<?>.DMVMContext vmc = (AbstractDMVMLayoutNode.DMVMContext)request.getElements()[0];
        final IExecutionDMContext dmc = DMContexts.getAncestorOfType(vmc.getDMC(), IExecutionDMContext.class);
        if (dmc == null) {
            request.setEnabled(false);
            request.done();
            return;
        }            
        
        fExecutor.execute(
            new DsfRunnable() { 
                public void run() {
                    // Get the processes service and the exec context.
                    INativeProcesses processes = fTracker.getService(INativeProcesses.class);
                    if (processes == null || dmc == null) {  
                        // Context or service already invalid.
                        request.done();
                    } else {
                        // Check the teriminate.
                        processes.canTerminate(
                            processes.getProcessForDebugContext(dmc), 
                            new DataRequestMonitor<Boolean>(fExecutor, null) { 
                                @Override
                                public void handleCompleted() {
                                    request.setEnabled(getData());
                                    request.done();
                                }
                            });
                    }
                }
            });
    }

    public boolean execute(final IDebugCommandRequest request) {
        if (request.getElements().length != 1) {
            request.done();
            return false;
        }

        fExecutor.submit(new DsfCommandRunnable(fTracker, request.getElements()[0], request) { 
            @Override public void doExecute() {
                getProcesses().terminate(
                    getProcesses().getProcessForDebugContext(getContext()), new RequestMonitor(fExecutor, null));
             }
        });
        return false;
    }
    
}
