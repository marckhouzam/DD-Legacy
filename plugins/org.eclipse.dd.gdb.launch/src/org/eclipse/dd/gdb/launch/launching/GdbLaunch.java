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
package org.eclipse.dd.gdb.launch.launching;

import java.util.concurrent.ExecutionException;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.ConfinedToDsfExecutor;
import org.eclipse.dd.dsf.concurrent.DefaultDsfExecutor;
import org.eclipse.dd.dsf.concurrent.DsfExecutor;
import org.eclipse.dd.dsf.concurrent.DsfRunnable;
import org.eclipse.dd.dsf.concurrent.ImmediateExecutor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.concurrent.Sequence;
import org.eclipse.dd.dsf.concurrent.ThreadSafe;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfServicesTracker;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.dsf.service.IDsfService;
import org.eclipse.dd.gdb.launch.internal.GdbLaunchPlugin;
import org.eclipse.dd.gdb.service.command.GDBControl;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.Launch;
import org.eclipse.debug.core.model.ISourceLocator;
import org.eclipse.debug.core.model.ITerminate;

/**
 * The only object in the model that implements the traditional interfaces.
 */
@ThreadSafe
public class GdbLaunch extends Launch
    implements ITerminate
{
    private DefaultDsfExecutor fExecutor;
    private DsfSession fSession;
    private DsfServicesTracker fTracker;
    private boolean fInitialized = false;
    private boolean fShutDown = false;
    
    
    public GdbLaunch(ILaunchConfiguration launchConfiguration, String mode, ISourceLocator locator) {
        super(launchConfiguration, mode, locator);

        // Create the dispatch queue to be used by debugger control and services 
        // that belong to this launch
        final DefaultDsfExecutor dsfExecutor = new DefaultDsfExecutor(GdbLocalLaunchDelegate.GDB_DEBUG_MODEL_ID);
        dsfExecutor.prestartCoreThread();
        fExecutor = dsfExecutor;
        fSession = DsfSession.startSession(fExecutor, GdbLocalLaunchDelegate.GDB_DEBUG_MODEL_ID);
    }

    public DsfExecutor getDsfExecutor() { return fExecutor; }
    
    @ConfinedToDsfExecutor("getExecutor")
    public void initializeControl()
        throws CoreException
    {
        
        Runnable initRunnable = new DsfRunnable() { 
            public void run() {
                fTracker = new DsfServicesTracker(GdbLaunchPlugin.getBundleContext(), fSession.getId());
                fSession.addServiceEventListener(GdbLaunch.this, null);
    
                fInitialized = true;
                fireChanged();
            }
        };
        
        // Invoke the execution code and block waiting for the result.
        try {
            fExecutor.submit(initRunnable).get();
        } catch (InterruptedException e) {
            new Status(IStatus.ERROR, GdbLaunchPlugin.PLUGIN_ID, IDsfService.INTERNAL_ERROR, "Error initializing launch", e); //$NON-NLS-1$
        } catch (ExecutionException e) {
            new Status(IStatus.ERROR, GdbLaunchPlugin.PLUGIN_ID, IDsfService.INTERNAL_ERROR, "Error initializing launch", e); //$NON-NLS-1$
        }
    }

    public DsfSession getSession() { return fSession; }

    ///////////////////////////////////////////////////////////////////////////
    // IServiceEventListener
    @DsfServiceEventHandler public void eventDispatched(GDBControl.ExitedEvent event) {
        shutdownSession(new RequestMonitor(ImmediateExecutor.getInstance(), null));
    }

    ///////////////////////////////////////////////////////////////////////////
    // ITerminate
    @Override
    public boolean canTerminate() {
        return super.canTerminate() && fInitialized && !fShutDown;
    }

    @Override
    public boolean isTerminated() {
        return super.isTerminated() || fShutDown;
    }


    @Override
    public void terminate() throws DebugException {
        if (fShutDown) return;
        super.terminate();
    }
    // ITerminate
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Shuts down the services, the session and the executor associated with 
     * this launch.  
     * <p>
     * Note: The argument request monitor to this method should NOT use the
     * executor that belongs to this launch.  By the time the shutdown is 
     * complete, this executor will not be dispatching anymore and the 
     * request monitor will never be invoked.  Instead callers should use
     * the {@link ImmediateExecutor}.
     * </p>
     * @param rm The request monitor invoked when the shutdown is complete.    
     */
    @ConfinedToDsfExecutor("getSession().getExecutor()")
    public void shutdownSession(final RequestMonitor rm) {
        if (fShutDown) {
            rm.done();
            return;
        }
        fShutDown = true;
            
        Sequence shutdownSeq = new ShutdownSequence(
            getDsfExecutor(), fSession.getId(),
            new RequestMonitor(fSession.getExecutor(), rm) { 
                @Override
                public void handleCompleted() {
                    fSession.removeServiceEventListener(GdbLaunch.this);
                    if (!getStatus().isOK()) {
                        GdbLaunchPlugin.getDefault().getLog().log(new MultiStatus(
                            GdbLaunchPlugin.PLUGIN_ID, -1, new IStatus[]{getStatus()}, "Session shutdown failed", null)); //$NON-NLS-1$
                    }
                    // Last order of business, shutdown the dispatch queue.
                    fTracker.dispose();
                    fTracker = null;
                    DsfSession.endSession(fSession);
                    // endSession takes a full dispatch to distribute the 
                    // session-ended event, finish step only after the dispatch.
                    fExecutor.shutdown();
                    fExecutor = null;
                    fireTerminate();
                    
                    rm.setStatus(getStatus());
                    rm.done();
                }
            });
        fExecutor.execute(shutdownSeq);
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public Object getAdapter(Class adapter) {
        // Must force adapters to be loaded.
        Platform.getAdapterManager().loadAdapter(this, adapter.getName());
        return super.getAdapter(adapter);
    }
}