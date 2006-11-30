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
package org.eclipse.dd.dsf.debug.ui.viewmodel.launch;

import java.util.List;

import org.eclipse.dd.dsf.concurrent.Done;
import org.eclipse.dd.dsf.concurrent.GetDataDone;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.datamodel.IDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl;
import org.eclipse.dd.dsf.debug.service.IStack;
import org.eclipse.dd.dsf.debug.service.IStepQueueManager;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.StateChangeReason;
import org.eclipse.dd.dsf.debug.service.IStack.IFrameDMContext;
import org.eclipse.dd.dsf.debug.service.IStack.IFrameDMData;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.dsf.ui.viewmodel.AbstractVMProvider;
import org.eclipse.dd.dsf.ui.viewmodel.IVMContext;
import org.eclipse.dd.dsf.ui.viewmodel.VMDelta;
import org.eclipse.dd.dsf.ui.viewmodel.dm.AbstractDMVMLayoutNode;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IHasChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.ILabelUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;

@SuppressWarnings("restriction")
public class StackFramesLayoutNode extends AbstractDMVMLayoutNode<IStack.IFrameDMData> {
    
    public IVMContext[] fCachedOldFrameVMCs;
    
    public StackFramesLayoutNode(AbstractVMProvider provider, DsfSession session) {
        super(provider, session, IStack.IFrameDMContext.class);
    }
    
    @Override
    protected void updateHasElementsInSessionThread(IHasChildrenUpdate[] updates) {
        
        for (IHasChildrenUpdate update : updates) {
            if (!checkService(IStack.class, null, update)) return;
            
            IExecutionDMContext execDmc = findDmcInPath(update.getElementPath(), IExecutionDMContext.class);
            if (execDmc == null) {
                handleFailedUpdate(update);
                return;
            }          
            
            update.setHasChilren(getServicesTracker().getService(IStack.class).isStackAvailable(execDmc));
            update.done();
        }
    }

    @Override
    protected void updateElementsInSessionThread(final IChildrenUpdate update) {
        if (!checkService(IStack.class, null, update)) return;
        
        final IExecutionDMContext execDmc = findDmcInPath(update.getElementPath(), IExecutionDMContext.class);
        if (execDmc == null) {
            handleFailedUpdate(update);
            return;
        }          
        
        getServicesTracker().getService(IStack.class).getFrames(
            execDmc, 
            new GetDataDone<IFrameDMContext[]>() { 
                public void run() {
                    if (!getStatus().isOK()) {
                        // Failed to retrieve frames.  If we are stepping, we 
                        // might still be able to retrieve just the top stack 
                        // frame, which would still be useful in Debug View.
                        if (!checkService(IRunControl.class, null, update)) return;                        
                        if (getServicesTracker().getService(IRunControl.class).isStepping(execDmc)) {
                            getElementsTopStackFrameOnly(update);
                        } else {
                            update.done();
                        }
                        return;
                    }
                    // Store the VMC element array, in case we need to use it when 
                    fCachedOldFrameVMCs = dmcs2vmcs(getData());
                    for (int i = 0; i < fCachedOldFrameVMCs.length; i++) update.setChild(fCachedOldFrameVMCs[i], i);
                    update.done();
                }
            });
    }
    
    /**
     * Retrieves teh list of VMC elements for a full stack trace, but with only 
     * the top stack frame being retrieved from the service.  The rest of the 
     * frames are retrieved from the cache or omitted.  
     * @see #getElements(IVMContext, GetDataDone)
     */
    private void getElementsTopStackFrameOnly(final IChildrenUpdate update) {
        final IExecutionDMContext execDmc = findDmcInPath(update.getElementPath(), IExecutionDMContext.class);
        if (execDmc == null) {
            handleFailedUpdate(update);
            return;
        }          

        getServicesTracker().getService(IStack.class).getTopFrame(
            execDmc, 
            new GetDataDone<IFrameDMContext>() { public void run() {
                if (!getStatus().isOK()) {
                    handleFailedUpdate(update);
                    return;
                }
                
                IVMContext topFrameVmc = new DMVMContext(getData());
                
                update.setChild(topFrameVmc, 0);
                // If there are old frames cached, use them and only substitute the top frame object. Otherwise, create
                // an array of VMCs with just the top frame.
                if (fCachedOldFrameVMCs != null && fCachedOldFrameVMCs.length >= 1) {
                    fCachedOldFrameVMCs[0] = topFrameVmc;
                    for (int i = 0; i < fCachedOldFrameVMCs.length; i++) update.setChild(fCachedOldFrameVMCs[i], i);
                } else {
                    update.setChild(topFrameVmc, 0);
                }
                update.done();
            }});
    }
    
    @Override
    protected void fillColumnLabel(IDMContext<IFrameDMData> dmContext, IFrameDMData dmData, String columnId, int idx,
                                   ILabelUpdate update) 
    {
        if (idx != 0) return;
        
        final IExecutionDMContext execDmc = findDmcInPath(update.getElementPath(), IExecutionDMContext.class);
        IRunControl runControlService = getServicesTracker().getService(IRunControl.class); 
        IStepQueueManager stepQueueMgrService = getServicesTracker().getService(IStepQueueManager.class); 
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
            label.append(fCachedOldFrameVMCs.length - dmData.getLevel());
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
        label.append(dmData.getAddress());
            
        // Set the label to the result listener
        update.setLabel(label.toString(), 0);
    }

