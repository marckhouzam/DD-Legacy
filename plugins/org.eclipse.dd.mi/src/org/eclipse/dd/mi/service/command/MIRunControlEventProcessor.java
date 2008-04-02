/*******************************************************************************
 * Copyright (c) 2006 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson			  - Additional handling of events  	
 *******************************************************************************/
package org.eclipse.dd.mi.service.command;

import java.util.LinkedList;
import java.util.List;

import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerDMContext;
import org.eclipse.dd.dsf.debug.service.command.ICommand;
import org.eclipse.dd.dsf.debug.service.command.ICommandListener;
import org.eclipse.dd.dsf.debug.service.command.ICommandResult;
import org.eclipse.dd.dsf.debug.service.command.ICommandToken;
import org.eclipse.dd.dsf.debug.service.command.IEventListener;
import org.eclipse.dd.dsf.service.DsfServicesTracker;
import org.eclipse.dd.mi.internal.MIPlugin;
import org.eclipse.dd.mi.service.MIRunControl;
import org.eclipse.dd.mi.service.command.commands.MIExecContinue;
import org.eclipse.dd.mi.service.command.commands.MIExecFinish;
import org.eclipse.dd.mi.service.command.commands.MIExecNext;
import org.eclipse.dd.mi.service.command.commands.MIExecNextInstruction;
import org.eclipse.dd.mi.service.command.commands.MIExecReturn;
import org.eclipse.dd.mi.service.command.commands.MIExecStep;
import org.eclipse.dd.mi.service.command.commands.MIExecStepInstruction;
import org.eclipse.dd.mi.service.command.commands.MIExecUntil;
import org.eclipse.dd.mi.service.command.events.MIBreakpointHitEvent;
import org.eclipse.dd.mi.service.command.events.MIEvent;
import org.eclipse.dd.mi.service.command.events.MIFunctionFinishedEvent;
import org.eclipse.dd.mi.service.command.events.MIInferiorExitEvent;
import org.eclipse.dd.mi.service.command.events.MIInferiorSignalExitEvent;
import org.eclipse.dd.mi.service.command.events.MILocationReachedEvent;
import org.eclipse.dd.mi.service.command.events.MIRunningEvent;
import org.eclipse.dd.mi.service.command.events.MISignalEvent;
import org.eclipse.dd.mi.service.command.events.MISteppingRangeEvent;
import org.eclipse.dd.mi.service.command.events.MIStoppedEvent;
import org.eclipse.dd.mi.service.command.events.MIWatchpointScopeEvent;
import org.eclipse.dd.mi.service.command.events.MIWatchpointTriggerEvent;
import org.eclipse.dd.mi.service.command.output.MIConst;
import org.eclipse.dd.mi.service.command.output.MIExecAsyncOutput;
import org.eclipse.dd.mi.service.command.output.MIInfo;
import org.eclipse.dd.mi.service.command.output.MINotifyAsyncOutput;
import org.eclipse.dd.mi.service.command.output.MIOOBRecord;
import org.eclipse.dd.mi.service.command.output.MIOutput;
import org.eclipse.dd.mi.service.command.output.MIResult;
import org.eclipse.dd.mi.service.command.output.MIResultRecord;
import org.eclipse.dd.mi.service.command.output.MIStatusAsyncOutput;
import org.eclipse.dd.mi.service.command.output.MIValue;

/**
 * MI debugger output listener that listens for the parsed MI output, and
 * generates corresponding MI events.  The generated MI events are then
 * received by other services and clients.
 */
