/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson 		  - Modified for additional features in DSF Reference implementation
 *     Ericsson           - New version for 7_0
 *******************************************************************************/
package org.eclipse.dd.gdb.internal.provisional.service.command;

import java.io.IOException;
import java.util.Hashtable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.cdt.debug.core.ICDTLaunchConfigurationConstants;
import org.eclipse.cdt.utils.pty.PTY;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DsfRunnable;
import org.eclipse.dd.dsf.concurrent.IDsfStatusConstants;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.concurrent.Sequence;
import org.eclipse.dd.dsf.datamodel.AbstractDMEvent;
import org.eclipse.dd.dsf.debug.service.IProcesses.IProcessDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerDMContext;
import org.eclipse.dd.dsf.debug.service.command.ICommandControl;
import org.eclipse.dd.dsf.debug.service.command.ICommandControlService;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfServicesTracker;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.gdb.internal.GdbPlugin;
import org.eclipse.dd.gdb.internal.provisional.launching.GdbLaunch;
import org.eclipse.dd.gdb.internal.provisional.service.IGDBBackend;
import org.eclipse.dd.gdb.internal.provisional.service.SessionType;
import org.eclipse.dd.mi.service.IMIBackend;
import org.eclipse.dd.mi.service.IMIProcesses;
import org.eclipse.dd.mi.service.MIProcesses;
import org.eclipse.dd.mi.service.IMIBackend.BackendStateChangedEvent;
import org.eclipse.dd.mi.service.command.AbstractCLIProcess;
import org.eclipse.dd.mi.service.command.AbstractMIControl;
import org.eclipse.dd.mi.service.command.CLIEventProcessor_7_0;
import org.eclipse.dd.mi.service.command.MIBackendCLIProcess;
import org.eclipse.dd.mi.service.command.MIControlDMContext;
import org.eclipse.dd.mi.service.command.MIInferiorProcess;
import org.eclipse.dd.mi.service.command.MIRunControlEventProcessor_7_0;
import org.eclipse.dd.mi.service.command.commands.MIBreakInsert;
import org.eclipse.dd.mi.service.command.commands.MICommand;
import org.eclipse.dd.mi.service.command.commands.MIExecContinue;
import org.eclipse.dd.mi.service.command.commands.MIExecRun;
import org.eclipse.dd.mi.service.command.commands.MIGDBExit;
import org.eclipse.dd.mi.service.command.commands.MIInferiorTTYSet;
import org.eclipse.dd.mi.service.command.output.MIBreakInsertInfo;
import org.eclipse.dd.mi.service.command.output.MIInfo;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.osgi.framework.BundleContext;

/**
 * GDB Debugger control implementation.  This implementation extends the 
 * base MI control implementation to provide the GDB-specific debugger 
 * features.  This includes:<br>
 * - CLI console support,<br>
 * - inferior process status tracking.<br>
 */
public class GDBControl_7_0 extends AbstractMIControl implements IGDBControl {

    /**
     * Event indicating that the back end process has started.
     */
    private static class GDBControlInitializedDMEvent extends AbstractDMEvent<ICommandControlDMContext> 
        implements ICommandControlInitializedDMEvent
    {
        public GDBControlInitializedDMEvent(ICommandControlDMContext context) {
            super(context);
        }
    }
    
    /**
     * Event indicating that the CommandControl (back end process) has terminated.
     */
    private static class GDBControlShutdownDMEvent extends AbstractDMEvent<ICommandControlDMContext> 
        implements ICommandControlShutdownDMEvent
    {
        public GDBControlShutdownDMEvent(ICommandControlDMContext context) {
            super(context);
        }
    }

    private GDBControlDMContext fControlDmc;

    private IGDBBackend fMIBackend;

    private int fConnected = 0;
    
    private MIRunControlEventProcessor_7_0 fMIEventProcessor;
    private CLIEventProcessor_7_0 fCLICommandProcessor;
    private AbstractCLIProcess fCLIProcess;
    private MIInferiorProcess fInferiorProcess = null;
    
    private PTY fPty;

    public GDBControl_7_0(DsfSession session, ILaunchConfiguration config) { 
        super(session, true);
    }

    @Override
    protected BundleContext getBundleContext() {
        return GdbPlugin.getBundleContext();
    }
    
    @Override
    public void initialize(final RequestMonitor requestMonitor) {
        super.initialize( new RequestMonitor(getExecutor(), requestMonitor) {
            @Override
            protected void handleSuccess() {
                doInitialize(requestMonitor);
            }
        });
    }

