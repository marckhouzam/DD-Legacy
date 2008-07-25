/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson	AB		  - Modified for handling of multiple threads
 *******************************************************************************/
package org.eclipse.dd.mi.service;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.Immutable;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.AbstractDMContext;
import org.eclipse.dd.dsf.datamodel.AbstractDMEvent;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.datamodel.IDMEvent;
import org.eclipse.dd.dsf.debug.service.IStack.IFrameDMContext;
import org.eclipse.dd.dsf.debug.service.command.CommandCache;
import org.eclipse.dd.dsf.service.AbstractDsfService;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.mi.internal.MIPlugin;
import org.eclipse.dd.mi.service.command.AbstractMIControl;
import org.eclipse.dd.mi.service.command.commands.MIExecContinue;
import org.eclipse.dd.mi.service.command.commands.MIExecFinish;
import org.eclipse.dd.mi.service.command.commands.MIExecInterrupt;
import org.eclipse.dd.mi.service.command.commands.MIExecNext;
import org.eclipse.dd.mi.service.command.commands.MIExecNextInstruction;
import org.eclipse.dd.mi.service.command.commands.MIExecStep;
import org.eclipse.dd.mi.service.command.commands.MIExecStepInstruction;
import org.eclipse.dd.mi.service.command.commands.MIExecUntil;
import org.eclipse.dd.mi.service.command.commands.MIThreadListIds;
import org.eclipse.dd.mi.service.command.events.IMIDMEvent;
import org.eclipse.dd.mi.service.command.events.MIBreakpointHitEvent;
import org.eclipse.dd.mi.service.command.events.MIErrorEvent;
import org.eclipse.dd.mi.service.command.events.MIEvent;
import org.eclipse.dd.mi.service.command.events.MIGDBExitEvent;
import org.eclipse.dd.mi.service.command.events.MIRunningEvent;
import org.eclipse.dd.mi.service.command.events.MISharedLibEvent;
import org.eclipse.dd.mi.service.command.events.MISignalEvent;
import org.eclipse.dd.mi.service.command.events.MISteppingRangeEvent;
import org.eclipse.dd.mi.service.command.events.MIStoppedEvent;
import org.eclipse.dd.mi.service.command.events.MIThreadCreatedEvent;
import org.eclipse.dd.mi.service.command.events.MIThreadExitEvent;
import org.eclipse.dd.mi.service.command.events.MIWatchpointTriggerEvent;
import org.eclipse.dd.mi.service.command.output.MIInfo;
import org.eclipse.dd.mi.service.command.output.MIThreadListIdsInfo;
import org.osgi.framework.BundleContext;


/**
 * 
 * <p>
 * Implementation note:
 * This class implements event handlers for the events that are generated by
 * this service itself.  When the event is dispatched, these handlers will
 * be called first, before any of the clients.  These handlers update the
 * service's internal state information to make them consistent with the
 * events being issued.  Doing this in the handlers as opposed to when
 * the events are generated, guarantees that the state of the service will
 * always be consistent with the events.
 * The purpose of this pattern is to allow clients that listen to service
 * events and track service state, to be perfectly in sync with the service
 * state.
 */
public class MIRunControl extends AbstractDsfService implements IMIRunControl
{
	class MIExecutionDMC extends AbstractDMContext implements IMIExecutionDMContext
	{
		/**
		 * Integer ID that is used to identify the thread in the GDB/MI protocol.
		 */
		private final int fThreadId;

		/**
		 * Constructor for the context.  It should not be called directly by clients.
		 * Instead clients should call {@link MIRunControl#createMIExecutionContext(IContainerDMContext, int)}
		 * to create instances of this context based on the thread ID.
		 * <p/>
		 * Classes extending {@link MIRunControl} may also extend this class to include
		 * additional information in the context.
		 * 
		 * @param sessionId Session that this context belongs to.
		 * @param containerDmc The container that this context belongs to.
		 * @param threadId GDB/MI thread identifier.
		 */
		protected MIExecutionDMC(String sessionId, IContainerDMContext containerDmc, int threadId) {
			super(sessionId, containerDmc != null ? new IDMContext[] { containerDmc } : new IDMContext[0]);
			fThreadId = threadId;
		}

		/**
		 * Returns the GDB/MI thread identifier of this context.
		 * @return
		 */
		public int getThreadId(){
			return fThreadId;
		}