public class MIRunControlEventProcessor 
    implements IEventListener, ICommandListener
{
    /**
     * The connection service that this event processor is registered with.
     */
    private final AbstractMIControl fCommandControl;
 
    /**
     * Container context used as the context for the run control events generated
     * by this processor.
     */
    private final IContainerDMContext fContainerDmc; 
    
    /**
     * Services tracker used to retrieve the MIRunControl service.
     */
    private final DsfServicesTracker fServicesTracker;
    
    /**
     * Creates the event processor and registers it as listener with the debugger
     * control.
     * @param connection
     * @param inferior
     */
    public MIRunControlEventProcessor(AbstractMIControl connection, IContainerDMContext containerDmc) {
        fCommandControl = connection;
        fContainerDmc = containerDmc;
        fServicesTracker = new DsfServicesTracker(MIPlugin.getBundleContext(), fCommandControl.getSession().getId());
        connection.addEventListener(this);
        connection.addCommandListener(this);
    }

    /**
     * This processor must be disposed before the control service is un-registered. 
     */
    public void dispose() {
        fCommandControl.removeEventListener(this);        
        fCommandControl.removeCommandListener(this);
        fServicesTracker.dispose();
    }
    
    public void eventReceived(Object output) {
        for (MIOOBRecord oobr : ((MIOutput)output).getMIOOBRecords()) {
            if (oobr instanceof MIExecAsyncOutput) {
            	MIExecAsyncOutput exec = (MIExecAsyncOutput) oobr;
                // Change of state.
                String state = exec.getAsyncClass();
                if ("stopped".equals(state)) { //$NON-NLS-1$
                	// Re-set the thread level to -1 when stopped event is recvd. 
                	// This is to synchronize the state between GDB back-end and AbstractMIControl. 
                	fCommandControl.resetCurrentThreadLevel();
                	
                    List<MIEvent<?>> events = new LinkedList<MIEvent<?>>();
                    MIResult[] results = exec.getMIResults();
                    for (int i = 0; i < results.length; i++) {
                        String var = results[i].getVariable();
                        MIValue val = results[i].getMIValue();
                        if (var.equals("reason")) { //$NON-NLS-1$
                            if (val instanceof MIConst) {
                                String reason = ((MIConst) val).getString();
                                MIEvent<?> e = createEvent(reason, exec);
                                if (e != null) {
                                    events.add(e);
                                    continue;
                                }
                            }
                        }
                    }
    
                    // We were stopped for some unknown reason, for example
                    // GDB for temporary breakpoints will not send the
                    // "reason" ??? still fire a stopped event.
                    if (events.isEmpty()) {
                    	MIEvent<?> e = MIStoppedEvent.parse(
                    	    fServicesTracker.getService(MIRunControl.class), fContainerDmc, exec.getToken(), exec.getMIResults());
                        events.add(e);
                    }
                    for (MIEvent<?> event : events) {
                        fCommandControl.getSession().dispatchEvent(event, fCommandControl.getProperties());
                    }
                }
            } 
            else if (oobr instanceof MIStatusAsyncOutput) {
                // Nothing done .. but what about +download??
            } else if (oobr instanceof MINotifyAsyncOutput) {
                // Nothing
            }        
        }
    }
    
    protected MIEvent<?> createEvent(String reason, MIExecAsyncOutput exec) {
        MIRunControl runControl = fServicesTracker.getService(MIRunControl.class);
        MIEvent<?> event = null;
        if ("breakpoint-hit".equals(reason)) { //$NON-NLS-1$
                event = MIBreakpointHitEvent.parse(runControl, fContainerDmc, exec.getToken(), exec.getMIResults());
        } else if (
            "watchpoint-trigger".equals(reason) //$NON-NLS-1$
                || "read-watchpoint-trigger".equals(reason) //$NON-NLS-1$
                || "access-watchpoint-trigger".equals(reason)) { //$NON-NLS-1$
                event = MIWatchpointTriggerEvent.parse(runControl, fContainerDmc, exec.getToken(), exec.getMIResults());
        } else if ("watchpoint-scope".equals(reason)) { //$NON-NLS-1$
            event = MIWatchpointScopeEvent.parse(runControl, fContainerDmc, exec.getToken(), exec.getMIResults());
        } else if ("end-stepping-range".equals(reason)) { //$NON-NLS-1$
            event = MISteppingRangeEvent.parse(runControl, fContainerDmc, exec.getToken(), exec.getMIResults());
        } else if ("signal-received".equals(reason)) { //$NON-NLS-1$
            event = MISignalEvent.parse(runControl, fContainerDmc, exec.getToken(), exec.getMIResults());
        } else if ("location-reached".equals(reason)) { //$NON-NLS-1$
            event = MILocationReachedEvent.parse(runControl, fContainerDmc, exec.getToken(), exec.getMIResults());
        } else if ("function-finished".equals(reason)) { //$NON-NLS-1$
            event = MIFunctionFinishedEvent.parse(runControl, fContainerDmc, exec.getToken(), exec.getMIResults());
        } else if ("exited-normally".equals(reason) || "exited".equals(reason)) { //$NON-NLS-1$ //$NON-NLS-2$
            event = MIInferiorExitEvent.parse(fCommandControl.getControlDMContext(), exec.getToken(), exec.getMIResults());
        } else if ("exited-signalled".equals(reason)) { //$NON-NLS-1$
            event = MIInferiorSignalExitEvent.parse(fCommandControl.getControlDMContext(), exec.getToken(), exec.getMIResults());
        }
        return event;
    }

    public void commandQueued(ICommandToken token) {
        // Do nothing.
    }
    
    public void commandSent(ICommandToken token) {
        // Do nothing.
    }
    
    public void commandRemoved(ICommandToken token) {
        // Do nothing.
    }
    
    public void commandDone(ICommandToken token, ICommandResult result) {
        ICommand<?> cmd = token.getCommand();
    	MIInfo cmdResult = (MIInfo) result ;
    	MIOutput output =  cmdResult.getMIOutput();
    	MIResultRecord rr = output.getMIResultRecord();
        if (rr != null) {
            int id = rr.getToken();
            // Check if the state changed.
            String state = rr.getResultClass();
            if ("running".equals(state)) { //$NON-NLS-1$
                int type = 0;
                // Check the type of command
                // if it was a step instruction set state stepping
                
                     if (cmd instanceof MIExecNext)            { type = MIRunningEvent.NEXT; }
                else if (cmd instanceof MIExecNextInstruction) { type = MIRunningEvent.NEXTI; }
                else if (cmd instanceof MIExecStep)            { type = MIRunningEvent.STEP; }
                else if (cmd instanceof MIExecStepInstruction) { type = MIRunningEvent.STEPI; }
                else if (cmd instanceof MIExecUntil)           { type = MIRunningEvent.UNTIL; }
                else if (cmd instanceof MIExecFinish)          { type = MIRunningEvent.FINISH; }
                else if (cmd instanceof MIExecReturn)          { type = MIRunningEvent.RETURN; }
                else if (cmd instanceof MIExecContinue)        { type = MIRunningEvent.CONTINUE; }
                else                                              { type = MIRunningEvent.CONTINUE; }
                
                fCommandControl.getSession().dispatchEvent(
                    new MIRunningEvent(fContainerDmc, id, type), fCommandControl.getProperties());
            } else if ("exit".equals(state)) { //$NON-NLS-1$
                // No need to do anything, terminate() will.
                // Send exited?
            } else if ("connected".equals(state)) { //$NON-NLS-1$
            } else if ("error".equals(state)) { //$NON-NLS-1$
            } 
        }
    }
}
