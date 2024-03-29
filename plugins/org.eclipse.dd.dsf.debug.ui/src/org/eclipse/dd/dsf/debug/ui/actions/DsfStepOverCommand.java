/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
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
import org.eclipse.dd.dsf.concurrent.ImmediateExecutor;
import org.eclipse.dd.dsf.concurrent.Immutable;
import org.eclipse.dd.dsf.debug.internal.ui.DsfDebugUIPlugin;
import org.eclipse.dd.dsf.debug.service.IRunControl.StepType;
import org.eclipse.dd.dsf.service.DsfServicesTracker;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.debug.core.commands.IDebugCommandRequest;
import org.eclipse.debug.core.commands.IEnabledStateRequest;
import org.eclipse.debug.core.commands.IStepOverHandler;

@Immutable
public class DsfStepOverCommand implements IStepOverHandler {

    private final DsfExecutor fExecutor;
    private final DsfServicesTracker fTracker;
	private final DsfSteppingModeTarget fSteppingMode;
    
    public DsfStepOverCommand(DsfSession session, DsfSteppingModeTarget steppingMode) {
        fExecutor = session.getExecutor();
        fTracker = new DsfServicesTracker(DsfDebugUIPlugin.getBundleContext(), session.getId());
        fSteppingMode = steppingMode;
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
            final StepType stepType= getStepType();
            @Override public void doExecute() {
            	getStepQueueManager().canEnqueueStep(
                    getContext(), stepType,
                    new DataRequestMonitor<Boolean>(ImmediateExecutor.getInstance(), null) {
                        @Override
                        protected void handleCompleted() {
                            request.setEnabled(isSuccess() && getData());
                            request.done();
                        }
                    });
            }
        });
    }
    
	public boolean execute(final IDebugCommandRequest request) {
        if (request.getElements().length != 1) {
            request.done();
            return false;
        }
        
        final StepType stepType= getStepType();
        fExecutor.submit(new DsfCommandRunnable(fTracker, request.getElements()[0], request) {
            @Override public void doExecute() {
            	getStepQueueManager().enqueueStep(getContext(), stepType);
            }
        });
        return true;
    }

    /**
	 * @return the currently active step type
	 */
	protected final StepType getStepType() {
		boolean instructionSteppingEnabled= fSteppingMode != null && fSteppingMode.isInstructionSteppingEnabled();
		return instructionSteppingEnabled ? StepType.INSTRUCTION_STEP_OVER : StepType.STEP_OVER;
	}

}