		@Override
		public String toString() { return baseToString() + ".thread[" + fThreadId + "]"; }  //$NON-NLS-1$ //$NON-NLS-2$

		@Override
		public boolean equals(Object obj) {
			return super.baseEquals(obj) && ((MIExecutionDMC)obj).fThreadId == fThreadId;
		}

		@Override
		public int hashCode() { return super.baseHashCode() ^ fThreadId; }
	}

	@Immutable
	static class ExecutionData implements IExecutionDMData {
		private final StateChangeReason fReason;
		ExecutionData(StateChangeReason reason) {
			fReason = reason;
		}
		public StateChangeReason getStateChangeReason() { return fReason; }
	}

	/**
	 * Base class for events generated by the MI Run Control service.  Most events
	 * generated by the MI Run Control service are directly caused by some MI event.
	 * Other services may need access to the extended MI data carried in the event.
	 * 
	 * @param <V> DMC that this event refers to
	 * @param <T> MIInfo object that is the direct cause of this event
	 * @see MIRunControl
	 */
	@Immutable
	static class RunControlEvent<V extends IDMContext, T extends MIEvent<? extends IDMContext>> extends AbstractDMEvent<V>
	implements IDMEvent<V>, IMIDMEvent
	{
		final private T fMIInfo;
		public RunControlEvent(V dmc, T miInfo) {
			super(dmc);
			fMIInfo = miInfo;
		}

		public T getMIEvent() { return fMIInfo; }
	}

	/**
	 * Indicates that the given thread has been suspended.
	 */
	@Immutable
	static class SuspendedEvent extends RunControlEvent<IExecutionDMContext, MIStoppedEvent>
	implements ISuspendedDMEvent
	{
		SuspendedEvent(IExecutionDMContext ctx, MIStoppedEvent miInfo) {
			super(ctx, miInfo);
		}

		public StateChangeReason getReason() {
			if (getMIEvent() instanceof MIBreakpointHitEvent) {
				return StateChangeReason.BREAKPOINT;
			} else if (getMIEvent() instanceof MISteppingRangeEvent) {
				return StateChangeReason.STEP;
			} else if (getMIEvent() instanceof MISharedLibEvent) {
				return StateChangeReason.SHAREDLIB;
			}else if (getMIEvent() instanceof MISignalEvent) {
				return StateChangeReason.SIGNAL;
			}else if (getMIEvent() instanceof MIWatchpointTriggerEvent) {
				return StateChangeReason.WATCHPOINT;
			}else if (getMIEvent() instanceof MIErrorEvent) {
				return StateChangeReason.ERROR;
			}else {
				return StateChangeReason.USER_REQUEST;
			}
		}
	}

	@Immutable
	static class ContainerSuspendedEvent extends SuspendedEvent
	implements IContainerSuspendedDMEvent
	{
		final IExecutionDMContext[] triggeringDmcs;
		ContainerSuspendedEvent(IContainerDMContext containerDmc, MIStoppedEvent miInfo, IExecutionDMContext triggeringDmc) {
			super(containerDmc, miInfo);
			this.triggeringDmcs = triggeringDmc != null
			? new IExecutionDMContext[] { triggeringDmc } : new IExecutionDMContext[0];
		}

		public IExecutionDMContext[] getTriggeringContexts() {
			return triggeringDmcs;
		}
	}

	@Immutable
	static class ThreadSuspendedEvent extends SuspendedEvent
	{
		ThreadSuspendedEvent(IExecutionDMContext executionDmc, MIStoppedEvent miInfo) {
			super(executionDmc, miInfo);
		}
	}

	@Immutable
	static class ResumedEvent extends RunControlEvent<IExecutionDMContext, MIRunningEvent>
	implements IResumedDMEvent
	{
		ResumedEvent(IExecutionDMContext ctx, MIRunningEvent miInfo) {
			super(ctx, miInfo);
		}

		public StateChangeReason getReason() {
			switch(getMIEvent().getType()) {
			case MIRunningEvent.CONTINUE:
				return StateChangeReason.USER_REQUEST;
			case MIRunningEvent.NEXT:
			case MIRunningEvent.NEXTI:
				return StateChangeReason.STEP;
			case MIRunningEvent.STEP:
			case MIRunningEvent.STEPI:
				return StateChangeReason.STEP;
			case MIRunningEvent.FINISH:
				return StateChangeReason.STEP;
			case MIRunningEvent.UNTIL:
			case MIRunningEvent.RETURN:
				break;
			}
			return StateChangeReason.UNKNOWN;
		}
	}

