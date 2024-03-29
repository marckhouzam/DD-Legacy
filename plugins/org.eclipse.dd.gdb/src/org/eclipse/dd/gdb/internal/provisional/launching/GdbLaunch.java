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
package org.eclipse.dd.gdb.internal.provisional.launching;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.ConfinedToDsfExecutor;
import org.eclipse.dd.dsf.concurrent.DefaultDsfExecutor;
import org.eclipse.dd.dsf.concurrent.DsfExecutor;
import org.eclipse.dd.dsf.concurrent.DsfRunnable;
import org.eclipse.dd.dsf.concurrent.IDsfStatusConstants;
import org.eclipse.dd.dsf.concurrent.ImmediateExecutor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.concurrent.Sequence;
import org.eclipse.dd.dsf.concurrent.ThreadSafe;
import org.eclipse.dd.dsf.concurrent.ThreadSafeAndProhibitedFromDsfExecutor;
import org.eclipse.dd.dsf.debug.model.DsfMemoryBlockRetrieval;
import org.eclipse.dd.dsf.debug.service.IDsfDebugServicesFactory;
import org.eclipse.dd.dsf.debug.service.IMemory.IMemoryDMContext;
import org.eclipse.dd.dsf.debug.service.IProcesses.IProcessDMContext;
import org.eclipse.dd.dsf.debug.service.command.ICommandControlService;
import org.eclipse.dd.dsf.debug.service.command.ICommandControlService.ICommandControlShutdownDMEvent;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfServicesTracker;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.gdb.internal.GdbPlugin;
import org.eclipse.dd.gdb.internal.provisional.service.command.IGDBControl;
import org.eclipse.dd.mi.service.IMIProcesses;
import org.eclipse.dd.mi.service.MIProcesses;
import org.eclipse.dd.mi.service.command.AbstractCLIProcess;
import org.eclipse.dd.mi.service.command.MIInferiorProcess;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.Launch;
import org.eclipse.debug.core.model.IDisconnect;
import org.eclipse.debug.core.model.IMemoryBlockRetrieval;
import org.eclipse.debug.core.model.ISourceLocator;
import org.eclipse.debug.core.model.ITerminate;

/**
 * The only object in the model that implements the traditional interfaces.
 */
