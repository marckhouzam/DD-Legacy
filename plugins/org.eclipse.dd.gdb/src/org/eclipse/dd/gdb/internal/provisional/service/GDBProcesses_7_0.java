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
package org.eclipse.dd.gdb.internal.provisional.service;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.Immutable;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.AbstractDMContext;
import org.eclipse.dd.dsf.datamodel.AbstractDMEvent;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.debug.service.IProcesses;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerResumedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerSuspendedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExitedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.IResumedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.IStartedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.ISuspendedDMEvent;
import org.eclipse.dd.dsf.debug.service.command.CommandCache;
import org.eclipse.dd.dsf.service.AbstractDsfService;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.gdb.internal.GdbPlugin;
import org.eclipse.dd.gdb.internal.provisional.service.command.GDBControl;
import org.eclipse.dd.mi.internal.MIPlugin;
import org.eclipse.dd.mi.service.IMIExecutionDMContext;
import org.eclipse.dd.mi.service.IMIExecutionGroupDMContext;
import org.eclipse.dd.mi.service.IMIProcessDMContext;
import org.eclipse.dd.mi.service.IMIProcesses;
import org.eclipse.dd.mi.service.command.MIControlDMContext;
import org.eclipse.dd.mi.service.command.commands.MIListThreadGroups;
import org.eclipse.dd.mi.service.command.commands.MITargetAttach;
import org.eclipse.dd.mi.service.command.commands.MITargetDetach;
import org.eclipse.dd.mi.service.command.commands.MIThreadInfo;
import org.eclipse.dd.mi.service.command.events.IMIDMEvent;
import org.eclipse.dd.mi.service.command.events.MIThreadCreatedEvent;
import org.eclipse.dd.mi.service.command.events.MIThreadGroupCreatedEvent;
import org.eclipse.dd.mi.service.command.events.MIThreadGroupExitedEvent;
import org.eclipse.dd.mi.service.command.output.IThreadInfo;
import org.eclipse.dd.mi.service.command.output.MIInfo;
import org.eclipse.dd.mi.service.command.output.MIListThreadGroupsInfo;
import org.eclipse.dd.mi.service.command.output.MIThreadInfoInfo;
import org.eclipse.dd.mi.service.command.output.MIListThreadGroupsInfo.IThreadGroupInfo;
import org.osgi.framework.BundleContext;

/**
 * This class implements the IProcesses interface for GDB 7.0
 * Actually, I'm not sure what the next version of GDB will be, so technically,
 * it is the one after GDB 6.8, as long as it contains multi-process support,
 * which really mean it supports the new -list-thread-groups command.
 * 
 */
public class GDBProcesses_7_0 extends AbstractDsfService implements IMIProcesses {

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
	 * Context representing a thread in GDB/MI
	 */
	@Immutable
	private static class MIExecutionDMC extends AbstractDMContext 
	implements IMIExecutionDMContext
	{
		/**
		 * String ID that is used to identify the thread in the GDB/MI protocol.
		 */
		private final String fThreadId;

		/**
		 * Constructor for the context.  It should not be called directly by clients.
		 * Instead clients should call {@link IMIProcesses#createExecutionContext()}
		 * to create instances of this context based on the thread ID.
		 * <p/>
		 * 
		 * @param sessionId Session that this context belongs to.
		 * @param containerDmc The container that this context belongs to.
		 * @param threadDmc The thread context parents of this context.
		 * @param threadId GDB/MI thread identifier.
		 */
        protected MIExecutionDMC(String sessionId, IContainerDMContext containerDmc, IThreadDMContext threadDmc, String threadId) {
            super(sessionId, 
                  containerDmc == null && threadDmc == null ? new IDMContext[0] :  
                      containerDmc == null ? new IDMContext[] { threadDmc } :
                          threadDmc == null ? new IDMContext[] { containerDmc } :
                              new IDMContext[] { containerDmc, threadDmc });
            fThreadId = threadId;
        }

		/**
		 * Returns the GDB/MI thread identifier of this context.
		 * @return
		 */
		public int getThreadId(){
			try {
				return Integer.parseInt(fThreadId);
			} catch (NumberFormatException e) {
			}
			
			return 0;
		}

		public String getId(){
			return fThreadId;
		}

		@Override
		public String toString() { return baseToString() + ".thread[" + fThreadId + "]"; }  //$NON-NLS-1$ //$NON-NLS-2$

		@Override
		public boolean equals(Object obj) {
			return super.baseEquals(obj) && ((MIExecutionDMC)obj).fThreadId.equals(fThreadId);
		}

		@Override
		public int hashCode() { return super.baseHashCode() ^ fThreadId.hashCode(); }
	}

