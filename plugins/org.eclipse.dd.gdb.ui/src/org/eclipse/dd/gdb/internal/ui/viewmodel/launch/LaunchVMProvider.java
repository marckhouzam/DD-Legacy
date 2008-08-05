/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson			  - Modified for new functionality	
 *******************************************************************************/
package org.eclipse.dd.gdb.internal.ui.viewmodel.launch;

import org.eclipse.dd.dsf.concurrent.ThreadSafe;
import org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.launch.AbstractLaunchVMProvider;
import org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.launch.LaunchRootVMNode;
import org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.launch.StackFramesVMNode;
import org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.launch.StandardProcessVMNode;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.dsf.ui.viewmodel.AbstractVMAdapter;
import org.eclipse.dd.dsf.ui.viewmodel.IRootVMNode;
import org.eclipse.dd.dsf.ui.viewmodel.IVMNode;
import org.eclipse.dd.mi.service.command.AbstractMIControl.BackendExitedEvent;
import org.eclipse.dd.mi.service.command.AbstractMIControl.BackendStartedEvent;
import org.eclipse.dd.mi.service.command.MIInferiorProcess.InferiorExitedDMEvent;
import org.eclipse.dd.mi.service.command.MIInferiorProcess.InferiorStartedDMEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunchesListener2;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;


/**
 * 
 */
@SuppressWarnings("restriction")
public class LaunchVMProvider extends AbstractLaunchVMProvider 
    implements IDebugEventSetListener, ILaunchesListener2
{
	@ThreadSafe
    public LaunchVMProvider(AbstractVMAdapter adapter, IPresentationContext presentationContext, DsfSession session)
    {
        super(adapter, presentationContext, session);
        
        IRootVMNode launchNode = new LaunchRootVMNode(this);
        setRootNode(launchNode);

        // Container node to contain all processes and threads
        IVMNode containerNode = new ContainerVMNode(this, getSession());
        IVMNode processesNode = new StandardProcessVMNode(this);
        addChildNodes(launchNode, new IVMNode[] { containerNode, processesNode});
        
        IVMNode threadsNode = new ThreadVMNode(this, getSession());
        addChildNodes(containerNode, new IVMNode[] { threadsNode });
        
        IVMNode stackFramesNode = new StackFramesVMNode(this, getSession());
        addChildNodes(threadsNode, new IVMNode[] { stackFramesNode });

        
        DebugPlugin.getDefault().addDebugEventListener(this);
        DebugPlugin.getDefault().getLaunchManager().addLaunchListener(this);
    }
    
    
    @Override
    public void dispose() {
        DebugPlugin.getDefault().removeDebugEventListener(this);
        DebugPlugin.getDefault().getLaunchManager().removeLaunchListener(this);
        super.dispose();
    }
    
    
    @Override
    protected boolean canSkipHandlingEvent(Object newEvent, Object eventToSkip) {
        // Never skip the process lifecycle events.
        if (eventToSkip instanceof InferiorExitedDMEvent || 
            eventToSkip instanceof InferiorStartedDMEvent ||
            eventToSkip instanceof BackendStartedEvent ||
            eventToSkip instanceof BackendExitedEvent) 
        {
            return false;
        }
        return super.canSkipHandlingEvent(newEvent, eventToSkip);
    }

}
