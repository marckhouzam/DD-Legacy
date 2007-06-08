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

import org.eclipse.dd.dsf.concurrent.DsfExecutor;
import org.eclipse.dd.dsf.concurrent.Immutable;
import org.eclipse.dd.dsf.debug.service.IRunControl.StepType;
import org.eclipse.dd.dsf.debug.ui.DsfDebugUIPlugin;
import org.eclipse.dd.dsf.service.DsfServicesTracker;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.debug.core.commands.IDebugCommandRequest;
import org.eclipse.debug.core.commands.IEnabledStateRequest;
import org.eclipse.debug.core.commands.IStepIntoHandler;

@Immutable
public class DsfStepIntoCommand implements IStepIntoHandler {

    private final DsfExecutor fExecutor;
    private final DsfServicesTracker fTracker;
    
    public DsfStepIntoCommand(DsfSession session) {
        fExecutor = session.getExecutor();
        fTracker = new DsfServicesTracker(DsfDebugUIPlugin.getBundleContext(), session.getId());
    }    

    public void dispose() {
        fTracker.dispose();
    }
    
    public void canExecute(final IEnabledStateRequest request) {
        if (request.getElements().length != 1) {
            request.setEnabled(false);
            request.done();
            return;
        }
    	
        fExecutor.submit(new DsfCommandRunnable(fTracker, request.getElements()[0], request) { 
            @Override public void doExecute() {
                request.setEnabled(getStepQueueMgr().canEnqueueStep(getContext()));
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
                getStepQueueMgr().enqueueStep(getContext(), StepType.STEP_INTO);
            }
        });
        return true;
    }
}
