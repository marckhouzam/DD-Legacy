/*******************************************************************************
 * Copyright (c) 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - Ted Williams - initial API and implementation
 *******************************************************************************/

package org.eclipse.dd.dsf.debug.internal.provisional.ui.memory;

import org.eclipse.dd.dsf.debug.internal.provisional.model.IMemoryBlockUpdatePolicyProvider;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.model.IMemoryBlock;
import org.eclipse.debug.internal.ui.views.memory.MemoryView;
import org.eclipse.debug.ui.memory.IMemoryRendering;
import org.eclipse.debug.ui.memory.IMemoryRenderingContainer;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IViewActionDelegate;
import org.eclipse.ui.IViewPart;

@SuppressWarnings("restriction")
public class RefreshAction implements IViewActionDelegate {

	private IMemoryBlock fMemoryBlock = null;
	
	private MemoryView fView;
	
	public void init(IViewPart view) {
		fView = (MemoryView) view;
	}

	public void run(IAction action) {
		
		if(fMemoryBlock instanceof IMemoryBlockUpdatePolicyProvider)
		{
			((IMemoryBlockUpdatePolicyProvider) fMemoryBlock).clearCache();
			IMemoryRenderingContainer containers[] = fView.getMemoryRenderingContainers();
			for(int i = 0; i < containers.length; i++)
			{
				IMemoryRendering renderings[] = containers[i].getRenderings();
				for(int j = 0; j < renderings.length; j++)
				{
					if (renderings[j].getControl() instanceof IDebugEventSetListener)
					{
						((IDebugEventSetListener) renderings[j].getControl()).handleDebugEvents(
							new DebugEvent[] { new DebugEvent(fMemoryBlock, DebugEvent.CHANGE) } );
					}
				}
			}
		}
	}

	public void selectionChanged(IAction action, ISelection selection) {
		fMemoryBlock = null;
    	action.setEnabled(false);
    	if(selection instanceof IStructuredSelection)
    	{
    		if(((IStructuredSelection) selection).getFirstElement() instanceof IMemoryBlock)
    		{
    			fMemoryBlock = (IMemoryBlock) ((IStructuredSelection) selection).getFirstElement();
    			action.setEnabled(true);
    		}
    		else if(((IStructuredSelection) selection).getFirstElement() instanceof IMemoryRendering)
    		{
    			fMemoryBlock = ((IMemoryRendering) ((IStructuredSelection) selection).getFirstElement()).getMemoryBlock();
    			action.setEnabled(true);
    		}
    	}
	}

}