	@Immutable
	static class ContainerResumedEvent extends ResumedEvent
	implements IContainerResumedDMEvent
	{
		final IExecutionDMContext[] triggeringDmcs;

		ContainerResumedEvent(IContainerDMContext containerDmc, MIRunningEvent miInfo, IExecutionDMContext triggeringDmc) {
			super(containerDmc, miInfo);
			this.triggeringDmcs = triggeringDmc != null
			? new IExecutionDMContext[] { triggeringDmc } : new IExecutionDMContext[0];
		}

		public IExecutionDMContext[] getTriggeringContexts() {
			return triggeringDmcs;
		}
	}

	@Immutable
	static class ThreadResumedEvent extends ResumedEvent
	{
		ThreadResumedEvent(IExecutionDMContext executionDmc, MIRunningEvent miInfo) {
			super(executionDmc, miInfo);
		}
	}

	@Immutable
	static class StartedDMEvent extends RunControlEvent<IExecutionDMContext,MIThreadCreatedEvent>
	implements IStartedDMEvent
	{
		StartedDMEvent(IMIExecutionDMContext executionDmc, MIThreadCreatedEvent miInfo) {
			super(executionDmc, miInfo);
		}
	}

	@Immutable
	static class ExitedDMEvent extends RunControlEvent<IExecutionDMContext,MIThreadExitEvent>
	implements IExitedDMEvent
	{
		ExitedDMEvent(IMIExecutionDMContext executionDmc, MIThreadExitEvent miInfo) {
			super(executionDmc, miInfo);
		}
	}

	private AbstractMIControl fConnection;
	private CommandCache fMICommandCache;
    
    // State flags
	private boolean fSuspended = true;
    private boolean fResumePending = false;
	private boolean fStepping = false;
	private boolean fTerminated = false;
	
	private StateChangeReason fStateChangeReason;
	private IExecutionDMContext fStateChangeTriggeringContext;
	
	private static final int DEFAULT_THREAD_ID = 1;

    public MIRunControl(DsfSession session) {
        super(session);
    }
    
    @Override
    public void initialize(final RequestMonitor rm) {
        super.initialize(
            new RequestMonitor(getExecutor(), rm) {
                @Override
                protected void handleSuccess() {
                    doInitialize(rm);
                }});
    }

    private void doInitialize(final RequestMonitor rm) {
        fConnection = getServicesTracker().getService(AbstractMIControl.class);
        fMICommandCache = new CommandCache(getSession(), fConnection);
        fMICommandCache.setContextAvailable(fConnection.getControlDMContext(), true);
        getSession().addServiceEventListener(this, null);
        rm.done();
    }

    @Override
    public void shutdown(final RequestMonitor rm) {
        getSession().removeServiceEventListener(this);
        fMICommandCache.reset();
        super.shutdown(rm);
    }
    
    public boolean isValid() { return true; }
    
