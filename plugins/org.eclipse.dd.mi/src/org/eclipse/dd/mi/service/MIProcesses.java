/*******************************************************************************
 * Copyright (c) 2008 Ericsson and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Ericsson - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.mi.service;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.Immutable;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.AbstractDMContext;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.debug.service.IProcesses;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IResumedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.ISuspendedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.StateChangeReason;
import org.eclipse.dd.dsf.debug.service.command.CommandCache;
import org.eclipse.dd.dsf.service.AbstractDsfService;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.mi.internal.MIPlugin;
import org.eclipse.dd.mi.service.command.AbstractMIControl;
import org.eclipse.dd.mi.service.command.MIControlDMContext;
import org.eclipse.dd.mi.service.command.commands.CLIAttach;
import org.eclipse.dd.mi.service.command.commands.CLIInfoThreads;
import org.eclipse.dd.mi.service.command.output.CLIInfoThreadsInfo;
import org.eclipse.dd.mi.service.command.output.MIInfo;
import org.osgi.framework.BundleContext;


public class MIProcesses extends AbstractDsfService implements IProcesses {
	
	// Below is the context hierarchy that is implemented between the
	// MIProcesses service and the MIRunControl service for the MI 
	// implementation of DSF:
	//
	//                           MIControlDMContext
	//                                |
	//                           MIProcessDMC (IProcess)
	//   MIExecutionGroupDMC __/      |
	//     (IContainer)               |
	//          |                MIThreadDMC (IThread)
	//    MIExecutionDMC  _____/
	//     (IExecution)
	//
	
	/**
	 * Context representing a thread group of GDB/MI. 
	 */
    @Immutable
	protected class MIExecutionGroupDMC extends AbstractDMContext
	implements IMIExecutionGroupDMContext
	{
		/**
		 * String ID that is used to identify the thread group in the GDB/MI protocol.
		 */
		private final String fId;

		/**
		 * Constructor for the context.  It should not be called directly by clients.
		 * Instead clients should call {@link MIRunControl#createMIExecutionGroupContext
		 * to create instances of this context based on the group name.
		 * 
		 * @param sessionId Session that this context belongs to.
		 * @param processDmc The process context that is the parent of this context.
		 * @param groupId GDB/MI thread group identifier.
		 */
		protected MIExecutionGroupDMC(String sessionId, IProcessDMContext processDmc, String groupId) {
			super(sessionId, processDmc == null ? new IDMContext[0] : new IDMContext[] { processDmc });
			fId = groupId;
		}

		/**
		 * Returns the GDB/MI thread group identifier of this context.
		 */
		public String getGroupId(){ return fId; }

		@Override
		public String toString() { return baseToString() + ".threadGroup[" + fId + "]"; }  //$NON-NLS-1$ //$NON-NLS-2$

		@Override
		public boolean equals(Object obj) {
			return super.baseEquals(obj) && 
			       (((MIExecutionGroupDMC)obj).fId == null ? fId == null : ((MIExecutionGroupDMC)obj).fId.equals(fId));
		}

		@Override
		public int hashCode() { return super.baseHashCode() ^ (fId == null ? 0 : fId.hashCode()); }
	}

	/**
	 * Context representing a thread. 
	 */
    @Immutable
    protected class MIThreadDMC extends AbstractDMContext
    implements IThreadDMContext
    {
    	/**
    	 * ID used by GDB to refer to threads.
    	 */
    	private final String fId;

    	/**
    	 * Constructor for the context.  It should not be called directly by clients.
    	 * Instead clients should call {@link MIProcesses#createThreadContext}
    	 * to create instances of this context based on the thread ID.
    	 * <p/>
    	 * 
    	 * @param sessionId Session that this context belongs to.
    	 * @param processDmc The process that this thread belongs to.
    	 * @param id thread identifier.
    	 */
    	protected MIThreadDMC(String sessionId, IProcessDMContext processDmc, String id) {
			super(sessionId, processDmc == null ? new IDMContext[0] : new IDMContext[] { processDmc });
    		fId = id;
    	}

    	/**
    	 * Returns the thread identifier of this context.
    	 * @return
    	 */
    	public String getId(){ return fId; }

    	@Override
    	public String toString() { return baseToString() + ".OSthread[" + fId + "]"; }  //$NON-NLS-1$ //$NON-NLS-2$

		@Override
		public boolean equals(Object obj) {
			return super.baseEquals(obj) && 
			       (((MIThreadDMC)obj).fId == null ? fId == null : ((MIThreadDMC)obj).fId.equals(fId));
		}

		@Override
		public int hashCode() { return super.baseHashCode() ^ (fId == null ? 0 : fId.hashCode()); }
    }

    @Immutable
    protected class MIProcessDMC extends AbstractDMContext
    implements IMIProcessDMContext
    {
      	/**
    	 * ID given by the OS.
    	 */
    	private final String fId;

    	/**
    	 * Constructor for the context.  It should not be called directly by clients.
    	 * Instead clients should call {@link MIProcesses#createProcessContext}
    	 * to create instances of this context based on the PID.
    	 * <p/>
    	 * 
    	 * @param sessionId Session that this context belongs to.
         * @param controlDmc The control context parent of this process.
    	 * @param id process identifier.
    	 */
    	protected MIProcessDMC(String sessionId, MIControlDMContext controlDmc, String id) {
			super(sessionId, controlDmc == null ? new IDMContext[0] : new IDMContext[] { controlDmc });
    		fId = id;
    	}
    	
    	public String getProcId() { return fId; }

    	@Override
    	public String toString() { return baseToString() + ".proc[" + fId + "]"; }  //$NON-NLS-1$ //$NON-NLS-2$

		@Override
		public boolean equals(Object obj) {
			return super.baseEquals(obj) && 
			       (((MIProcessDMC)obj).fId == null ? fId == null : ((MIProcessDMC)obj).fId.equals(fId));
		}

		@Override
		public int hashCode() { return super.baseHashCode() ^ (fId == null ? 0 : fId.hashCode()); }
    }
    
    /*
     * The data of a corresponding thread or process.
     */
    @Immutable
    protected static class MIThreadDMData implements IThreadDMData {
    	final String fName;
    	final String fId;
    	
    	public MIThreadDMData(String name, String id) {
    		fName = name;
    		fId = id;
    	}
    	
		public String getId() { return fId; }
		public String getName() { return fName; }
		public boolean isDebuggerAttached() {
			return true;
		}
    }
    
    private AbstractMIControl fCommandControl;
	private CommandCache fCommandCache;

    public MIProcesses(DsfSession session) {
    	super(session);
    }

    /**
     * This method initializes this service.
     * 
     * @param requestMonitor
     *            The request monitor indicating the operation is finished
     */
    @Override
    public void initialize(final RequestMonitor requestMonitor) {
    	super.initialize(new RequestMonitor(getExecutor(), requestMonitor) {
    		@Override
    		protected void handleSuccess() {
    			doInitialize(requestMonitor);
			}
		});
	}
	
	/**
	 * This method initializes this service after our superclass's initialize()
	 * method succeeds.
	 * 
	 * @param requestMonitor
	 *            The call-back object to notify when this service's
	 *            initialization is done.
	 */
	private void doInitialize(RequestMonitor requestMonitor) {
        
//		// Register this service.
//		register(new String[] { IProcesses.class.getName(),
//				MIProcesses.class.getName() },
//				new Hashtable<String, String>());
		
		fCommandControl = getServicesTracker().getService(AbstractMIControl.class);
        fCommandCache = new CommandCache(getSession(), fCommandControl);
        fCommandCache.setContextAvailable(fCommandControl.getControlDMContext(), true);
        getSession().addServiceEventListener(this, null);

		requestMonitor.done();
	}

	/**
	 * This method shuts down this service. It unregisters the service, stops
	 * receiving service events, and calls the superclass shutdown() method to
	 * finish the shutdown process.
	 * 
	 * @return void
	 */
	@Override
	public void shutdown(RequestMonitor requestMonitor) {
//		unregister();
        getSession().removeServiceEventListener(this);
		super.shutdown(requestMonitor);
	}
	
	/**
	 * @return The bundle context of the plug-in to which this service belongs.
	 */
	@Override
	protected BundleContext getBundleContext() {
		return MIPlugin.getBundleContext();
	}
	
	/**
	 * Create a thread context.
	 * 
	 * @param processDmc The parent process context
	 * @param threadId The OS Id of the thread
	 */
    public IThreadDMContext createThreadContext(IProcessDMContext processDmc, String threadId) {
        return new MIThreadDMC(getSession().getId(), processDmc, threadId);
    }

	/**
	 * Create a process context.
	 * 
	 * @param pid The OS Id of the process
	 */
    public IProcessDMContext createProcessContext(MIControlDMContext controlDmc, String pid) {
        return new MIProcessDMC(getSession().getId(), controlDmc, pid);
    }
    
    /**
     * Create a executionGroup context.
     * 
     * @param processDmc The parent process context of this context
     * @param groupId The thread group id of the process
     */
    public IMIExecutionGroupDMContext createExecutionGroupContext(IProcessDMContext processDmc,
    															  String groupId) {
    	return new MIExecutionGroupDMC(getSession().getId(), processDmc, groupId);
    }

	/**
	 * This method obtains the model data for a given IThreadDMContext object
	 * which can represent a thread or a process.
	 * 
	 * @param dmc
	 *            The context for which we are requesting the data
	 * @param rm
	 *            The request monitor that will contain the requested data
	 */
	@SuppressWarnings("unchecked")
	public void getModelData(IDMContext dmc, DataRequestMonitor<?> rm) {
		if (dmc instanceof IThreadDMContext) {
			getExecutionData((IThreadDMContext) dmc, 
					(DataRequestMonitor<IThreadDMData>) rm);
		} else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid DMC type", null)); //$NON-NLS-1$
            rm.done();
		}
	}

	public void getExecutionData(IThreadDMContext dmc, final DataRequestMonitor<IThreadDMData> rm) {
		if (dmc instanceof MIProcessDMC) {
			rm.setData(new MIThreadDMData("", ((MIProcessDMC)dmc).getProcId())); //$NON-NLS-1$
			rm.done();
		} else if (dmc instanceof MIThreadDMC) {
			final MIThreadDMC threadDmc = (MIThreadDMC)dmc;
			
			IProcessDMContext procDmc = DMContexts.getAncestorOfType(dmc, IProcessDMContext.class);
	        fCommandCache.execute(new CLIInfoThreads(procDmc),
	        		new DataRequestMonitor<CLIInfoThreadsInfo>(getExecutor(), rm) {
	        	@Override
	        	protected void handleSuccess() {
	        		IThreadDMData threadData = new MIThreadDMData("", ""); //$NON-NLS-1$ //$NON-NLS-2$
	        		for (CLIInfoThreadsInfo.ThreadInfo thread : getData().getThreadInfo()) {
	        			if (thread.getId().equals(threadDmc.getId())) {
	        				threadData = new MIThreadDMData(thread.getName(), thread.getOsId());     
	        				break;
	        			}
	        		}
	        		rm.setData(threadData);
	        		rm.done();
	        	}
	        });
		} else {
			rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid DMC type", null)); //$NON-NLS-1$
			rm.done();
		}
	}
	
    public void getDebuggingContext(IThreadDMContext dmc, DataRequestMonitor<IDMContext> rm) {
		rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID,
				NOT_SUPPORTED, "Not supported", null)); //$NON-NLS-1$
		rm.done();
    }
    
    public void isDebuggerAttachSupported(IDMContext dmc, DataRequestMonitor<Boolean> rm) {
    	rm.setData(true);
    	rm.done();
    }

    public void attachDebuggerToProcess(IProcessDMContext procCtx, final DataRequestMonitor<IDMContext> rm) {
		if (procCtx instanceof IMIProcessDMContext) {
			MIControlDMContext controlDmc = DMContexts.getAncestorOfType(procCtx, MIControlDMContext.class);
			fCommandControl.queueCommand(
					new CLIAttach(controlDmc, ((IMIProcessDMContext)procCtx).getProcId()),
					new DataRequestMonitor<MIInfo>(getExecutor(), rm) {
						@Override
						protected void handleSuccess() {
							rm.setData(null);
							rm.done();
						}
					});

	    } else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Invalid process context.", null)); //$NON-NLS-1$
            rm.done();
	    }
	}
	
    public void canDetachDebuggerFromProcess(IProcessDMContext procCtx, DataRequestMonitor<Boolean> rm) {
    	rm.setData(true);
    	rm.done();
    }

    public void detachDebuggerFromProcess(IProcessDMContext procCtx, final RequestMonitor rm) {
//		if (procCtx instanceof MIProcessDMC) {
//			int pid;
//			try {
//				pid = Integer.parseInt(((MIProcessDMC)procCtx).getId());
//			} catch (NumberFormatException e) {
//	            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid process id.", null)); //$NON-NLS-1$
//	            rm.done();
//	            return;
//			}
//
//		    // The service version cannot use -target-detach because it didn't exist
//		    // in versions of GDB up to and including GDB 6.8
//			fCommandControl.queueCommand(
//					new CLIDetach(procCtx, pid),
//					new DataRequestMonitor<MIInfo>(getExecutor(), rm));
//	    } else {
//            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Invalid process context.", null)); //$NON-NLS-1$
//            rm.done();
//	    }
		rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID,
				NOT_SUPPORTED, "Not supported", null)); //$NON-NLS-1$
		rm.done();
	}

	public void canTerminate(IThreadDMContext thread, DataRequestMonitor<Boolean> rm) {
		rm.setData(true);
		rm.done();
	}

	public void isDebugNewProcessSupported(IDMContext dmc, DataRequestMonitor<Boolean> rm) {
		rm.setData(false);
		rm.done();	
	}

	public void debugNewProcess(String file, DataRequestMonitor<IProcessDMContext> rm) {
		rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID,
				NOT_SUPPORTED, "Not supported", null)); //$NON-NLS-1$
		rm.done();
	}
    
	public void getProcessesBeingDebugged(IDMContext dmc, DataRequestMonitor<IDMContext[]> rm) {
		// This service version only handles a single process to debug, therefore, we can simply
		// create the context describing this process ourselves.
		MIControlDMContext controlDmc = DMContexts.getAncestorOfType(dmc, MIControlDMContext.class);
		IMIExecutionGroupDMContext groupDmc = createExecutionGroupContext(createProcessContext(controlDmc, ""), "");//$NON-NLS-1$//$NON-NLS-2$
		rm.setData(new IContainerDMContext[] {groupDmc});
		rm.done();
	}

    public void getRunningProcesses(IDMContext dmc, final DataRequestMonitor<IProcessDMContext[]> rm) {
		rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID,
				NOT_SUPPORTED, "Not supported", null)); //$NON-NLS-1$
		rm.done();
	}

	public void isRunNewProcessSupported(IDMContext dmc, DataRequestMonitor<Boolean> rm) {
		rm.setData(false);
		rm.done();			
	}
	public void runNewProcess(String file, DataRequestMonitor<IProcessDMContext> rm) {
		rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID,
				NOT_SUPPORTED, "Not supported", null)); //$NON-NLS-1$
		rm.done();
	}

	public void terminate(IThreadDMContext thread, RequestMonitor rm) {
		rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID,
				NOT_SUPPORTED, "Not supported", null)); //$NON-NLS-1$
		rm.done();
	}
	
    @DsfServiceEventHandler
    public void eventDispatched(IResumedDMEvent e) {
        fCommandCache.setContextAvailable(e.getDMContext(), false);
        fCommandCache.setContextAvailable(fCommandControl.getControlDMContext(), true);
        if (e.getReason() != StateChangeReason.STEP) {
            fCommandCache.reset();
        }
    }


    @DsfServiceEventHandler
    public void eventDispatched(ISuspendedDMEvent e) {
        fCommandCache.setContextAvailable(e.getDMContext(), true);
        fCommandCache.reset();
    }
}
