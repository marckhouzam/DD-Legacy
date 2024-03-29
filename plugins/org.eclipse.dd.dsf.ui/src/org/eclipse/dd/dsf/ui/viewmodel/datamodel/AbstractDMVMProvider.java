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
package org.eclipse.dd.dsf.ui.viewmodel.datamodel;

import org.eclipse.dd.dsf.concurrent.ConfinedToDsfExecutor;
import org.eclipse.dd.dsf.datamodel.IDMEvent;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.dsf.ui.viewmodel.AbstractVMAdapter;
import org.eclipse.dd.dsf.ui.viewmodel.IVMNode;
import org.eclipse.dd.dsf.ui.viewmodel.update.AbstractCachingVMProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelProxy;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;
import org.eclipse.debug.internal.ui.viewers.provisional.IAsynchronousContentAdapter;
import org.eclipse.debug.internal.ui.viewers.provisional.IAsynchronousLabelAdapter;

/**
 * View model provider implements the asynchronous view model functionality for 
 * a single view.  This provider is just a holder which further delegates the
 * model provider functionality to the view model nodes that need
 * to be configured with each provider.
 * <p>
 * The view model provider, often does not provide the model for the entire 
 * view.  Rather, it needs to be able to plug in at any level in the viewer's
 * content model and provide data for a sub-tree.
 * 
 * @see IAsynchronousContentAdapter
 * @see IAsynchronousLabelAdapter
 * @see IModelProxy
 * @see IVMNode
 */
@ConfinedToDsfExecutor("fSession#getExecutor")
@SuppressWarnings("restriction")
abstract public class AbstractDMVMProvider extends AbstractCachingVMProvider
{
    private final DsfSession fSession;
    /**
     * Constructs the view model provider for given DSF session.  The 
     * constructor is thread-safe to allow VM provider to be constructed
     * synchronously when a call to getAdapter() is made on an element 
     * in a view.
     */
    public AbstractDMVMProvider(AbstractVMAdapter adapter,  IPresentationContext presentationContext, DsfSession session) {
        super(adapter, presentationContext);
        fSession = session;
    }    

    public DsfSession getSession() { return fSession; }

    /**
     * @deprecated Kept for API compatibility reasons. 
     * Events are now received and dispatched by the VM adapter.
     */
    @Deprecated
	@DsfServiceEventHandler
    public void eventDispatched(final IDMEvent<?> event) {
    }
}