    public void doInitialize(final RequestMonitor requestMonitor) {
        fMIBackend = getServicesTracker().getService(IGDBBackend.class);
    	
        // getId uses the MIBackend service, which is why we must wait until we
        // have it, before we can create this context.
        fControlDmc = new GDBControlDMContext(getSession().getId(), getId()); 

        final Sequence.Step[] initializeSteps = new Sequence.Step[] {
                new CommandMonitoringStep(InitializationShutdownStep.Direction.INITIALIZING),
                new InferiorInputOutputInitStep(InitializationShutdownStep.Direction.INITIALIZING),
                new CommandProcessorsStep(InitializationShutdownStep.Direction.INITIALIZING),
                new RegisterStep(InitializationShutdownStep.Direction.INITIALIZING),
            };

        Sequence startupSequence = new Sequence(getExecutor(), requestMonitor) {
            @Override public Step[] getSteps() { return initializeSteps; }
        };
        getExecutor().execute(startupSequence);
    }

    @Override
    public void shutdown(final RequestMonitor requestMonitor) {
        final Sequence.Step[] shutdownSteps = new Sequence.Step[] {
                new RegisterStep(InitializationShutdownStep.Direction.SHUTTING_DOWN),
                new CommandProcessorsStep(InitializationShutdownStep.Direction.SHUTTING_DOWN),
                new InferiorInputOutputInitStep(InitializationShutdownStep.Direction.SHUTTING_DOWN),
                new CommandMonitoringStep(InitializationShutdownStep.Direction.SHUTTING_DOWN),
            };
        Sequence shutdownSequence = new Sequence(getExecutor(), requestMonitor) {
            @Override public Step[] getSteps() { return shutdownSteps; }
        };
        getExecutor().execute(shutdownSequence);
        
    }        
    
    public String getId() {
        return fMIBackend.getId();
    }

    @Override
    public MIControlDMContext getControlDMContext() {
        return fControlDmc;
    }
    
    public ICommandControlDMContext getContext() {
        return fControlDmc;
    }
    
    public void terminate(final RequestMonitor rm) {
        // Schedule a runnable to be executed 2 seconds from now.
        // If we don't get a response to the quit command, this 
        // runnable will kill the task.
        final Future<?> quitTimeoutFuture = getExecutor().schedule(
            new DsfRunnable() {
                public void run() {
                    fMIBackend.destroy();
                    rm.done();
                }
                
                @Override
                protected boolean isExecutionRequired() {
                    return false;
                }
            }, 
            2, TimeUnit.SECONDS);
        
        queueCommand(
       		new MIGDBExit(fControlDmc),
            new DataRequestMonitor<MIInfo>(getExecutor(), rm) { 
                @Override
                public void handleCompleted() {
                    // Cancel the time out runnable (if it hasn't run yet).
                    if (quitTimeoutFuture.cancel(false)) {
                        if (!isSuccess()) {
                        	fMIBackend.destroy();
                        }
                        rm.done();
                    }
                }
            }
        );
    }
    
    /*
     * This method does the necessary work to setup the input/output streams for the
     * inferior process, by either preparing the PTY to be used, to simply leaving
     * the PTY null, which indicates that the input/output streams of the CLI should
     * be used instead; this decision is based on the type of session.
     */
    public void initInferiorInputOutput(final RequestMonitor requestMonitor) {
    	if (fMIBackend.getSessionType() == SessionType.REMOTE || fMIBackend.getIsAttachSession()) {
    		// These types do not use a PTY
    		fPty = null;
    		requestMonitor.done();
    	} else {
    		// These types always use a PTY
    		try {
    			fPty = new PTY();

    			// Tell GDB to use this PTY
    			queueCommand(
    					new MIInferiorTTYSet((ICommandControlDMContext)fControlDmc, fPty.getSlaveName()), 
    					new DataRequestMonitor<MIInfo>(getExecutor(), requestMonitor) {
    						@Override
    						protected void handleFailure() {
    							// We were not able to tell GDB to use the PTY
    							// so we won't use it at all.
    			    			fPty = null;
    			        		requestMonitor.done();
    						}
    					});
    		} catch (IOException e) {
    			fPty = null;
        		requestMonitor.done();
    		}
    	}
    }


    public boolean canRestart() {
    	if (fMIBackend.getIsAttachSession()) return false;
    	
    	// Before GDB6.8, the Linux gdbserver would restart a new
    	// process when getting a -exec-run but the communication
    	// with GDB had a bug and everything hung.
    	// with GDB6.8 the program restarts properly one time,
    	// but on a second attempt, gdbserver crashes.
    	// So, lets just turn off the Restart for Remote debugging
    	if (fMIBackend.getSessionType() == SessionType.REMOTE) return false;
    	
    	return true;
    }

