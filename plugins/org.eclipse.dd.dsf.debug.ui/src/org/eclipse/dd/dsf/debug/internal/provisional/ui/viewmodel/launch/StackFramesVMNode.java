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
package org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.launch;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DsfRunnable;
import org.eclipse.dd.dsf.concurrent.IDsfStatusConstants;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl;
import org.eclipse.dd.dsf.debug.service.IStack;
import org.eclipse.dd.dsf.debug.service.StepQueueManager;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerSuspendedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IResumedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.ISuspendedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.StateChangeReason;
import org.eclipse.dd.dsf.debug.service.IStack.IFrameDMContext;
import org.eclipse.dd.dsf.debug.service.IStack.IFrameDMData;
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
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementCompareRequest;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementLabelProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementMementoProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementMementoRequest;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IHasChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.ILabelUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.ui.IMemento;

@SuppressWarnings("restriction")
public class StackFramesVMNode extends AbstractDMVMNode 
    implements IElementLabelProvider, IElementMementoProvider
{
    
    public IVMContext[] fCachedOldFrameVMCs;
    
    public StackFramesVMNode(AbstractDMVMProvider provider, DsfSession session) {
        super(provider, session, IStack.IFrameDMContext.class);
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.ui.viewmodel.datamodel.AbstractDMVMNode#updateHasElementsInSessionThread(org.eclipse.debug.internal.ui.viewers.model.provisional.IHasChildrenUpdate)
     */
    @Override
    protected void updateHasElementsInSessionThread(IHasChildrenUpdate update) {
        IRunControl runControl = getServicesTracker().getService(IRunControl.class);
        IExecutionDMContext execCtx = findDmcInPath(update.getViewerInput(), update.getElementPath(), IExecutionDMContext.class);
        if (runControl == null || execCtx == null) {
            handleFailedUpdate(update);
            return;
        }
        
        update.setHasChilren(runControl.isSuspended(execCtx) || runControl.isStepping(execCtx));
        update.done();
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.ui.viewmodel.datamodel.AbstractDMVMNode#updateElementsInSessionThread(org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenUpdate)
     */
    @Override
    protected void updateElementsInSessionThread(final IChildrenUpdate update) {
    	
        if ( getServicesTracker().getService(IStack.class) == null ) {
                handleFailedUpdate(update);
                return;
        }
        
        final IExecutionDMContext execDmc = findDmcInPath(update.getViewerInput(), update.getElementPath(), IExecutionDMContext.class);
        if (execDmc == null) {
            handleFailedUpdate(update);
            return;
        }          
        
        getServicesTracker().getService(IStack.class).getFrames(
            execDmc, 
            new ViewerDataRequestMonitor<IFrameDMContext[]>(getSession().getExecutor(), update) { 
                @Override
                public void handleCompleted() {
                    if (!isSuccess()) {
                        // Failed to retrieve frames.  If we are stepping, we 
                        // might still be able to retrieve just the top stack 
                        // frame, which would still be useful in Debug View.
                        if ( getServicesTracker().getService(IRunControl.class) == null ) {
                        	handleFailedUpdate(update);
                        	return;
                        }
                        if (getServicesTracker().getService(IRunControl.class).isStepping(execDmc)) {
                            getElementsTopStackFrameOnly(update);
                        } else {
                            update.done();
                        }
                        return;
                    }
                    // Store the VMC element array, in case we need to use it when 
                    fCachedOldFrameVMCs = dmcs2vmcs(getData());
                    for (int i = 0; i < fCachedOldFrameVMCs.length; i++)
                    	update.setChild(fCachedOldFrameVMCs[i], i);
                    update.done();
                }
            });
    }
    
    /**
     * Retrieves teh list of VMC elements for a full stack trace, but with only 
     * the top stack frame being retrieved from the service.  The rest of the 
     * frames are retrieved from the cache or omitted.  
     * @see #getElements(IVMContext, DataRequestMonitor)
     */
    private void getElementsTopStackFrameOnly(final IChildrenUpdate update) {
        final IExecutionDMContext execDmc = findDmcInPath(update.getViewerInput(), update.getElementPath(), IExecutionDMContext.class);
        if (execDmc == null) {
            handleFailedUpdate(update);
            return;
        }          

        try {
            getSession().getExecutor().execute(new DsfRunnable() {
                public void run() {
                	if ( getServicesTracker().getService(IStack.class) == null ) {
                		handleFailedUpdate(update);
                		return;
                	}
                
                    getServicesTracker().getService(IStack.class).getTopFrame(
                        execDmc, 
                        new ViewerDataRequestMonitor<IFrameDMContext>(getExecutor(), update) { 
                            @Override
                            public void handleCompleted() {
                                if (!isSuccess()) {
                                    handleFailedUpdate(update);
                                    return;
                                }
                                
                                IVMContext topFrameVmc = createVMContext(getData());
                                
                                update.setChild(topFrameVmc, 0);
                                // If there are old frames cached, use them and only substitute the top frame object. Otherwise, create
                                // an array of VMCs with just the top frame.
                                if (fCachedOldFrameVMCs != null && fCachedOldFrameVMCs.length >= 1) {
                                    fCachedOldFrameVMCs[0] = topFrameVmc;
                                    for (int i = 0; i < fCachedOldFrameVMCs.length; i++) 
                                    	update.setChild(fCachedOldFrameVMCs[i], i);
                                } else {
                                    update.setChild(topFrameVmc, 0);
                                }
                                update.done();
                            }
                        });
                }
            });
        } catch (RejectedExecutionException e) {
            update.done();
        }
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.debug.internal.ui.viewers.model.provisional.IElementLabelProvider#update(org.eclipse.debug.internal.ui.viewers.model.provisional.ILabelUpdate[])
     */
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

    protected void updateLabelInSessionThread(ILabelUpdate[] updates) {
        for (final ILabelUpdate update : updates) {
            final IFrameDMContext dmc = findDmcInPath(update.getViewerInput(), update.getElementPath(), IFrameDMContext.class);
            
            if ( dmc == null ) {
            	handleFailedUpdate(update);
            	continue;
            }
            if ( getServicesTracker().getService(IStack.class) == null ) {
            	handleFailedUpdate(update);
            	continue;
            }
        
            
            getDMVMProvider().getModelData(
                this, update, 
                getServicesTracker().getService(IStack.class, null),
                dmc, 
                new ViewerDataRequestMonitor<IFrameDMData>(getSession().getExecutor(), update) { 
                    @Override
                    protected void handleCompleted() {
                        /*
                         * Check that the request was evaluated and data is still
                         * valid.  The request could fail if the state of the 
                         * service changed during the request, but the view model
                         * has not been updated yet.
                         */ 
                        if (!isSuccess()) {
                            assert getStatus().isOK() || 
                                   getStatus().getCode() != IDsfStatusConstants.INTERNAL_ERROR || 
                                   getStatus().getCode() != IDsfStatusConstants.NOT_SUPPORTED;
                            handleFailedUpdate(update);
                            return;
                        }
                        
                        /*
                         * If columns are configured, call the protected methods to 
                         * fill in column values.  
                         */
                        String[] localColumns = update.getColumnIds();
                        if (localColumns == null) localColumns = new String[] { null };
                        
                        for (int i = 0; i < localColumns.length; i++) {
                            fillColumnLabel(dmc, getData(), localColumns[i], i, update);
                        }
                        update.done();
                    }
                },
                getExecutor());
        }
    }

    protected void fillColumnLabel(IFrameDMContext dmContext, IFrameDMData dmData, String columnId, int idx, ILabelUpdate update) 
    {
        if (idx != 0) return;
        
        final IExecutionDMContext execDmc = findDmcInPath(update.getViewerInput(), update.getElementPath(), IExecutionDMContext.class);
        IRunControl runControlService = getServicesTracker().getService(IRunControl.class); 
        StepQueueManager stepQueueMgrService = getServicesTracker().getService(StepQueueManager.class); 
        if (execDmc == null || runControlService == null || stepQueueMgrService == null) return;
        
        String imageKey = null;
        if (runControlService.isSuspended(execDmc) || 
            (runControlService.isStepping(execDmc) && !stepQueueMgrService.isSteppingTimedOut(execDmc)))
        {
            imageKey = IDebugUIConstants.IMG_OBJS_STACKFRAME;
        } else {
            imageKey = IDebugUIConstants.IMG_OBJS_STACKFRAME_RUNNING;
        }            
        update.setImageDescriptor(DebugUITools.getImageDescriptor(imageKey), 0);
        
        //
        // Finally, if all goes well, set the label.
        //
        StringBuilder label = new StringBuilder();
        
        // Add frame number (if total number of frames in known)
        if (fCachedOldFrameVMCs != null) {
            label.append(fCachedOldFrameVMCs.length - dmContext.getLevel());
        }
        
        // Add the function name
        if (dmData.getFunction() != null && dmData.getFunction().length() != 0) { 
            label.append(" "); //$NON-NLS-1$
            label.append(dmData.getFunction());
            label.append("()"); //$NON-NLS-1$
        }
        
        // Add full file name
        if (dmData.getFile() != null && dmData.getFile().length() != 0) {
            label.append(" at "); //$NON-NLS-1$
            label.append(dmData.getFile());
        }
        
        // Add line number 
        if (dmData.getLine() >= 0) {
            label.append(":"); //$NON-NLS-1$
            label.append(dmData.getLine());
            label.append(" "); //$NON-NLS-1$
        }
        
        // Add the address
        if (dmData.getAddress() != null) {
            label.append("- 0x" + dmData.getAddress().toString(16)); //$NON-NLS-1$
        }
            
        // Set the label to the result listener
        update.setLabel(label.toString(), 0);
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.ui.viewmodel.AbstractVMNode#handleFailedUpdate(org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate)
     */
    @Override
    protected void handleFailedUpdate(IViewerUpdate update) {
        if (update instanceof ILabelUpdate) {
            update.done();
            // Avoid repainting the label if it's not available.  This only slows
            // down the display.
        } else {
            super.handleFailedUpdate(update);
        }
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.ui.viewmodel.datamodel.AbstractDMVMNode#getContextsForEvent(org.eclipse.dd.dsf.ui.viewmodel.VMDelta, java.lang.Object, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
     */
    @Override
    public void getContextsForEvent(final VMDelta parentDelta, Object e, final DataRequestMonitor<IVMContext[]> rm) {
        if (e instanceof ModelProxyInstalledEvent) {
            // Retrieve the list of stack frames, and mark the top frame to be selected.  
            getElementsTopStackFrameOnly(
                new VMChildrenUpdate(
                    parentDelta, getVMProvider().getPresentationContext(), -1, -1,
                    new DataRequestMonitor<List<Object>>(getExecutor(), rm) { 
                        @Override
                        public void handleCompleted() {
                            if (isSuccess() && getData().size() != 0) {
                                rm.setData(new IVMContext[] { (IVMContext)getData().get(0) });
                            } else {
                                // In case of errors, return an empty set of frames.
                                rm.setData(new IVMContext[0]);
                            }
                            rm.done();
                        }
                    })
                );
            return;
        }
        super.getContextsForEvent(parentDelta, e, rm);
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.ui.viewmodel.IVMNode#getDeltaFlags(java.lang.Object)
     */
    public int getDeltaFlags(Object e) {
        // This node generates delta if the timers have changed, or if the 
        // label has changed.
        if (e instanceof ISuspendedDMEvent) {
            return IModelDelta.CONTENT | IModelDelta.EXPAND | IModelDelta.SELECT;
        } else if (e instanceof IResumedDMEvent) {
            if (((IResumedDMEvent)e).getReason() == StateChangeReason.STEP) {
                return IModelDelta.STATE;
            } else {
                return IModelDelta.CONTENT;
            }
        } else if (e instanceof StepQueueManager.ISteppingTimedOutEvent) {
            return IModelDelta.CONTENT;
        } else if (e instanceof ModelProxyInstalledEvent) {
            return IModelDelta.SELECT | IModelDelta.EXPAND;
        }

        return IModelDelta.NO_CHANGE;
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.ui.viewmodel.IVMNode#buildDelta(java.lang.Object, org.eclipse.dd.dsf.ui.viewmodel.VMDelta, int, org.eclipse.dd.dsf.concurrent.RequestMonitor)
     */
    public void buildDelta(final Object e, final VMDelta parent, final int nodeOffset, final RequestMonitor rm) {
        if (e instanceof IContainerSuspendedDMEvent) {
            IContainerSuspendedDMEvent csEvent = (IContainerSuspendedDMEvent)e;
            
            IExecutionDMContext triggeringCtx = csEvent.getTriggeringContexts().length != 0 
                ? csEvent.getTriggeringContexts()[0] : null;
                
            if (parent.getElement() instanceof IDMVMContext) {
                IExecutionDMContext threadDmc = null;
                threadDmc = DMContexts.getAncestorOfType( ((IDMVMContext)parent.getElement()).getDMContext(), IExecutionDMContext.class);
                buildDeltaForSuspendedEvent((ISuspendedDMEvent)e, threadDmc, triggeringCtx, parent, nodeOffset, rm);
            } else {
                rm.done();
            }
        } else if (e instanceof ISuspendedDMEvent) {
            IExecutionDMContext execDmc = ((ISuspendedDMEvent)e).getDMContext();
            buildDeltaForSuspendedEvent((ISuspendedDMEvent)e, execDmc, execDmc, parent, nodeOffset, rm);
        } else if (e instanceof IResumedDMEvent) {
            buildDeltaForResumedEvent((IResumedDMEvent)e, parent, nodeOffset, rm);
        } else if (e instanceof StepQueueManager.ISteppingTimedOutEvent) {
            buildDeltaForSteppingTimedOutEvent((StepQueueManager.ISteppingTimedOutEvent)e, parent, nodeOffset, rm);
        } else if (e instanceof ModelProxyInstalledEvent) {
            buildDeltaForModelProxyInstalledEvent(parent, nodeOffset, rm);
        } else {
            rm.done();
        }
    }
    
    private void buildDeltaForSuspendedEvent(final ISuspendedDMEvent e, final IExecutionDMContext executionCtx, final IExecutionDMContext triggeringCtx, final VMDelta parentDelta, final int nodeOffset, final RequestMonitor rm) {
        IRunControl runControlService = getServicesTracker().getService(IRunControl.class); 
        IStack stackService = getServicesTracker().getService(IStack.class);
        if (stackService == null || runControlService == null) {
            // Required services have not initialized yet.  Ignore the event.
            rm.done();
            return;
        }          
        
        // Refresh the whole list of stack frames unless the target is already stepping the next command.  In 
        // which case, the refresh will occur when the stepping sequence slows down or stops.  Trying to
        // refresh the whole stack trace with every step would slow down stepping too much.
        if (triggeringCtx == null || !runControlService.isStepping(triggeringCtx)) {
            parentDelta.setFlags(parentDelta.getFlags() | IModelDelta.CONTENT);
        }
        
        // Check if we are building a delta for the thread that triggered the event.
        // Only then expand the stack frames and select the top one.
        if (executionCtx.equals(triggeringCtx)) {
            // Always expand the thread node to show the stack frames.
            parentDelta.setFlags(parentDelta.getFlags() | IModelDelta.EXPAND);
    
            // Retrieve the list of stack frames, and mark the top frame to be selected.  
            getElementsTopStackFrameOnly(
                new VMChildrenUpdate(
                    parentDelta, getVMProvider().getPresentationContext(), -1, -1,
                    new DataRequestMonitor<List<Object>>(getExecutor(), rm) { 
                        @Override
                        public void handleCompleted() {
                            if (isSuccess() && getData().size() != 0) {
                                parentDelta.addNode( getData().get(0), 0, IModelDelta.SELECT | IModelDelta.STATE);
                                // If second frame is available repaint it, so that a "..." appears.  This gives a better
                                // impression that the frames are not up-to date.
                                if (getData().size() >= 2) {
                                    parentDelta.addNode( getData().get(1), 1, IModelDelta.STATE);
                                }
                            }                        
                            // Even in case of errors, complete the request monitor.
                            rm.done();
                        }
                    })
                );
        } else {
            rm.done();
        }
    }
    
    private void buildDeltaForResumedEvent(final IResumedDMEvent e, final VMDelta parentDelta, final int nodeOffset, final RequestMonitor rm) {
        IStack stackService = getServicesTracker().getService(IStack.class);
        if (stackService == null) {
            // Required services have not initialized yet.  Ignore the event.
            rm.done();
            return;
        }          

        IResumedDMEvent resumedEvent = e; 
        if (resumedEvent.getReason() != StateChangeReason.STEP) {
            // Refresh the list of stack frames only if the run operation is not a step.  Also, clear the list
            // of cached frames.
            parentDelta.setFlags(parentDelta.getFlags() | IModelDelta.CONTENT);
            fCachedOldFrameVMCs = null;
        }
        rm.done();
    }

    private void buildDeltaForSteppingTimedOutEvent(final StepQueueManager.ISteppingTimedOutEvent e, final VMDelta parentDelta, final int nodeOffset, final RequestMonitor rm) {
        // Repaint the stack frame images to have the running symbol.
        parentDelta.setFlags(parentDelta.getFlags() | IModelDelta.CONTENT);
        rm.done();
    }
    
    private void buildDeltaForModelProxyInstalledEvent(final VMDelta parentDelta, final int nodeOffset, final RequestMonitor rm) {
        // Retrieve the list of stack frames, and mark the top frame to be selected.  
        getElementsTopStackFrameOnly(
            new VMChildrenUpdate(
                parentDelta, getVMProvider().getPresentationContext(), -1, -1,
                new DataRequestMonitor<List<Object>>(getExecutor(), rm) { 
                    @Override
                    public void handleCompleted() {
                        if (isSuccess() && getData().size() != 0) {
                            parentDelta.addNode( getData().get(0), 0, IModelDelta.SELECT | IModelDelta.EXPAND);
                        }                        
                        rm.done();
                    }
                })
            );
    }

    private String produceFrameElementName( String viewName , IFrameDMContext frame ) {
    	/*
    	 *  We are addressing Bugzilla 211490 which wants the Register View  to keep the same expanded
    	 *  state for registers for stack frames within the same thread. Different  threads could have
    	 *  different register sets ( e.g. one thread may have floating point & another may not ). But
    	 *  within a thread we are enforcing  the assumption that the register  sets will be the same.  
    	 *  So we make a more convenient work flow by keeping the same expansion when selecting amount
    	 *  stack frames within the same thread. We accomplish this by only differentiating by  adding
    	 *  the level for the Expression/Variables view. Otherwise we do not delineate based on  which
    	 *  view and this captures the Register View in its filter.
		 */
    	if ( viewName.startsWith(IDebugUIConstants.ID_VARIABLE_VIEW)   ||
    	     viewName.startsWith(IDebugUIConstants.ID_EXPRESSION_VIEW)    )
    	{
    		return "Frame." + frame.getLevel() + "." + frame.getSessionId(); //$NON-NLS-1$ //$NON-NLS-2$
    	}
    	else {
    		return "Frame" + frame.getSessionId(); //$NON-NLS-1$
    	}
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.debug.internal.ui.viewers.model.provisional.IElementMementoProvider#compareElements(org.eclipse.debug.internal.ui.viewers.model.provisional.IElementCompareRequest[])
     */
    public void compareElements(IElementCompareRequest[] requests) {
        
        for ( IElementCompareRequest request : requests ) {
        	
            Object element = request.getElement();
            IMemento memento = request.getMemento();
            String mementoName = memento.getString("STACK_FRAME_MEMENTO_NAME"); //$NON-NLS-1$
            
            if (mementoName != null) {
                if (element instanceof IDMVMContext) {
                	
                    IDMContext dmc = ((IDMVMContext)element).getDMContext();
                    
                    if ( dmc instanceof IFrameDMContext) {
                    	
                    	String elementName = produceFrameElementName( request.getPresentationContext().getId(), (IFrameDMContext) dmc );
                    	request.setEqual( elementName.equals( mementoName ) );
                    } 
                }
            }
            request.done();
        }
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.debug.internal.ui.viewers.model.provisional.IElementMementoProvider#encodeElements(org.eclipse.debug.internal.ui.viewers.model.provisional.IElementMementoRequest[])
     */
    public void encodeElements(IElementMementoRequest[] requests) {
    	
    	for ( IElementMementoRequest request : requests ) {
    		
            Object element = request.getElement();
            IMemento memento = request.getMemento();
            
            if (element instanceof IDMVMContext) {

            	IDMContext dmc = ((IDMVMContext)element).getDMContext();

            	if ( dmc instanceof IFrameDMContext) {

            		String elementName = produceFrameElementName( request.getPresentationContext().getId(), (IFrameDMContext) dmc );
            		memento.putString("STACK_FRAME_MEMENTO_NAME", elementName); //$NON-NLS-1$
            	} 
            }
            request.done();
        }
    }
}
