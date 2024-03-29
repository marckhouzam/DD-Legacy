/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson 		  - Modified for multi threaded functionality
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.launch;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DsfRunnable;
import org.eclipse.dd.dsf.concurrent.IDsfStatusConstants;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.datamodel.IDMEvent;
import org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.SteppingController.SteppingTimedOutEvent;
import org.eclipse.dd.dsf.debug.internal.ui.DsfDebugUIPlugin;
import org.eclipse.dd.dsf.debug.service.IRunControl;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerResumedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerSuspendedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IResumedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.ISuspendedDMEvent;
import org.eclipse.dd.dsf.debug.service.StepQueueManager.ISteppingTimedOutEvent;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.dsf.ui.concurrent.ViewerDataRequestMonitor;
import org.eclipse.dd.dsf.ui.viewmodel.IVMContext;
import org.eclipse.dd.dsf.ui.viewmodel.ModelProxyInstalledEvent;
import org.eclipse.dd.dsf.ui.viewmodel.VMChildrenUpdate;
import org.eclipse.dd.dsf.ui.viewmodel.VMDelta;
import org.eclipse.dd.dsf.ui.viewmodel.datamodel.AbstractDMVMNode;
import org.eclipse.dd.dsf.ui.viewmodel.datamodel.AbstractDMVMProvider;
import org.eclipse.dd.dsf.ui.viewmodel.datamodel.IDMVMContext;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementLabelProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.ILabelUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta;


/**
 * Abstract implementation of a thread view model node.
 * Clients need to implement {@link #updateLabelInSessionThread(ILabelUpdate[])}.
 * 
 * @since 1.1
 */