     /*
     * Start the program.
     */
    public void start(GdbLaunch launch, final RequestMonitor requestMonitor) {
    	startOrRestart(launch, false, requestMonitor);
    }

    /*
     * Before restarting the inferior, we must re-initialize its input/output streams
     * and create a new inferior process object.  Then we can restart the inferior.
     */
    public void restart(final GdbLaunch launch, final RequestMonitor requestMonitor) {
   		startOrRestart(launch, true, requestMonitor);
    }

    /*
     * Insert breakpoint at entry if set, and start or restart the program.
     */
    protected void startOrRestart(final GdbLaunch launch, boolean restart, final RequestMonitor requestMonitor) {
    	if (fMIBackend.getIsAttachSession()) {
    		// When attaching to a running process, we do not need to set a breakpoint or
    		// start the program; it is left up to the user.
    		requestMonitor.done();
    		return;
    	}

    	DsfServicesTracker servicesTracker = new DsfServicesTracker(GdbPlugin.getBundleContext(), getSession().getId());
    	IMIProcesses procService = servicesTracker.getService(IMIProcesses.class);
    	servicesTracker.dispose();
   		IProcessDMContext procDmc = procService.createProcessContext(fControlDmc, MIProcesses.UNIQUE_GROUP_ID);
   		final IContainerDMContext containerDmc = procService.createContainerContext(procDmc, MIProcesses.UNIQUE_GROUP_ID);

    	final MICommand<MIInfo> execCommand;
    	if (fMIBackend.getSessionType() == SessionType.REMOTE) {
    		// When doing remote debugging, we use -exec-continue instead of -exec-run 
    		execCommand = new MIExecContinue(containerDmc);
    	} else {
    		execCommand = new MIExecRun(containerDmc, new String[0]);	
    	}

    	boolean stopInMain = false;
    	try {
    		stopInMain = launch.getLaunchConfiguration().getAttribute( ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_STOP_AT_MAIN, false );
    	} catch (CoreException e) {
    		requestMonitor.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, -1, "Cannot retrieve stop at entry point boolean", e)); //$NON-NLS-1$
    		requestMonitor.done();
    		return;
    	}

    	final DataRequestMonitor<MIInfo> execMonitor = new DataRequestMonitor<MIInfo>(getExecutor(), requestMonitor);

    	if (!stopInMain) {
    		// Just start the program.
    		queueCommand(execCommand, execMonitor);
    	} else {
    		String stopSymbol = null;
    		try {
    			stopSymbol = launch.getLaunchConfiguration().getAttribute( ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_STOP_AT_MAIN_SYMBOL, ICDTLaunchConfigurationConstants.DEBUGGER_STOP_AT_MAIN_SYMBOL_DEFAULT );
    		} catch (CoreException e) {
    			requestMonitor.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, DebugException.CONFIGURATION_INVALID, "Cannot retrieve the entry point symbol", e)); //$NON-NLS-1$
    			requestMonitor.done();
    			return;
    		}

    		// Insert a breakpoint at the requested stop symbol.
    		queueCommand(
    				new MIBreakInsert(fControlDmc, true, false, null, 0, stopSymbol, 0), 
    				new DataRequestMonitor<MIBreakInsertInfo>(getExecutor(), requestMonitor) { 
    					@Override
    					protected void handleSuccess() {
    						// After the break-insert is done, execute the -exec-run or -exec-continue command.
    						queueCommand(execCommand, execMonitor);
    					}
    				});
    	}
    }

    /*
     * This method creates a new inferior process object based on the current Pty or output stream.
     */
    public void createInferiorProcess() {
    	if (fPty == null) {
    		fInferiorProcess = new GDBInferiorProcess(GDBControl_7_0.this, fMIBackend, fMIBackend.getMIOutputStream());
    	} else {
    		fInferiorProcess = new GDBInferiorProcess(GDBControl_7_0.this, fMIBackend, fPty);
    	}
    }

    public boolean isConnected() {
    	return fInferiorProcess.getState() != MIInferiorProcess.State.TERMINATED &&
    							(!fMIBackend.getIsAttachSession() || fConnected > 0);
    }

    public void setConnected(boolean connected) {
    	if (connected) {
    		fConnected++;
    	} else {
    		if (fConnected > 0) fConnected--;
    	}
    }

    public AbstractCLIProcess getCLIProcess() { 
        return fCLIProcess; 
    }

    public MIInferiorProcess getInferiorProcess() {
        return fInferiorProcess;
    }
    
    @DsfServiceEventHandler 
    public void eventDispatched(ICommandControlShutdownDMEvent e) {
        // Handle our "GDB Exited" event and stop processing commands.
        stopCommandProcessing();
    }
    
    @DsfServiceEventHandler 
    public void eventDispatched(BackendStateChangedEvent e) {
        if (e.getState() == IMIBackend.State.TERMINATED && e.getBackendId().equals(fMIBackend.getId())) {
            // Handle "GDB Exited" event, just relay to following event.
            getSession().dispatchEvent(new GDBControlShutdownDMEvent(fControlDmc), getProperties());
        }
    }
 
    public static class InitializationShutdownStep extends Sequence.Step {
        public enum Direction { INITIALIZING, SHUTTING_DOWN }
        
        private Direction fDirection;
        public InitializationShutdownStep(Direction direction) { fDirection = direction; }
        
        @Override
        final public void execute(RequestMonitor requestMonitor) {
            if (fDirection == Direction.INITIALIZING) {
                initialize(requestMonitor);
            } else {
                shutdown(requestMonitor);
            }
        }
        
        @Override
        final public void rollBack(RequestMonitor requestMonitor) {
            if (fDirection == Direction.INITIALIZING) {
                shutdown(requestMonitor);
            } else {
                super.rollBack(requestMonitor);
            }
        }
        
        protected void initialize(RequestMonitor requestMonitor) {
            requestMonitor.done();
        }
        protected void shutdown(RequestMonitor requestMonitor) {
            requestMonitor.done();
        }
    }

    protected class CommandMonitoringStep extends InitializationShutdownStep {
        CommandMonitoringStep(Direction direction) { super(direction); }

        @Override
        protected void initialize(final RequestMonitor requestMonitor) {
            startCommandProcessing(fMIBackend.getMIInputStream(), fMIBackend.getMIOutputStream());
            requestMonitor.done();
        }

        @Override
        protected void shutdown(RequestMonitor requestMonitor) {
            stopCommandProcessing();
            requestMonitor.done();
        }
    }
    
    protected class InferiorInputOutputInitStep extends InitializationShutdownStep {
    	InferiorInputOutputInitStep(Direction direction) { super(direction); }

        @Override
        protected void initialize(final RequestMonitor requestMonitor) {
        	initInferiorInputOutput(requestMonitor);
        }

        @Override
        protected void shutdown(RequestMonitor requestMonitor) {
            requestMonitor.done();
        }
    }

    protected class CommandProcessorsStep extends InitializationShutdownStep {
        CommandProcessorsStep(Direction direction) { super(direction); }

        @Override
        public void initialize(final RequestMonitor requestMonitor) {
            try {
                fCLIProcess = new MIBackendCLIProcess(GDBControl_7_0.this, fMIBackend);
            }
            catch(IOException e) {
                requestMonitor.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, IDsfStatusConstants.REQUEST_FAILED, "Failed to create CLI Process", e)); //$NON-NLS-1$
                requestMonitor.done();
                return;
            }

            createInferiorProcess();
            
            fCLICommandProcessor = new CLIEventProcessor_7_0(GDBControl_7_0.this, fControlDmc);
            fMIEventProcessor = new MIRunControlEventProcessor_7_0(GDBControl_7_0.this, fControlDmc);

            requestMonitor.done();
        }
        
        @Override
        protected void shutdown(RequestMonitor requestMonitor) {
            fCLICommandProcessor.dispose();
            fMIEventProcessor.dispose();
            fCLIProcess.dispose();
            fInferiorProcess.dispose();

            requestMonitor.done();
        }
    }
    
    protected class RegisterStep extends InitializationShutdownStep {
        RegisterStep(Direction direction) { super(direction); }
        @Override
        public void initialize(final RequestMonitor requestMonitor) {
            getSession().addServiceEventListener(GDBControl_7_0.this, null);
            register(
                new String[]{ ICommandControl.class.getName(), 
                              ICommandControlService.class.getName(), 
                              AbstractMIControl.class.getName(),
                              IGDBControl.class.getName() }, 
                new Hashtable<String,String>());
            getSession().dispatchEvent(new GDBControlInitializedDMEvent(fControlDmc), getProperties());
            requestMonitor.done();
        }

        @Override
        protected void shutdown(RequestMonitor requestMonitor) {
            unregister();
            getSession().removeServiceEventListener(GDBControl_7_0.this);
            requestMonitor.done();
        }
    }
}