	/**
	 * Context representing a thread group of GDB/MI. 
	 */
    @Immutable
	private static class MIExecutionGroupDMC extends AbstractDMContext
	implements IMIExecutionGroupDMContext
	{
		/**
		 * String ID that is used to identify the thread group in the GDB/MI protocol.
		 */
		private final String fId;

		/**
		 * Constructor for the context.  It should not be called directly by clients.
		 * Instead clients should call {@link IMIProcesses#createExecutionGroupContext
		 * to create instances of this context based on the group name.
		 * 
		 * @param sessionId Session that this context belongs to.
		 * @param processDmc The process context that is the parent of this context.
		 * @param groupId GDB/MI thread group identifier.
		 */
		public MIExecutionGroupDMC(String sessionId, IProcessDMContext processDmc, String groupId) {
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
    private static class MIThreadDMC extends AbstractDMContext
    implements IThreadDMContext
    {
    	/**
    	 * ID used by GDB to refer to threads.
    	 */
    	private final String fId;

    	/**
    	 * Constructor for the context.  It should not be called directly by clients.
    	 * Instead clients should call {@link IMIProcesses#createThreadContext}
    	 * to create instances of this context based on the thread ID.
    	 * <p/>
    	 * 
    	 * @param sessionId Session that this context belongs to.
    	 * @param processDmc The process that this thread belongs to.
    	 * @param id thread identifier.
    	 */
    	public MIThreadDMC(String sessionId, IProcessDMContext processDmc, String id) {
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
    private static class MIProcessDMC extends AbstractDMContext
    implements IMIProcessDMContext
    {
      	/**
    	 * ID given by the OS.
    	 */
    	private final String fId;

    	/**
    	 * Constructor for the context.  It should not be called directly by clients.
    	 * Instead clients should call {@link IMIProcesses#createProcessContext}
    	 * to create instances of this context based on the PID.
    	 * <p/>
    	 * 
    	 * @param sessionId Session that this context belongs to.
         * @param controlDmc The control context parent of this process.
    	 * @param id process identifier.
    	 */
    	public MIProcessDMC(String sessionId, MIControlDMContext controlDmc, String id) {
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
    
    /**
     * Event indicating that an execution group (debugged process) has started.  This event
     * implements the {@link IStartedMDEvent} from the IRunControl service. 
     */
    public static class ExecutionGroupStartedDMEvent extends AbstractDMEvent<IExecutionDMContext> 
        implements IStartedDMEvent
    {
        public ExecutionGroupStartedDMEvent(IMIExecutionGroupDMContext context) {
            super(context);
        }
    }        
    
    /**
     * Event indicating that an execution group is no longer being debugged.  This event
     * implements the {@link IExitedMDEvent} from the IRunControl service. 
     */
    public static class ExecutionGroupExitedDMEvent extends AbstractDMEvent<IExecutionDMContext> 
        implements IExitedDMEvent
    {
        public ExecutionGroupExitedDMEvent(IContainerDMContext context) {
            super(context);
        }
    }        

    private GDBControl fCommandControl;
    
    // A cache for commands about the threadGroups
	private CommandCache fContainerCommandCache;

	//A cache for commands about the threads
	private CommandCache fThreadCommandCache;

    // A map of process id to process names.  It is filled when we get all the processes that are running
    private Map<String, String> fProcessNames = new HashMap<String, String>();
    // A map of thread id to thread group id.  We use this to find out to which threadGroup a thread belongs.
    private Map<String, String> fGroupIdMap   = new HashMap<String, String>();
	
    private static final String FAKE_THREAD_ID = "0"; //$NON-NLS-1$

    public GDBProcesses_7_0(DsfSession session) {
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
        
		fCommandControl = getServicesTracker().getService(GDBControl.class);
        fContainerCommandCache = new CommandCache(getSession(), fCommandControl);
        fContainerCommandCache.setContextAvailable(fCommandControl.getControlDMContext(), true);
        fThreadCommandCache = new CommandCache(getSession(), fCommandControl);
        fThreadCommandCache.setContextAvailable(fCommandControl.getControlDMContext(), true);

        getSession().addServiceEventListener(this, null);
        
		// Register this service.
		register(new String[] { IProcesses.class.getName(),
				IMIProcesses.class.getName(),
				GDBProcesses_7_0.class.getName() },
				new Hashtable<String, String>());
        
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
		unregister();
        getSession().removeServiceEventListener(this);
		super.shutdown(requestMonitor);
	}
	
	/**
	 * @return The bundle context of the plug-in to which this service belongs.
	 */
	@Override
	protected BundleContext getBundleContext() {
		return GdbPlugin.getBundleContext();
	}
	
   public IThreadDMContext createThreadContext(IProcessDMContext processDmc, String threadId) {
        return new MIThreadDMC(getSession().getId(), processDmc, threadId);
    }

    public IProcessDMContext createProcessContext(MIControlDMContext controlDmc, String pid) {
        return new MIProcessDMC(getSession().getId(), controlDmc, pid);
    }
    
    public IMIExecutionDMContext createExecutionContext(IContainerDMContext containerDmc, 
                                                        IThreadDMContext threadDmc, 
                                                        String threadId) {
    	return new MIExecutionDMC(getSession().getId(), containerDmc, threadDmc, threadId);
    }

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
            rm.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid DMC type", null)); //$NON-NLS-1$
            rm.done();
		}
	}

	public void getExecutionData(IThreadDMContext dmc, final DataRequestMonitor<IThreadDMData> rm) {
		if (dmc instanceof IMIProcessDMContext) {
			String id = ((IMIProcessDMContext)dmc).getProcId();
			String name = fProcessNames.get(id);
			if (name == null) name = "Unknown name"; //$NON-NLS-1$
			rm.setData(new MIThreadDMData(name, id));
			rm.done();
		} else if (dmc instanceof MIThreadDMC) {
			final MIThreadDMC threadDmc = (MIThreadDMC)dmc;
			
			MIControlDMContext controlDmc = DMContexts.getAncestorOfType(dmc, MIControlDMContext.class);
	        fThreadCommandCache.execute(new MIThreadInfo(controlDmc, threadDmc.getId()),
	        		new DataRequestMonitor<MIThreadInfoInfo>(getExecutor(), rm) {
        	        	@Override
        	        	protected void handleSuccess() {
        	        		IThreadDMData threadData = new MIThreadDMData("", ""); //$NON-NLS-1$ //$NON-NLS-2$
        	        		if (getData().getThreadList().length != 0) {
        	        			IThreadInfo thread = getData().getThreadList()[0];
        	        			if (thread.getThreadId().equals(threadDmc.getId())) {
        	        				threadData = new MIThreadDMData("", thread.getOsId());      //$NON-NLS-1$
        	        			}
        	        		}
        	        		
        	        		rm.setData(threadData);
        	        		rm.done();
        	        	}
	        });
		} else {
			rm.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid DMC type", null)); //$NON-NLS-1$
			rm.done();
		}
	}
	
    public void getDebuggingContext(IThreadDMContext dmc, DataRequestMonitor<IDMContext> rm) {
    	if (dmc instanceof MIProcessDMC) {
    		MIProcessDMC procDmc = (MIProcessDMC)dmc;
    		rm.setData(createExecutionGroupContext(procDmc, procDmc.getProcId()));
    	} else if (dmc instanceof MIThreadDMC) {
    		MIThreadDMC threadDmc = (MIThreadDMC)dmc;
    		IMIProcessDMContext procDmc = DMContexts.getAncestorOfType(dmc, IMIProcessDMContext.class);
    		IMIExecutionGroupDMContext groupDmc = createExecutionGroupContext(procDmc, procDmc.getProcId()); 
    		rm.setData(createExecutionContext(groupDmc, threadDmc, threadDmc.getId()));
    	} else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Invalid thread context.", null)); //$NON-NLS-1$
    	}

    	rm.done();
    }
    
    public void isDebuggerAttachSupported(IDMContext dmc, DataRequestMonitor<Boolean> rm) {
    	rm.setData(fCommandControl.getIsAttachSession());
    	rm.done();
    }

    public void attachDebuggerToProcess(final IProcessDMContext procCtx, final DataRequestMonitor<IDMContext> rm) {
		if (procCtx instanceof IMIProcessDMContext) {
			MIControlDMContext controlDmc = DMContexts.getAncestorOfType(procCtx, MIControlDMContext.class);
			fCommandControl.queueCommand(
					new MITargetAttach(controlDmc, ((IMIProcessDMContext)procCtx).getProcId()),
					new DataRequestMonitor<MIInfo>(getExecutor(), rm) {
						@Override
						protected void handleSuccess() {
							fCommandControl.setConnected(true);

							IMIExecutionGroupDMContext groupDmc = createExecutionGroupContext(procCtx,
									                                         ((IMIProcessDMContext)procCtx).getProcId());
			                rm.setData(groupDmc);
							rm.done();
						}
					});

	    } else {
            rm.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, INTERNAL_ERROR, "Invalid process context.", null)); //$NON-NLS-1$
            rm.done();
	    }
	}
	
    public void canDetachDebuggerFromProcess(IDMContext dmc, DataRequestMonitor<Boolean> rm) {
    	rm.setData(fCommandControl.getIsAttachSession() && fCommandControl.isConnected());
    	rm.done();
    }

    public void detachDebuggerFromProcess(final IDMContext dmc, final RequestMonitor rm) {
    	MIControlDMContext controlDmc = DMContexts.getAncestorOfType(dmc, MIControlDMContext.class);
    	IMIProcessDMContext procDmc = DMContexts.getAncestorOfType(dmc, IMIProcessDMContext.class);

    	if (controlDmc != null && procDmc != null) {
    		fCommandControl.queueCommand(
    				new MITargetDetach(controlDmc, procDmc.getProcId()),
    				new DataRequestMonitor<MIInfo>(getExecutor(), rm) {
    					@Override
    					protected void handleSuccess() {
    						// only if it is the last detach
    						fCommandControl.setConnected(false);
    						rm.done();
    					}
    				});
    	} else {
            rm.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, INTERNAL_ERROR, "Invalid context.", null)); //$NON-NLS-1$
            rm.done();
	    }
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
		rm.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID,
				NOT_SUPPORTED, "Not supported", null)); //$NON-NLS-1$
		rm.done();
	}
    
	public void getProcessesBeingDebugged(final IDMContext dmc, final DataRequestMonitor<IDMContext[]> rm) {
//		MIInferiorProcess inferiorProcess = fCommandControl.getInferiorProcess();
//		if (fCommandControl.isConnected() &&
//			inferiorProcess != null && 
//			inferiorProcess.getState() != MIInferiorProcess.State.TERMINATED) {
		
			final MIControlDMContext controlDmc = DMContexts.getAncestorOfType(dmc, MIControlDMContext.class);
			final IMIExecutionGroupDMContext groupDmc = DMContexts.getAncestorOfType(dmc, IMIExecutionGroupDMContext.class);
			if (groupDmc != null) {
				fThreadCommandCache.execute(
						new MIListThreadGroups(controlDmc, groupDmc.getGroupId()),
						new DataRequestMonitor<MIListThreadGroupsInfo>(getExecutor(), rm) {
							@Override
							protected void handleSuccess() {
								rm.setData(makeExecutionDMCs(groupDmc, getData().getThreadInfo().getThreadList()));
								rm.done();
							}
						});
			} else {
				fContainerCommandCache.execute(
						new MIListThreadGroups(controlDmc),
						new DataRequestMonitor<MIListThreadGroupsInfo>(getExecutor(), rm) {
							@Override
							protected void handleSuccess() {
								rm.setData(makeExecutionGroupDMCs(controlDmc, getData().getGroupList()));
								rm.done();
							}
						});
			}
//		} else {
//			rm.setData(new IDMContext[0]);
//			rm.done();
//		}
	}

	private IExecutionDMContext[] makeExecutionDMCs(IContainerDMContext containerDmc, IThreadInfo[] threadInfos) {
		final IProcessDMContext procDmc = DMContexts.getAncestorOfType(containerDmc, IProcessDMContext.class);

		if (threadInfos.length == 0) {
			// Main thread always exist even if it is not reported by GDB.
			// So create thread-id = 0 when no thread is reported.
			// This hack is necessary to prevent AbstractMIControl from issuing a thread-select
			// because it doesn't work if the application was not compiled with pthread.
			return new IMIExecutionDMContext[]{createExecutionContext(containerDmc, 
					                                                  createThreadContext(procDmc, FAKE_THREAD_ID),
					                                                  FAKE_THREAD_ID)};
		} else {
			IExecutionDMContext[] executionDmcs = new IMIExecutionDMContext[threadInfos.length];
			for (int i = 0; i < threadInfos.length; i++) {
				String threadId = threadInfos[i].getThreadId();
				executionDmcs[i] = createExecutionContext(containerDmc, 
						                                  createThreadContext(procDmc, threadId),
						                                  threadId);
			}
			return executionDmcs;
		}
	}
	
	private IMIExecutionGroupDMContext[] makeExecutionGroupDMCs(MIControlDMContext controlDmc, IThreadGroupInfo[] groups) {
		IProcessDMContext[] procDmcs = makeProcessDMCs(controlDmc, groups);
		
		IMIExecutionGroupDMContext[] groupDmcs = new IMIExecutionGroupDMContext[groups.length];
		for (int i = 0; i < procDmcs.length; i++) {
			String groupId = groups[i].getGroupId();
			IProcessDMContext procDmc = createProcessContext(controlDmc, groupId); 
			groupDmcs[i] = createExecutionGroupContext(procDmc, groupId);
		}
		return groupDmcs;
	}

    public void getRunningProcesses(IDMContext dmc, final DataRequestMonitor<IProcessDMContext[]> rm) {
		final MIControlDMContext controlDmc = DMContexts.getAncestorOfType(dmc, MIControlDMContext.class);

		if (controlDmc != null) {
			// Don't cache this command since the list can change at any time.
			fCommandControl.queueCommand(
				new MIListThreadGroups(controlDmc, true),
				new DataRequestMonitor<MIListThreadGroupsInfo>(getExecutor(), rm) {
					@Override
					protected void handleCompleted() {
						if (isSuccess()) {
							for (IThreadGroupInfo groupInfo : getData().getGroupList()) {
								fProcessNames.put(groupInfo.getPid(), groupInfo.getName());
							}
							rm.setData(makeProcessDMCs(controlDmc, getData().getGroupList()));
						} else {
							rm.setData(new IProcessDMContext[0]);
						}
						rm.done();
					}
				});
		} else {
			rm.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, INTERNAL_ERROR, "Invalid context.", null)); //$NON-NLS-1$
			rm.done();
		}

	}

	private IProcessDMContext[] makeProcessDMCs(MIControlDMContext controlDmc, IThreadGroupInfo[] processes) {
		IProcessDMContext[] procDmcs = new IMIProcessDMContext[processes.length];
		for (int i=0; i<procDmcs.length; i++) {
			procDmcs[i] = createProcessContext(controlDmc, processes[i].getGroupId()); 
		}
		return procDmcs;
	}

	public void isRunNewProcessSupported(IDMContext dmc, DataRequestMonitor<Boolean> rm) {
		rm.setData(false);
		rm.done();			
	}
	public void runNewProcess(String file, DataRequestMonitor<IProcessDMContext> rm) {
		rm.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID,
				NOT_SUPPORTED, "Not supported", null)); //$NON-NLS-1$
		rm.done();
	}

	public void terminate(IThreadDMContext thread, RequestMonitor rm) {
		if (thread instanceof IMIProcessDMContext) {
			fCommandControl.terminate(rm);
	    } else {
            rm.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, INTERNAL_ERROR, "Invalid process context.", null)); //$NON-NLS-1$
            rm.done();
	    }
	}
	
    public String getExecutionGroupIdFromThread(String threadId) {
    	String groupId = fGroupIdMap.get(threadId);
    	if (groupId == null) return ""; //$NON-NLS-1$
    	else return groupId;
    }
    
    @DsfServiceEventHandler
    public void eventDispatched(final MIThreadGroupCreatedEvent e) {
    	IProcessDMContext procDmc = e.getDMContext();
        IMIExecutionGroupDMContext groupDmc = e.getGroupId() != null ? createExecutionGroupContext(procDmc, e.getGroupId()) : null;
        getSession().dispatchEvent(new ExecutionGroupStartedDMEvent(groupDmc), getProperties());
    }

    @DsfServiceEventHandler
    public void eventDispatched(final MIThreadGroupExitedEvent e) {
    	IProcessDMContext procDmc = e.getDMContext();
        IMIExecutionGroupDMContext groupDmc = e.getGroupId() != null ? createExecutionGroupContext(procDmc, e.getGroupId()) : null;
		getSession().dispatchEvent(new ExecutionGroupExitedDMEvent(groupDmc), getProperties());

    }
    
    @DsfServiceEventHandler
    public void eventDispatched(IResumedDMEvent e) {
    	if (e instanceof IContainerResumedDMEvent) {
    		// This will happen in all-stop mode
    		fContainerCommandCache.setContextAvailable(e.getDMContext(), false);
    		fThreadCommandCache.setContextAvailable(e.getDMContext(), false);
    	} else {
       		// This will happen in non-stop mode
    		// Keep target available for Container commands
       	}
    }


    @DsfServiceEventHandler
    public void eventDispatched(ISuspendedDMEvent e) {
       	if (e instanceof IContainerSuspendedDMEvent) {
    		// This will happen in all-stop mode
       		fContainerCommandCache.setContextAvailable(e.getDMContext(), true);
       		fThreadCommandCache.setContextAvailable(e.getDMContext(), true);
       	} else {
       		// This will happen in non-stop mode
       	}
    }
    
    // Event handler when a thread or threadGroup starts
    @DsfServiceEventHandler
    public void eventDispatched(IStartedDMEvent e) {
    	if (e instanceof ExecutionGroupStartedDMEvent) {
    		fContainerCommandCache.reset();
    	} else {
    		// HACK figure out the thread and the group ids
    		// I had to HACK GDB for this
    		if (e instanceof IMIDMEvent) {
        		String threadId = ((MIThreadCreatedEvent)((IMIDMEvent)e).getMIEvent()).getStrId();
        		IContainerDMContext ctx = ((MIThreadCreatedEvent)((IMIDMEvent)e).getMIEvent()).getDMContext();
        		if (ctx instanceof IMIExecutionGroupDMContext) {
        			String groupId = ((IMIExecutionGroupDMContext)ctx).getGroupId();
              		 fGroupIdMap.put(threadId, groupId);
        		}
    		}
    		// END HACK
    		fThreadCommandCache.reset();
    	}
	}

    // Event handler when a thread or a threadGroup exits
    @DsfServiceEventHandler
    public void eventDispatched(IExitedDMEvent e) {
    	if (e instanceof ExecutionGroupExitedDMEvent) {
    		fContainerCommandCache.reset();
    	} else {
    		// HACK figure out the thread and the group ids
    		// I had to HACK GDB for this
    		if (e instanceof IMIDMEvent) {
        		String threadId = ((MIThreadCreatedEvent)((IMIDMEvent)e).getMIEvent()).getStrId();
                fGroupIdMap.remove(threadId);
    		}
    		// END HACK
 
    		fThreadCommandCache.reset();
    	}
    }
}