@SuppressWarnings("restriction")
public abstract class AbstractThreadVMNode extends AbstractDMVMNode
    implements IElementLabelProvider
{
    public AbstractThreadVMNode(AbstractDMVMProvider provider, DsfSession session) {
        super(provider, session, IExecutionDMContext.class);
    }

	@Override
    protected void updateElementsInSessionThread(final IChildrenUpdate update) {
    	IRunControl runControl = getServicesTracker().getService(IRunControl.class);
    	final IContainerDMContext contDmc = findDmcInPath(update.getViewerInput(), update.getElementPath(), IContainerDMContext.class);
    	if (runControl == null || contDmc == null) {
    		handleFailedUpdate(update);
    		return;
    	}

    	runControl.getExecutionContexts(contDmc,
    			new ViewerDataRequestMonitor<IExecutionDMContext[]>(getSession().getExecutor(), update){
    				@Override
    				public void handleCompleted() {
    					if (!isSuccess()) {
    						handleFailedUpdate(update);
    						return;
    					}
    					fillUpdateWithVMCs(update, getData());
    					update.done();
    				}
    			});
    }


    public void update(final ILabelUpdate[] updates) {
        try {
            getSession().getExecutor().execute(new DsfRunnable() {
                public void run() {
                    updateLabelInSessionThread(updates);
                }});
        } catch (RejectedExecutionException e) {
            for (ILabelUpdate update : updates) {
                handleFailedUpdate(update);
            }
        }
    }

    @Override
    public void getContextsForEvent(VMDelta parentDelta, Object e, final DataRequestMonitor<IVMContext[]> rm) {
        if(e instanceof IContainerResumedDMEvent) {
            IExecutionDMContext[] triggerContexts = ((IContainerResumedDMEvent)e).getTriggeringContexts();
            if (triggerContexts.length != 0) {
                rm.setData(new IVMContext[] { createVMContext(triggerContexts[0]) });
                rm.done();
                return;
            }
        } else if(e instanceof IContainerSuspendedDMEvent) {
            IExecutionDMContext[] triggerContexts = ((IContainerSuspendedDMEvent)e).getTriggeringContexts();
            if (triggerContexts.length != 0) {
                rm.setData(new IVMContext[] { createVMContext(triggerContexts[0]) });
                rm.done();
                return;
            }
        } else if (e instanceof SteppingTimedOutEvent && 
                ((SteppingTimedOutEvent)e).getDMContext() instanceof IContainerDMContext) 
     {
          // The timed out event occured on a container and not on a thread.  Do not
          // return a context for this event, which will force the view model to generate
          // a delta for all the threads.
          rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfStatusConstants.NOT_SUPPORTED, "", null)); //$NON-NLS-1$
          rm.done();
          return;
        } else if (e instanceof ISteppingTimedOutEvent && 
                   ((ISteppingTimedOutEvent)e).getDMContext() instanceof IContainerDMContext) 
        {
             // The timed out event occured on a container and not on a thread.  Do not
             // return a context for this event, which will force the view model to generate
             // a delta for all the threads.
             rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfStatusConstants.NOT_SUPPORTED, "", null)); //$NON-NLS-1$
             rm.done();
             return;
        } else if (e instanceof FullStackRefreshEvent &&
                ((FullStackRefreshEvent)e).getDMContext() instanceof IContainerDMContext)
        {
        	// The step sequence end event occured on a container and not on a thread.  Do not
        	// return a context for this event, which will force the view model to generate
        	// a delta for all the threads.
        	rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfStatusConstants.NOT_SUPPORTED, "", null)); //$NON-NLS-1$
        	rm.done();
        	return;
        } else if (e instanceof ModelProxyInstalledEvent) {
            getThreadVMCForModelProxyInstallEvent(
                parentDelta,
                new DataRequestMonitor<VMContextInfo>(getExecutor(), rm) {
                    @Override
                    protected void handleCompleted() {
                        if (isSuccess()) {
                            rm.setData(new IVMContext[] { getData().fVMContext });
                        } else {
                            rm.setData(new IVMContext[0]);
                        }
                        rm.done();
                    }
                });
            return;
        }
        super.getContextsForEvent(parentDelta, e, rm);
    }
    
    private static class VMContextInfo {
        final IVMContext fVMContext;
        final int fIndex;
        final boolean fIsSuspended;
        VMContextInfo(IVMContext vmContext, int index, boolean isSuspended) {
            fVMContext = vmContext;
            fIndex = index;
            fIsSuspended = isSuspended;
        }
    }
    
    private void getThreadVMCForModelProxyInstallEvent(VMDelta parentDelta, final DataRequestMonitor<VMContextInfo> rm) {
        getVMProvider().updateNode(this, new VMChildrenUpdate(
            parentDelta, getVMProvider().getPresentationContext(), -1, -1,
            new DataRequestMonitor<List<Object>>(getExecutor(), rm) {
                @Override
                protected void handleSuccess() {
                    try {
                        getSession().getExecutor().execute(new DsfRunnable() {
                            public void run() {
                                final IRunControl runControl = getServicesTracker().getService(IRunControl.class);
                                if (runControl != null) {
                                    int vmcIdx = -1;
                                    int suspendedVmcIdx = -1;
                                    
                                    for (int i = 0; i < getData().size(); i++) {
                                        if (getData().get(i) instanceof IDMVMContext) {
                                            IDMVMContext vmc = (IDMVMContext)getData().get(i);
                                            IExecutionDMContext execDmc = DMContexts.getAncestorOfType(
                                                vmc.getDMContext(), IExecutionDMContext.class);
                                            if (execDmc != null) {
                                                vmcIdx = vmcIdx < 0 ? i : vmcIdx;
                                                if (runControl.isSuspended(execDmc)) {
                                                    suspendedVmcIdx = suspendedVmcIdx < 0 ? i : suspendedVmcIdx;
                                                }
                                            }
                                        }
                                    }
                                    if (suspendedVmcIdx >= 0) {
                                        rm.setData(new VMContextInfo(
                                            (IVMContext)getData().get(suspendedVmcIdx), suspendedVmcIdx, true));
                                    } else if (vmcIdx >= 0) {
                                        rm.setData(new VMContextInfo((IVMContext)getData().get(vmcIdx), vmcIdx, false));
                                    } else {
                                        rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfStatusConstants.REQUEST_FAILED, "No threads available", null)); //$NON-NLS-1$
                                    }
                                    rm.done();
                                } else {
                                    rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfStatusConstants.REQUEST_FAILED, "No threads available", null)); //$NON-NLS-1$
                                    rm.done();
                                }
                            }
                        });
                    } catch (RejectedExecutionException e) {
                        rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfStatusConstants.NOT_SUPPORTED, "", null)); //$NON-NLS-1$
                        rm.done();
                    }
                }
            }));
    }
    
    /**
     * Perform the given label updates in the session executor thread.
     * 
     * @param updates  the pending label updates
     * @see {@link #update(ILabelUpdate[])
     */
    protected abstract void updateLabelInSessionThread(ILabelUpdate[] updates);
    
    
    public int getDeltaFlags(Object e) {
        IDMContext dmc = e instanceof IDMEvent<?> ? ((IDMEvent<?>)e).getDMContext() : null;

        if (dmc instanceof IContainerDMContext) {
            return IModelDelta.NO_CHANGE;
        } else if (e instanceof IResumedDMEvent && 
                   ((IResumedDMEvent)e).getReason() != IRunControl.StateChangeReason.STEP) 
        {
            return IModelDelta.CONTENT;            
        } else if (e instanceof ISuspendedDMEvent) {
            return IModelDelta.NO_CHANGE;
        } else if (e instanceof FullStackRefreshEvent) {
            return IModelDelta.CONTENT;
        } else if (e instanceof SteppingTimedOutEvent) {
            return IModelDelta.CONTENT;            
        } else if (e instanceof ISteppingTimedOutEvent) {
            return IModelDelta.CONTENT;            
        } else if (e instanceof ModelProxyInstalledEvent) {
            return IModelDelta.SELECT | IModelDelta.EXPAND;
        }
        return IModelDelta.NO_CHANGE;
    }

    public void buildDelta(Object e, final VMDelta parentDelta, final int nodeOffset, final RequestMonitor rm) {
        IDMContext dmc = e instanceof IDMEvent<?> ? ((IDMEvent<?>)e).getDMContext() : null;

        if(dmc instanceof IContainerDMContext) {
            // The IContainerDMContext sub-classes IExecutionDMContext.
            // Also IContainerResumedDMEvent sub-classes IResumedDMEvent and
            // IContainerSuspendedDMEvnet sub-classes ISuspendedEvent.
            // Because of this relationship, the thread VM node can be called
            // with data-model evnets for the containers.  This statement
            // filters out those event.
            rm.done();
        } else if(e instanceof IResumedDMEvent) {
            // Resumed: 
            // - If not stepping, update the thread and its content (its stack).
            // - If stepping, do nothing to avoid too many updates.  If a 
            // time-out is reached before the step completes, the 
            // ISteppingTimedOutEvent will trigger a refresh.
            if (((IResumedDMEvent)e).getReason() != IRunControl.StateChangeReason.STEP) {
                parentDelta.addNode(createVMContext(dmc), IModelDelta.CONTENT);
            }
            rm.done();
        } else if (e instanceof ISuspendedDMEvent) {
            // Container suspended.  Do nothing here to give the stack the 
            // priority in updating. The thread will update as a result of 
            // FullStackRefreshEvent. 
        	rm.done();
        } else if (e instanceof FullStackRefreshEvent) {
            // Full-stack refresh event is generated following a suspended event 
            // and a fixed delay.  Refresh the whole thread upon this event.
            parentDelta.addNode(createVMContext(dmc), IModelDelta.CONTENT);
            rm.done();
        } else if (e instanceof SteppingTimedOutEvent) {
            // Stepping time-out indicates that a step operation is taking 
            // a long time, and the view needs to be refreshed to show 
            // the user that the program is running.  
            parentDelta.addNode(createVMContext(dmc), IModelDelta.CONTENT);
            rm.done();            
        } else if (e instanceof ISteppingTimedOutEvent) {
            // Stepping time-out indicates that a step operation is taking 
            // a long time, and the view needs to be refreshed to show 
            // the user that the program is running.  
            parentDelta.addNode(createVMContext(dmc), IModelDelta.CONTENT);
            rm.done();            
        } else if (e instanceof ModelProxyInstalledEvent) {
            // Model Proxy install event is generated when the model is first 
            // populated into the view.  This happens when a new debug session
            // is started or when the view is first opened.  
            // In both cases, if there are already threads in the debug model, 
            // the desired user behavior is to show the threads and to select
            // the first thread.  
            // If the thread is suspended, do not select the thread, instead, 
            // its top stack frame will be selected.
            getThreadVMCForModelProxyInstallEvent(
                parentDelta,
                new DataRequestMonitor<VMContextInfo>(getExecutor(), rm) {
                    @Override
                    protected void handleCompleted() {
                        if (isSuccess()) {
                            parentDelta.addNode(
                                getData().fVMContext, nodeOffset + getData().fIndex,
                                IModelDelta.EXPAND | (getData().fIsSuspended ? 0 : IModelDelta.SELECT));
                        }
                        rm.done();
                    }
                });
        } else {
            
            rm.done();
        }
    }

}