    @SuppressWarnings("unchecked")
    public void getModelData(IDMContext dmc, DataRequestMonitor<?> rm) {
        if (dmc instanceof IExecutionDMContext) {
            getExecutionData((IExecutionDMContext)dmc, (DataRequestMonitor<IExecutionDMData>)rm);
        } else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Unknown DMC type", null)); //$NON-NLS-1$
            rm.done();
        }
    }
    
    public CommandCache getCache() { return fMICommandCache; }
    
    public IMIExecutionDMContext createMIExecutionContext(IContainerDMContext container, int threadId) {
        return new MIExecutionDMC(getSession().getId(), container, threadId);
    }
    
    //
    // Running event handling
    //
    @DsfServiceEventHandler
    public void eventDispatched(final MIRunningEvent e) {
        IDMEvent<?> event = null;
        // Find the container context, which is used in multi-threaded debugging.
        IContainerDMContext containerDmc = DMContexts.getAncestorOfType(e.getDMContext(), IContainerDMContext.class);
        if (containerDmc != null) {
            // Set the triggering context only if it's different than the container context.
            IExecutionDMContext triggeringCtx = !e.getDMContext().equals(containerDmc) ? e.getDMContext() : null;
            event = new ContainerResumedEvent(containerDmc, e, triggeringCtx);
        } else {
            event = new ResumedEvent(e.getDMContext(), e);
        }
        getSession().dispatchEvent(event, getProperties());
    }

    //
    // Suspended event handling
    //
    @DsfServiceEventHandler
    public void eventDispatched(final MIStoppedEvent e) {
        IDMEvent<?> event = null;
        // Find the container context, which is used in multi-threaded debugging.
        IContainerDMContext containerDmc = DMContexts.getAncestorOfType(e.getDMContext(), IContainerDMContext.class);
        if (containerDmc != null) {
            // Set the triggering context only if it's different than the container context.
            IExecutionDMContext triggeringCtx = !e.getDMContext().equals(containerDmc) ? e.getDMContext() : null;
            event = new ContainerSuspendedEvent(containerDmc, e, triggeringCtx);
        } else {
            event = new SuspendedEvent(e.getDMContext(), e);
        }
        getSession().dispatchEvent(event, getProperties());
    }

    //
    // Thread Created event handling
    // When a new thread is created - OOB Event fired ~"[New Thread 1077300144 (LWP 7973)]\n"
    //
    @DsfServiceEventHandler
    public void eventDispatched(final MIThreadCreatedEvent e) {
        IContainerDMContext containerDmc = e.getDMContext();
        IMIExecutionDMContext executionCtx = e.getId() != -1 ? new MIExecutionDMC(getSession().getId(), containerDmc, e.getId()) : null;
        getSession().dispatchEvent(new StartedDMEvent(executionCtx, e), getProperties());
    }

    //
    // Thread exit event handling
    // When a new thread is destroyed - OOB Event fired "
    //
    @DsfServiceEventHandler
    public void eventDispatched(final MIThreadExitEvent e) {
        IContainerDMContext containerDmc = e.getDMContext();
        IMIExecutionDMContext executionCtx = e.getId() != -1 ? new MIExecutionDMC(getSession().getId(), containerDmc, e.getId()) : null;
    	getSession().dispatchEvent(new ExitedDMEvent(executionCtx, e), getProperties());
    }

    @DsfServiceEventHandler
    public void eventDispatched(ContainerResumedEvent e) {
        fSuspended = false;
        fResumePending = false;
        fStateChangeReason = e.getReason();
        fMICommandCache.setContextAvailable(e.getDMContext(), false);
        //fStateChangeTriggeringContext = e.getTriggeringContext();
        if (e.getReason().equals(StateChangeReason.STEP)) {
            fStepping = true;
        } else {
            fMICommandCache.reset();
        }
    }


    @DsfServiceEventHandler
    public void eventDispatched(ContainerSuspendedEvent e) {
        fMICommandCache.setContextAvailable(e.getDMContext(), true);
        fMICommandCache.reset();
        fStateChangeReason = e.getReason();
        fStateChangeTriggeringContext = e.getTriggeringContexts().length != 0
            ? e.getTriggeringContexts()[0] : null;
        fSuspended = true;
        fStepping = false;
    }
    
    
    @DsfServiceEventHandler
    public void eventDispatched(MIGDBExitEvent e) {
        fTerminated = true;
	}


    // Event handler when New thread is created
    @DsfServiceEventHandler
    public void eventDispatched(StartedDMEvent e) {

	}

    // Event handler when a thread is destroyed
    @DsfServiceEventHandler
    public void eventDispatched(ExitedDMEvent e) {
    	fMICommandCache.reset(e.getDMContext());
    }

    ///////////////////////////////////////////////////////////////////////////
    // AbstractService
    @Override
    protected BundleContext getBundleContext() {
        return MIPlugin.getBundleContext();
    }
    
    ///////////////////////////////////////////////////////////////////////////
    // IRunControl
	public void canResume(IExecutionDMContext context, DataRequestMonitor<Boolean> rm) {
        rm.setData(doCanResume(context));
        rm.done();
	}
	
	private boolean doCanResume(IExecutionDMContext context) {
	    return !fTerminated && isSuspended(context) && !fResumePending;
	}

	public void canSuspend(IExecutionDMContext context, DataRequestMonitor<Boolean> rm) {
        rm.setData(doCanSuspend(context));
        rm.done();
	}
	
    private boolean doCanSuspend(IExecutionDMContext context) {
        return !fTerminated && !isSuspended(context);
    }

	public boolean isSuspended(IExecutionDMContext context) {
		return !fTerminated && fSuspended;
	}

	public boolean isStepping(IExecutionDMContext context) {
    	return !fTerminated && fStepping;
    }

	public void resume(IExecutionDMContext context, final RequestMonitor rm) {
		assert context != null;

		if (doCanResume(context)) {
            fResumePending = true;
            // Cygwin GDB will accept commands and execute them after the step
            // which is not what we want, so mark the target as unavailable
            // as soon as we send a resume command.
            fMICommandCache.setContextAvailable(context, false);
            MIExecContinue cmd = null;
            if(context instanceof IContainerDMContext)
            	cmd = new MIExecContinue(context);
            else{
        		IMIExecutionDMContext dmc = DMContexts.getAncestorOfType(context, IMIExecutionDMContext.class);
    			if (dmc == null){
    	            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_STATE, "Given context: " + context + " is not an execution context.", null)); //$NON-NLS-1$ //$NON-NLS-2$
    	            rm.done();
    	            return;
    			}
            	cmd = new MIExecContinue(dmc);//, new String[0]);
            }
            fConnection.queueCommand(
            	cmd,
            	new DataRequestMonitor<MIInfo>(getExecutor(), rm) {
                    @Override
                    protected void handleSuccess() {
                        rm.done();
                    }
            	}
            );
        }else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_STATE, "Given context: " + context + ", is already running.", null)); //$NON-NLS-1$ //$NON-NLS-2$
            rm.done();
        }
	}
	
	public void suspend(IExecutionDMContext context, final RequestMonitor rm){
		assert context != null;

		if (doCanSuspend(context)) {
			MIExecInterrupt cmd = null;
			if(context instanceof IContainerDMContext){
				cmd = new MIExecInterrupt(context);
			}
			else {
				IMIExecutionDMContext dmc = DMContexts.getAncestorOfType(context, IMIExecutionDMContext.class);
				if (dmc == null){
		            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Given context: " + context + " is not an execution context.", null)); //$NON-NLS-1$ //$NON-NLS-2$
		            rm.done();
		            return;
				}
				cmd = new MIExecInterrupt(dmc);
			}
            fConnection.queueCommand(
            	cmd,
                new DataRequestMonitor<MIInfo>(getExecutor(), rm) {
                    @Override
                    protected void handleSuccess() {
                        rm.done();
                    }
            	}
            );
        } else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Given context: " + context + ", is already suspended.", null)); //$NON-NLS-1$ //$NON-NLS-2$
            rm.done();
        }
    }
    
    public void canStep(IExecutionDMContext context, StepType stepType, DataRequestMonitor<Boolean> rm) {
    	if (context instanceof IContainerDMContext) {
    		rm.setData(false);
    		rm.done();
    		return;
    	}
        canResume(context, rm);
    }
    
    public void step(IExecutionDMContext context, StepType stepType, final RequestMonitor rm) {
    	assert context != null;

    	IMIExecutionDMContext dmc = DMContexts.getAncestorOfType(context, IMIExecutionDMContext.class);
		if (dmc == null){
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Given context: " + context + " is not an execution context.", null)); //$NON-NLS-1$ //$NON-NLS-2$
            rm.done();
            return;
		}
    	
    	if (!doCanResume(context)) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_STATE, "Cannot resume context", null)); //$NON-NLS-1$
            rm.done();
            return;
        }

        fResumePending = true;
        fStepping = true;
        fMICommandCache.setContextAvailable(context, false);
        switch(stepType) {
            case STEP_INTO:
                fConnection.queueCommand(
                    new MIExecStep(dmc, 1), new DataRequestMonitor<MIInfo>(getExecutor(), rm) {}
                );
                break;
            case STEP_OVER:
                fConnection.queueCommand(
                    new MIExecNext(dmc), new DataRequestMonitor<MIInfo>(getExecutor(), rm) {});
                break;
            case STEP_RETURN:
                // The -exec-finish command operates on the selected stack frame, but here we always
                // want it to operate on the stop stack frame.  So we manually create a top-frame
                // context to use with the MI command.
                // We get a local instance of the stack service because the stack service can be shut
                // down before the run control service is shut down.  So it is possible for the
                // getService() reqeust below to return null.
                MIStack stackService = getServicesTracker().getService(MIStack.class);
                if (stackService != null) {
                    IFrameDMContext topFrameDmc = stackService.createFrameDMContext(dmc, 0);
                    fConnection.queueCommand(
                        new MIExecFinish(topFrameDmc), new DataRequestMonitor<MIInfo>(getExecutor(), rm) {});
                } else {
                    rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Cannot create context for command, stack service not available.", null)); //$NON-NLS-1$
                    rm.done();
                }
                break;
            case INSTRUCTION_STEP_INTO:
                fConnection.queueCommand(
                        new MIExecStepInstruction(dmc, 1), new DataRequestMonitor<MIInfo>(getExecutor(), rm) {}
                    );
                break;
            case INSTRUCTION_STEP_OVER:
                fConnection.queueCommand(
                    new MIExecNextInstruction(dmc, 1), new DataRequestMonitor<MIInfo>(getExecutor(), rm) {}
                );
                break;
            default:
                rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Given step type not supported", null)); //$NON-NLS-1$
                rm.done();
        }
    }

    public void getExecutionContexts(final IContainerDMContext containerDmc, final DataRequestMonitor<IExecutionDMContext[]> rm) {
		fMICommandCache.execute(
		    new MIThreadListIds(containerDmc),
				new DataRequestMonitor<MIThreadListIdsInfo>(
						getExecutor(), rm) {
					@Override
					protected void handleSuccess() {
						rm.setData(makeExecutionDMCs(containerDmc, getData()));
						rm.done();
					}
				});
    }
    

	private IExecutionDMContext[] makeExecutionDMCs(IContainerDMContext containerCtx, MIThreadListIdsInfo info) {
		if (info.getThreadIds().length == 0) {
			//Main thread always exist even if it is not reported by GDB.
			//So create thread-id= 0 when no thread is reported
			return new IMIExecutionDMContext[]{new MIExecutionDMC(getSession().getId(), containerCtx, DEFAULT_THREAD_ID)};
		} else {
			IExecutionDMContext[] executionDmcs = new IMIExecutionDMContext[info.getThreadIds().length];
			for (int i = 0; i < info.getThreadIds().length; i++) {
				executionDmcs[i] = new MIExecutionDMC(getSession().getId(), containerCtx, info.getThreadIds()[i]);
			}
			return executionDmcs;
		}
	}
	
	public void getExecutionData(IExecutionDMContext dmc, DataRequestMonitor<IExecutionDMData> rm){
        if (dmc instanceof IContainerDMContext) {
            rm.setData( new ExecutionData(fStateChangeReason) );
        } else if (dmc instanceof IMIExecutionDMContext) {
    	    StateChangeReason reason = dmc.equals(fStateChangeTriggeringContext) ? fStateChangeReason : StateChangeReason.CONTAINER;
    		rm.setData(new ExecutionData(reason));
        } else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Given context: " + dmc + " is not an execution context.", null)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        rm.done();
    }
	
	/*
	 * Run selected execution thread to a given line number.
	 */
	// Later add support for Address and function.
	// skipBreakpoints is not used at the moment. Implement later
	public void runToLine(IExecutionDMContext context, String fileName, String lineNo, boolean skipBreakpoints, final DataRequestMonitor<MIInfo> rm){
    	assert context != null;

    	IMIExecutionDMContext dmc = DMContexts.getAncestorOfType(context, IMIExecutionDMContext.class);
		if (dmc == null){
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Given context: " + context + " is not an execution context.", null)); //$NON-NLS-1$ //$NON-NLS-2$
            rm.done();
            return;
		}

        if (doCanResume(context)) {
            fResumePending = true;
            fMICommandCache.setContextAvailable(context, false);
    		fConnection.queueCommand(new MIExecUntil(dmc, fileName + ":" + lineNo), //$NON-NLS-1$
    				new DataRequestMonitor<MIInfo>(
    						getExecutor(), rm) {
    					@Override
    					protected void handleSuccess() {
    						rm.done();
    					}
    				});
        } else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED,
            		"Cannot resume given DMC.", null)); //$NON-NLS-1$
            rm.done();
        }
	}
}
