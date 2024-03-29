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
package org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.launch;

import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.ui.viewmodel.AbstractVMContext;
import org.eclipse.dd.dsf.ui.viewmodel.AbstractVMNode;
import org.eclipse.dd.dsf.ui.viewmodel.AbstractVMProvider;
import org.eclipse.dd.dsf.ui.viewmodel.IVMContext;
import org.eclipse.dd.dsf.ui.viewmodel.IVMNode;
import org.eclipse.dd.dsf.ui.viewmodel.VMDelta;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenCountUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IHasChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta;
import org.eclipse.debug.internal.ui.viewers.model.provisional.ModelDelta;
import org.eclipse.debug.internal.ui.viewers.provisional.ILabelRequestMonitor;
import org.eclipse.jface.viewers.TreePath;

/**
 * Layout node for the standard platform debug model IProcess object. This 
 * node requires that an ILaunch object be found as an ancestor of this node.  
 * It does not implement the label provider functionality, so the default 
 * adapters should be used to retrieve the label.  
 */
@SuppressWarnings("restriction")
public class StandardProcessVMNode extends AbstractVMNode {
    
    /**
     * VMC element implementation, it is a proxy for the IProcess class, to 
     * allow the standard label adapter to be used with this object. 
     */
    private class VMC extends AbstractVMContext
        implements IProcess 
    {
        private final IProcess fProcess;
        
        VMC(IProcess process) {
            super(StandardProcessVMNode.this);
            fProcess = process;
        }
        
        @Override
        public IVMNode getVMNode() { return StandardProcessVMNode.this; }        
        @Override
        @SuppressWarnings("unchecked")
        public Object getAdapter(Class adapter) { 
            Object vmcAdapter = super.getAdapter(adapter);
            if (vmcAdapter != null) {
                return vmcAdapter;
            }
            return fProcess.getAdapter(adapter); 
        }
        @Override
        public String toString() { return "IProcess " + fProcess.toString(); } //$NON-NLS-1$

        public String getAttribute(String key) { return fProcess.getAttribute(key); }
        public int getExitValue() throws DebugException { return fProcess.getExitValue(); }
        public String getLabel() { return fProcess.getLabel(); }
        public ILaunch getLaunch() { return fProcess.getLaunch(); }
        public IStreamsProxy getStreamsProxy() { return fProcess.getStreamsProxy(); }
        public void setAttribute(String key, String value) { fProcess.setAttribute(key, value); }
        public boolean canTerminate() { return fProcess.canTerminate(); }
        public boolean isTerminated() { return fProcess.isTerminated(); }
        public void terminate() throws DebugException { fProcess.terminate(); }
        
        @Override
        public boolean equals(Object other) { 
            return other instanceof VMC && fProcess.equals(((VMC)other).fProcess);
        }
        @Override
        public int hashCode() { return fProcess.hashCode(); }
    }

    public StandardProcessVMNode(AbstractVMProvider provider) {
        super(provider);
    }

    @Override
    public String toString() {
        return "StandardProcessVMNode";  //$NON-NLS-1$ 
    }

    public void update(IChildrenUpdate[] updates) {
        for (IChildrenUpdate update : updates) {
            ILaunch launch = findLaunch(update.getElementPath());
            if (launch == null) {
                // There is no launch in the parent of this node.  This means that the 
                // layout is misconfigured.  
                assert false; 
                update.done();
                continue;
            }
            
            /*
             * Assume that the process objects are stored within the launch, and 
             * retrieve them on dispatch thread.  
             */
            IProcess[] processes = launch.getProcesses();
            for (int i = 0; i < processes.length; i++) {
                update.setChild(new VMC(processes[i]), i);
            }
            update.done();
        }
    }
    
    public void update(final IChildrenCountUpdate[] updates) {
        for (IChildrenCountUpdate update : updates) {
            if (!checkUpdate(update)) continue;
            ILaunch launch = findLaunch(update.getElementPath());
            if (launch == null) {
                assert false;
                update.setChildCount(0);
                update.done();
                return;
            }
    
            update.setChildCount(launch.getProcesses().length);
            update.done();
        }
    }

    // @see org.eclipse.dd.dsf.ui.viewmodel.IViewModelLayoutNode#hasElements(org.eclipse.dd.dsf.ui.viewmodel.IVMContext, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
    public void update(IHasChildrenUpdate[] updates) {
        for (IHasChildrenUpdate update : updates) {
            ILaunch launch = findLaunch(update.getElementPath());
            if (launch == null) {
                assert false;
                update.setHasChilren(false);
                update.done();
                return;
            }
    
            update.setHasChilren(launch.getProcesses().length != 0);
            update.done();
        }
    }

    // @see org.eclipse.dd.dsf.ui.viewmodel.IViewModelLayoutNode#retrieveLabel(org.eclipse.dd.dsf.ui.viewmodel.IVMContext, org.eclipse.debug.internal.ui.viewers.provisional.ILabelRequestMonitor)
    public void updateLabel(IVMContext vmc, ILabelRequestMonitor result, String[] columns) {
        
        /*
         * The implementation of IAdapterFactory that uses this node should not
         * register a label adapter for IProcess.  This will cause the default
         * label provider to be used instead, and this method should then never
         * be called.
         */
        assert false;  
        result.done();
    }

    /**
     * Recursively searches the VMC for Launch VMC, and returns its ILaunch.  
     * Returns null if an ILaunch is not found.
     */
    private ILaunch findLaunch(TreePath path) {
        for (int i = path.getSegmentCount() - 1; i >= 0; i--) {
            if (path.getSegment(i) instanceof ILaunch) {
                return (ILaunch)path.getSegment(i);
            }
        }
        return null;
    }
    
    public int getDeltaFlags(Object e) {
        int myFlags = 0;
        if (e instanceof DebugEvent) {
            DebugEvent de = (DebugEvent)e;
            if ( de.getSource() instanceof IProcess && 
                 (de.getKind() == DebugEvent.CHANGE || 
                  de.getKind() == DebugEvent.CREATE || 
                  de.getKind() == DebugEvent.TERMINATE) )
            {
                myFlags = IModelDelta.STATE;
            }
        }
        return myFlags;
    }
    
    public void buildDelta(Object e, VMDelta parent, int nodeOffset, RequestMonitor requestMonitor) {
        if (e instanceof DebugEvent && ((DebugEvent)e).getSource() instanceof IProcess) {
            DebugEvent de = (DebugEvent)e;
            if (de.getKind() == DebugEvent.CHANGE) {
                handleChange(de, parent);
            } else if (de.getKind() == DebugEvent.CREATE) {
                handleCreate(de, parent);
            } else if (de.getKind() == DebugEvent.TERMINATE) {
                handleTerminate(de, parent);
            }
            /*
             * No other node should need to process events related to process.
             * Therefore, just invoke the request monitor without calling super.buildDelta().
             */
        } 
        requestMonitor.done();
    }
    
    protected void handleChange(DebugEvent event, ModelDelta parent) {
        parent.addNode(new VMC((IProcess)event.getSource()), IModelDelta.STATE);
    }

    protected void handleCreate(DebugEvent event, ModelDelta parent) {
        parent.setFlags(parent.getFlags() | IModelDelta.CONTENT);
    }

    protected void handleTerminate(DebugEvent event, ModelDelta parent) {
        handleChange(event, parent);
    }

}
