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
package org.eclipse.dd.dsf.ui.viewmodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.CountingRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DsfRunnable;
import org.eclipse.dd.dsf.concurrent.IDsfStatusConstants;
import org.eclipse.dd.dsf.concurrent.ImmediateExecutor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.concurrent.ThreadSafe;
import org.eclipse.dd.dsf.internal.ui.DsfUIPlugin;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenCountUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IColumnPresentation;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IHasChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelProxy;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerInputUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate;

/** 
 * Base implementation for View Model Adapters.  The implementation uses
 * its own single-thread executor for communicating with providers and 
 * layout nodes. 
 */
@ThreadSafe
@SuppressWarnings("restriction")
abstract public class AbstractVMAdapter implements IVMAdapterExtension
{
 
	private boolean fDisposed;

    private final Map<IPresentationContext, IVMProvider> fViewModelProviders = 
        Collections.synchronizedMap( new HashMap<IPresentationContext, IVMProvider>() );

    /**
     * Constructor for the View Model session.  It is tempting to have the 
     * adapter register itself here with the session as the model adapter, but
     * that would mean that the adapter might get accessed on another thread
     * even before the deriving class is fully constructed.  So it it better
     * to have the owner of this object register it with the session.
     * @param session
     */
    public AbstractVMAdapter() {
    }    

    @ThreadSafe
    public IVMProvider getVMProvider(IPresentationContext context) {
        synchronized(fViewModelProviders) {
            if (fDisposed) return null;

            IVMProvider provider = fViewModelProviders.get(context);
            if (provider == null) {
                provider = createViewModelProvider(context);
                if (provider != null) {
                    fViewModelProviders.put(context, provider);
                }
            }
            return provider;
        }
    }

    /**
     * {@inheritDoc}
     * 
	 * @since 1.1
	 */
    public IVMProvider[] getActiveProviders() {
        synchronized(fViewModelProviders) {
            return fViewModelProviders.values().toArray(new IVMProvider[fViewModelProviders.size()]);
        }
    }

    public void dispose() {
        IVMProvider[] providers = new IVMProvider[0]; 
        synchronized(fViewModelProviders) {
            providers = fViewModelProviders.values().toArray(new IVMProvider[fViewModelProviders.size()]);
            fViewModelProviders.clear();            
            fDisposed = true;
        }
        
        for (final IVMProvider provider : providers) {
            try {
                provider.getExecutor().execute(new Runnable() {
                    public void run() {
                        provider.dispose();
                    }
                });
            } catch (RejectedExecutionException e) {
                // Not much we can do at this point.
            }            
        }
    }
    
    /**
	 * @return whether this VM adapter is disposed.
	 * 
     * @since 1.1
	 */
	public boolean isDisposed() {
		return fDisposed;
	}

    public void update(IHasChildrenUpdate[] updates) {
    	handleUpdate(updates);
    }
    
    public void update(IChildrenCountUpdate[] updates) {
    	handleUpdate(updates);
    }
    
    public void update(final IChildrenUpdate[] updates) {
    	handleUpdate(updates);
    }
    
    private void handleUpdate(IViewerUpdate[] updates) {
        IVMProvider provider = getVMProvider(updates[0].getPresentationContext());
        if (provider != null) {
            updateProvider(provider, updates);
        } else {
            for (IViewerUpdate update : updates) {
                update.setStatus(new Status(IStatus.ERROR, DsfUIPlugin.PLUGIN_ID, IDsfStatusConstants.INTERNAL_ERROR, 
                    "No model provider for update " + update, null)); //$NON-NLS-1$
                update.done();
            }
        }
    }

    private void updateProvider(final IVMProvider provider, final IViewerUpdate[] updates) {
        try {
            provider.getExecutor().execute(new Runnable() {
                public void run() {
                    if (updates instanceof IHasChildrenUpdate[]) {
                        provider.update((IHasChildrenUpdate[])updates);
                    } else if (updates instanceof IChildrenCountUpdate[]) {
                        provider.update((IChildrenCountUpdate[])updates);
                    } else if (updates instanceof IChildrenUpdate[]) {
                        provider.update((IChildrenUpdate[])updates);
                    }  
                }
            });
        } catch (RejectedExecutionException e) {
            for (IViewerUpdate update : updates) {
                update.setStatus(new Status(IStatus.ERROR, DsfUIPlugin.PLUGIN_ID, IDsfStatusConstants.INTERNAL_ERROR, 
                    "Display is disposed, cannot complete update " + update, null)); //$NON-NLS-1$
                update.done();
            }                
        }            
    }
    
    public IModelProxy createModelProxy(Object element, IPresentationContext context) {
        IVMProvider provider = getVMProvider(context);
        if (provider != null) {
            return provider.createModelProxy(element, context);
        }
        return null;
    }
    
    public IColumnPresentation createColumnPresentation(IPresentationContext context, Object element) {
        final IVMProvider provider = getVMProvider(context);
        if (provider != null) {
            return provider.createColumnPresentation(context, element);
        }
        return null;
    }
    
    public String getColumnPresentationId(IPresentationContext context, Object element) {
        final IVMProvider provider = getVMProvider(context);
        if (provider != null) {
            return provider.getColumnPresentationId(context, element);
        }
        return null;
    }


    public void update(IViewerInputUpdate update) {
        final IVMProvider provider = getVMProvider(update.getPresentationContext());
        if (provider != null) {
            provider.update(update);
        }
    }

    /**
     * Creates a new View Model Provider for given presentation context.  Returns null
     * if the presentation context is not supported.
     */
    @ThreadSafe    
    abstract protected IVMProvider createViewModelProvider(IPresentationContext context);
    
	/**
	 * Dispatch given event to VM providers interested in events.
	 * 
	 * @since 1.1
	 */
	protected final void handleEvent(final Object event) {
    	final List<IVMEventListener> eventListeners = new ArrayList<IVMEventListener>();

    	aboutToHandleEvent(event);
    	
		for (IVMProvider vmProvider : getActiveProviders()) {
			if (vmProvider instanceof IVMEventListener) {
				eventListeners.add((IVMEventListener)vmProvider);
			}
		}
	
		if (!eventListeners.isEmpty()) {
			final CountingRequestMonitor crm = new CountingRequestMonitor(ImmediateExecutor.getInstance(), null) {
				@Override
				protected void handleCompleted() {
					if (isDisposed()) {
						return;
					}
                    doneHandleEvent(event);
				}
			};

			int count = 0;
			for (final IVMEventListener vmEventListener : eventListeners) {
			    RequestMonitor listenerRm = null;
			    if (vmEventListener.shouldWaitHandleEventToComplete()) {
			        listenerRm = crm;
			        count++;
			    } else {
			        // Create a dummy executor for the handling of this event.
			        listenerRm = new RequestMonitor(ImmediateExecutor.getInstance(), null);
			    }
			    final RequestMonitor finalListenerRm = listenerRm;
			    vmEventListener.getExecutor().execute(new DsfRunnable() {
					public void run() {
						vmEventListener.handleEvent(event, finalListenerRm);
					}});
			}
            crm.setDoneCount(count);
		} else {
		    doneHandleEvent(event);
		}
	}

    /**
     * Given event is about to be handled.
     * 
     * @param event
     * 
     * @since 1.1
     */
    protected void aboutToHandleEvent(final Object event) {
    }

    /**
     * Given event has been processed by all VM event listeners.
     * 
     * @param event
     * 
     * @since 1.1
     */
    protected void doneHandleEvent(final Object event) {
    }
}
