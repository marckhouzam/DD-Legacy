/*******************************************************************************
 * Copyright (c) 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.examples.pda.launch;

import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.concurrent.Sequence;
import org.eclipse.dd.dsf.debug.service.BreakpointsMediator;
import org.eclipse.dd.dsf.debug.service.StepQueueManager;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.examples.pda.service.PDABreakpointAttributeTranslator;
import org.eclipse.dd.examples.pda.service.PDABreakpoints;
import org.eclipse.dd.examples.pda.service.PDACommandControl;
import org.eclipse.dd.examples.pda.service.PDAExpressions;
import org.eclipse.dd.examples.pda.service.PDARegisters;
import org.eclipse.dd.examples.pda.service.PDARunControl;
import org.eclipse.dd.examples.pda.service.PDAStack;

/**
 * The initialization sequence for PDA debugger services.  This sequence contains
 * the series of steps that are executed to properly initialize the PDA-DSF debug
 * session.  If any of the individual steps fail, the initialization will abort.   
 * <p>
 * The order in which services are initialized is important.  Some services depend
 * on other services and they assume that they will be initialized only if those
 * services are active.  Also the service events are prioritized and their priority
 * depends on the order in which the services were initialized.
 * </p>
 */
public class PDAServicesInitSequence extends Sequence {

    Step[] fSteps = new Step[] {
        new Step() 
        { 
            @Override
            public void execute(RequestMonitor requestMonitor) {
                // Create the connection to PDA debugger.
                fCommandControl = new PDACommandControl(fSession, fProgram, fRequestPort, fEventPort);
                fCommandControl.initialize(requestMonitor);
            }
        },
        new Step() { 
            @Override
            public void execute(RequestMonitor requestMonitor) {
                // Start the run control service.
                fRunControl = new PDARunControl(fSession);
                fRunControl.initialize(requestMonitor);
            }
        },
        new Step() { 
            @Override
            public void execute(RequestMonitor requestMonitor) {
                // Start the service to manage step actions.
                new StepQueueManager(fSession).initialize(requestMonitor);
            }
        },
        new Step() { 
            @Override
            public void execute(final RequestMonitor requestMonitor) {
                // Start the low-level breakpoint service 
                new PDABreakpoints(fSession).initialize(new RequestMonitor(getExecutor(), requestMonitor));
            }
        },
        new Step() { 
            @Override
            public void execute(final RequestMonitor requestMonitor) {
                // Create the breakpoint mediator and start tracking PDA breakpoints.

                final BreakpointsMediator bpmService = new BreakpointsMediator(
                    fSession, new PDABreakpointAttributeTranslator());
                bpmService.initialize(new RequestMonitor(getExecutor(), requestMonitor) {
                    @Override
                    protected void handleSuccess() {
                        bpmService.startTrackingBreakpoints(fCommandControl.getVirtualMachineDMContext(), requestMonitor);
                    }
                }); 
            }
        },
        new Step() { 
            @Override
            public void execute(RequestMonitor requestMonitor) {
                // Start the stack service.
                new PDAStack(fSession).initialize(requestMonitor);
            }
        },
        new Step() { 
            @Override
            public void execute(RequestMonitor requestMonitor) {
                // Start the service to track expressions.
                new PDAExpressions(fSession).initialize(requestMonitor);
            }
        },
        new Step() { 
            @Override
            public void execute(RequestMonitor requestMonitor) {
                // Start the service to track expressions.
                new PDARegisters(fSession).initialize(requestMonitor);
            }
        },
        new Step() { 
            @Override
            public void execute(RequestMonitor requestMonitor) {
                fRunControl.resume(fCommandControl.getVirtualMachineDMContext(), requestMonitor);
            }
        },
    };

    // Sequence input parameters, used in initializing services.
    private DsfSession fSession;
    private String fProgram;
    private int fRequestPort;
    private int fEventPort;

    // Service references, initialized when created and used in initializing other services.
    private PDACommandControl fCommandControl;
    private PDARunControl fRunControl;

    public PDAServicesInitSequence(DsfSession session, String program, int requestPort, int eventPort, RequestMonitor rm) 
    {
        super(session.getExecutor(), rm);
        fSession = session;
        fProgram = program;
        fRequestPort = requestPort;
        fEventPort = eventPort;
    }

    @Override
    public Step[] getSteps() {
        return fSteps;
    }
}