    protected void handleFailedUpdate(IViewerUpdate update) {
        if (update instanceof ILabelUpdate) {
            // Avoid repainting the label if it's not available.  This only slows
            // down the display.
        } else {
            super.handleFailedUpdate(update);
        }
    }

    
    @Override
    protected int getNodeDeltaFlagsForDMEvent(org.eclipse.dd.dsf.datamodel.IDMEvent<?> e) {
        // This node generates delta if the timers have changed, or if the 
        // label has changed.
        if (e instanceof IRunControl.ISuspendedDMEvent) {
            return IModelDelta.CONTENT | IModelDelta.EXPAND | IModelDelta.SELECT;
        } else if (e instanceof IRunControl.IResumedDMEvent) {
            if (((IRunControl.IResumedDMEvent)e).getReason() == StateChangeReason.STEP) {
                return IModelDelta.STATE;
            } else {
                return IModelDelta.CONTENT;
            }
        } else if (e instanceof IStepQueueManager.ISteppingTimedOutEvent) {
            return IModelDelta.CONTENT;
        }
        return 0;
    }

    @Override
    protected void buildDeltaForDMEvent(final IDMEvent<?> e, final VMDelta parent, final int nodeOffset, final Done done) {
        if (e instanceof IRunControl.ISuspendedDMEvent) {
            buildDeltaForSuspendedEvent((IRunControl.ISuspendedDMEvent)e, parent, nodeOffset, done);
        } else if (e instanceof IRunControl.IResumedDMEvent) {
            buildDeltaForResumedEvent((IRunControl.IResumedDMEvent)e, parent, nodeOffset, done);
        } else if (e instanceof IStepQueueManager.ISteppingTimedOutEvent) {
            buildDeltaForSteppingTimedOutEvent((IStepQueueManager.ISteppingTimedOutEvent)e, parent, nodeOffset, done);
        } else {
            // Call super-class to build sub-node delta's.
            super.buildDeltaForDMEvent(e, parent, nodeOffset, done);
        }
    }
    
    private void buildDeltaForSuspendedEvent(final IRunControl.ISuspendedDMEvent e, final VMDelta parent, final int nodeOffset, final Done done) {
        IRunControl runControlService = getServicesTracker().getService(IRunControl.class); 
        IStack stackService = getServicesTracker().getService(IStack.class);
        if (stackService == null || runControlService == null) {
            // Required services have not initialized yet.  Ignore the event.
            super.buildDeltaForDMEvent(e, parent, nodeOffset, done);
            return;
        }          
        
        // Refresh the whole list of stack frames unless the target is already stepping the next command.  In 
        // which case, the refresh will occur when the stepping sequence slows down or stops.  Trying to
        // refresh the whole stack trace with every step would slow down stepping too much.
        if (!runControlService.isStepping(e.getDMContext())) {
            parent.addFlags(IModelDelta.CONTENT);
        }
        
        // Always expand the thread node to show the stack frames.
        parent.addFlags(IModelDelta.EXPAND);

        // Retrieve the list of stack frames, and mark the top frame to be selected.  
        getElementsTopStackFrameOnly(
            new ElementsUpdate(
                new GetDataDone<List<Object>>() { 
                    public void run() {
                        if (getStatus().isOK() && getData().size() != 0) {
                            parent.addNode( getData().get(0), IModelDelta.SELECT | IModelDelta.STATE);
                            // If second frame is available repaint it, so that a "..." appears.  This gives a better
                            // impression that the frames are not up-to date.
                            if (getData().size() >= 2) {
                                parent.addNode( getData().get(1), IModelDelta.STATE);
                            }
                        }                        
                        // Even in case of errors, call super-class to complete building of the delta.
                        StackFramesLayoutNode.super.buildDeltaForDMEvent(e, parent, nodeOffset, done);
                    }
                },
                parent)
            );
    }
    
    private void buildDeltaForResumedEvent(final IRunControl.IResumedDMEvent e, final VMDelta parent, final int nodeOffset, final Done done) {
        IStack stackService = getServicesTracker().getService(IStack.class);
        if (stackService == null) {
            // Required services have not initialized yet.  Ignore the event.
            super.buildDeltaForDMEvent(e, parent, nodeOffset, done);
            return;
        }          

        IRunControl.IResumedDMEvent resumedEvent = (IRunControl.IResumedDMEvent)e; 
        if (resumedEvent.getReason() != StateChangeReason.STEP) {
            // Refresh the list of stack frames only if the run operation is not a step.  Also, clear the list
            // of cached frames.
            parent.addFlags(IModelDelta.CONTENT);
            fCachedOldFrameVMCs = null;
        }
        super.buildDeltaForDMEvent(e, parent, nodeOffset, done);
    }

    private void buildDeltaForSteppingTimedOutEvent(final IStepQueueManager.ISteppingTimedOutEvent e, final VMDelta parent, final int nodeOffset, final Done done) {
        // Repaint the stack frame images to have the running symbol.
        parent.addFlags(IModelDelta.CONTENT);
        super.buildDeltaForDMEvent(e, parent, nodeOffset, done);
    }
}