@ThreadSafe
public class GdbLaunch extends Launch
    implements ITerminate, IDisconnect
{
    private DefaultDsfExecutor fExecutor;
    private DsfSession fSession;
    private DsfServicesTracker fTracker;
    private boolean fInitialized = false;
    private boolean fShutDown = false;

    private DsfMemoryBlockRetrieval fMemRetrieval;
    private IDsfDebugServicesFactory fServiceFactory;
    
    public GdbLaunch(ILaunchConfiguration launchConfiguration, String mode, ISourceLocator locator) {
        super(launchConfiguration, mode, locator);

        // Create the dispatch queue to be used by debugger control and services 
        // that belong to this launch
        final DefaultDsfExecutor dsfExecutor = new DefaultDsfExecutor(GdbLaunchDelegate.GDB_DEBUG_MODEL_ID);
        dsfExecutor.prestartCoreThread();
        fExecutor = dsfExecutor;
        fSession = DsfSession.startSession(fExecutor, GdbLaunchDelegate.GDB_DEBUG_MODEL_ID);
    }

    public DsfExecutor getDsfExecutor() { return fExecutor; }
    public IDsfDebugServicesFactory getServiceFactory() { return fServiceFactory; }
    
    public void initialize()
    {
        Runnable initRunnable = new DsfRunnable() { 
            public void run() {
                fTracker = new DsfServicesTracker(GdbPlugin.getBundleContext(), fSession.getId());
                fSession.addServiceEventListener(GdbLaunch.this, null);
    
                fInitialized = true;
                fireChanged();
            }
        };
        
        // Invoke the execution code and block waiting for the result.
        try {
            fExecutor.submit(initRunnable).get();
        } catch (InterruptedException e) {
            new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, IDsfStatusConstants.INTERNAL_ERROR, "Error initializing launch", e); //$NON-NLS-1$
        } catch (ExecutionException e) {
            new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, IDsfStatusConstants.INTERNAL_ERROR, "Error initializing launch", e); //$NON-NLS-1$
        }
    }

    public void initializeControl()
    throws CoreException
    {
        // Create a memory retrieval and register it with the session 
        try {
            fExecutor.submit( new Callable<Object>() {
                public Object call() throws CoreException {
                	ICommandControlService commandControl = fTracker.getService(ICommandControlService.class);
                	IMIProcesses procService = fTracker.getService(IMIProcesses.class);
                    if (commandControl != null && procService != null) {
                        fMemRetrieval = new DsfMemoryBlockRetrieval(
                                GdbLaunchDelegate.GDB_DEBUG_MODEL_ID, getLaunchConfiguration(), fSession);
                        fSession.registerModelAdapter(IMemoryBlockRetrieval.class, fMemRetrieval);
                        
                   		IProcessDMContext procDmc = procService.createProcessContext(commandControl.getContext(), MIProcesses.UNIQUE_GROUP_ID);
                        IMemoryDMContext memoryDmc = (IMemoryDMContext)procService.createContainerContext(procDmc, MIProcesses.UNIQUE_GROUP_ID);
                        fMemRetrieval.initialize(memoryDmc);
                    }
                    return null;
                }
            }).get();
        } catch (InterruptedException e) {
            throw new CoreException(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, 0, "Interrupted while waiting for get process callable.", e)); //$NON-NLS-1$
        } catch (ExecutionException e) {
            throw (CoreException)e.getCause();
        } catch (RejectedExecutionException e) {
            throw new CoreException(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, 0, "Debugger shut down before launch was completed.", e)); //$NON-NLS-1$
        }
    }

    public DsfSession getSession() { return fSession; }

    @ThreadSafeAndProhibitedFromDsfExecutor("getDsfExecutor()")
    public void addInferiorProcess(String label) throws CoreException {
    	try {
    		// Add the "inferior" process object to the launch.
    		MIInferiorProcess inferiorProc = 
    			getDsfExecutor().submit( new Callable<MIInferiorProcess>() {
    				public MIInferiorProcess call() throws CoreException {
    					IGDBControl gdb = fTracker.getService(IGDBControl.class);
    					if (gdb != null) {
    						return gdb.getInferiorProcess();
    					}
    					return null;
    				}
    			}).get();

            DebugPlugin.newProcess(this, inferiorProc, label);
        } catch (InterruptedException e) {
            throw new CoreException(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, 0, "Interrupted while waiting for get process callable.", e)); //$NON-NLS-1$
        } catch (ExecutionException e) {
            throw (CoreException)e.getCause();
        } catch (RejectedExecutionException e) {
            throw new CoreException(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, 0, "Debugger shut down before launch was completed.", e)); //$NON-NLS-1$
        }            
    }
    
    @ThreadSafeAndProhibitedFromDsfExecutor("getDsfExecutor()")
    public void addCLIProcess(String label) throws CoreException {
        try {
            // Add the CLI process object to the launch.
    		AbstractCLIProcess cliProc =
    			getDsfExecutor().submit( new Callable<AbstractCLIProcess>() {
    				public AbstractCLIProcess call() throws CoreException {
    					IGDBControl gdb = fTracker.getService(IGDBControl.class);
    					if (gdb != null) {
    						return gdb.getCLIProcess();
    					}
    					return null;
    				}
    			}).get();

            DebugPlugin.newProcess(this, cliProc, label);
        } catch (InterruptedException e) {
            throw new CoreException(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, 0, "Interrupted while waiting for get process callable.", e)); //$NON-NLS-1$
        } catch (ExecutionException e) {
            throw (CoreException)e.getCause();
        } catch (RejectedExecutionException e) {
            throw new CoreException(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, 0, "Debugger shut down before launch was completed.", e)); //$NON-NLS-1$
        } 
    }
    
    public void setServiceFactory(IDsfDebugServicesFactory factory) {
    	fServiceFactory = factory;
    }
    
    ///////////////////////////////////////////////////////////////////////////
    // IServiceEventListener
    @DsfServiceEventHandler public void eventDispatched(ICommandControlShutdownDMEvent event) {
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

    ///////////////////////////////////////////////////////////////////////////
    // IDisconnect
    @Override
    public boolean canDisconnect() {
        return canTerminate();
    }

    @Override
    public boolean isDisconnected() {
        return isTerminated();
    }

    @Override
    public void disconnect() throws DebugException {
    	terminate();
    }
    // IDisconnect
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
                    if (!isSuccess()) {
                        GdbPlugin.getDefault().getLog().log(new MultiStatus(
                            GdbPlugin.PLUGIN_ID, -1, new IStatus[]{getStatus()}, "Session shutdown failed", null)); //$NON-NLS-1$
                    }
                    // Last order of business, shutdown the dispatch queue.
                    fTracker.dispose();
                    fTracker = null;
                    DsfSession.endSession(fSession);
                    
                    // DsfMemoryBlockRetrieval.saveMemoryBlocks();
                    fMemRetrieval.saveMemoryBlocks();
                    
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
