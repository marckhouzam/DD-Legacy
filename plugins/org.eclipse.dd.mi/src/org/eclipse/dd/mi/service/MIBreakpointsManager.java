/*******************************************************************************
 * Copyright (c) 2007, 2008 Wind River and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Wind River - Initial API and implementation
 *     Ericsson   - High-level breakpoints integration
 *     Ericsson   - Added breakpoint filter support
 *     Ericsson   - Re-factored the service and put a few comments
 *     Ericsson   - Added Action support
 *******************************************************************************/

package org.eclipse.dd.mi.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.cdt.debug.core.CDebugCorePlugin;
import org.eclipse.cdt.debug.core.breakpointactions.BreakpointActionManager;
import org.eclipse.cdt.debug.core.model.ICAddressBreakpoint;
import org.eclipse.cdt.debug.core.model.ICBreakpoint;
import org.eclipse.cdt.debug.core.model.ICBreakpointExtension;
import org.eclipse.cdt.debug.core.model.ICLineBreakpoint;
import org.eclipse.cdt.debug.core.model.ICWatchpoint;
import org.eclipse.cdt.debug.internal.core.breakpoints.BreakpointProblems;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IMarkerDelta;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.MultiRule;
import org.eclipse.dd.dsf.concurrent.CountingRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DsfRunnable;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.concurrent.ThreadSafe;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.debug.service.IBreakpoints;
import org.eclipse.dd.dsf.debug.service.IDsfBreakpointExtension;
import org.eclipse.dd.dsf.debug.service.IRunControl;
import org.eclipse.dd.dsf.debug.service.ISourceLookup;
import org.eclipse.dd.dsf.debug.service.IBreakpoints.IBreakpointDMContext;
import org.eclipse.dd.dsf.debug.service.IBreakpoints.IBreakpointsTargetDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.dsf.debug.service.ISourceLookup.ISourceLookupDMContext;
import org.eclipse.dd.dsf.debug.service.command.ICommandControl;
import org.eclipse.dd.dsf.debug.service.command.ICommandControlService.ICommandControlShutdownDMEvent;
import org.eclipse.dd.dsf.service.AbstractDsfService;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.mi.internal.MIPlugin;
import org.eclipse.dd.mi.service.MIBreakpoints.BreakpointAddedEvent;
import org.eclipse.dd.mi.service.MIBreakpoints.BreakpointRemovedEvent;
import org.eclipse.dd.mi.service.MIBreakpoints.BreakpointUpdatedEvent;
import org.eclipse.dd.mi.service.MIBreakpoints.MIBreakpointDMContext;
import org.eclipse.dd.mi.service.breakpoint.actions.BreakpointActionAdapter;
import org.eclipse.dd.mi.service.command.events.MIBreakpointHitEvent;
import org.eclipse.dd.mi.service.command.events.MIGDBExitEvent;
import org.eclipse.dd.mi.service.command.events.MIWatchpointScopeEvent;
import org.eclipse.dd.mi.service.command.events.MIWatchpointTriggerEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointListener;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.IBreakpointManagerListener;
import org.eclipse.debug.core.model.IBreakpoint;
import org.osgi.framework.BundleContext;

/**
 * Breakpoint service interface.  The breakpoint service tracks CDT breakpoint
 * objects, and based on those, it manages breakpoints in the debugger back end.
 * 
 * It relies on MIBreakpoints for the actual back-end interface.
 */
public class MIBreakpointsManager extends AbstractDsfService implements IBreakpointManagerListener, IBreakpointListener
{
    // Note: Find a way to import this (careful of circular dependencies)
    public final static String GDB_DEBUG_MODEL_ID = "org.eclipse.dd.gdb"; //$NON-NLS-1$

    // Extra breakpoint attributes
    private static final String ATTR_DEBUGGER_PATH = MIPlugin.PLUGIN_ID + ".debuggerPath";   //$NON-NLS-1$
    private static final String ATTR_THREAD_FILTER = MIPlugin.PLUGIN_ID + ".threadFilter";   //$NON-NLS-1$
    private static final String ATTR_THREAD_ID     = MIPlugin.PLUGIN_ID + ".threadID";       //$NON-NLS-1$

    // Services
    ICommandControl    fConnection;
    IRunControl        fRunControl;
    ISourceLookup      fSourceLookup;
    IBreakpoints       fBreakpoints;
    IBreakpointManager fBreakpointManager;  // Platform breakpoint manager (not this!)
    BreakpointActionManager fBreakpointActionManager;

    ///////////////////////////////////////////////////////////////////////////
    // Breakpoints tracking
    ///////////////////////////////////////////////////////////////////////////

    private String fDebugModelId;
    
    // Holds the set of platform breakpoints with their corresponding back-end
    // breakpoint attributes, per context (i.e. each platform breakpoint is
    // replicated for each execution context).
    // - Context entry added/removed on start/stopTrackingBreakpoints()
    // - Augmented on breakpointAdded()
    // - Modified on breakpointChanged()
    // - Diminished on breakpointRemoved()
    private Map<IBreakpointsTargetDMContext, Map<ICBreakpoint, Map<String, Object>>> fPlatformBPs =
        new HashMap<IBreakpointsTargetDMContext, Map<ICBreakpoint, Map<String, Object>>>();

    // Holds the set of target breakpoints, per execution context, and their
    // mapping to the corresponding platform breakpoint. In a given execution
    // context there can only be one platform breakpoint per target breakpoint.
    // Acts as a mapping from target (low-level) BP to the corresponding platform
    // (high-level) BP.
    // Updated when:
    // - We start/stop tracking an execution context
    // - A platform breakpoint is added/removed
    // - A thread filter is applied/removed
    private Map<IBreakpointsTargetDMContext, Map<IBreakpointDMContext, ICBreakpoint>> fTargetBPs =
        new HashMap<IBreakpointsTargetDMContext, Map<IBreakpointDMContext, ICBreakpoint>>();

    // Holds the mapping from platform breakpoint to the corresponding target
    // breakpoint(s), per context. There can be multiple back-end BPs for a
    // single platform BP in the case of [1] multiple target contexts, and/or
    // [2] thread filtering.
    // Updated when:
    // - We start/stop tracking an execution context
    // - A platform breakpoint is added/removed
    // - A thread filter is applied/removed
    private Map<IBreakpointsTargetDMContext, Map<ICBreakpoint, Vector<IBreakpointDMContext>>> fBreakpointIDs =
        new HashMap<IBreakpointsTargetDMContext, Map<ICBreakpoint, Vector<IBreakpointDMContext>>>();

    // Holds the mapping from platform breakpoint to the corresponding target
    // breakpoint threads, per context.
    // Updated when:
    // - We start/stop tracking an execution context
    // - A platform breakpoint is added/removed
    // - A thread filter is applied/removed
    private Map<IBreakpointsTargetDMContext, Map<ICBreakpoint, Set<String>>> fBreakpointThreads =
        new HashMap<IBreakpointsTargetDMContext, Map<ICBreakpoint, Set<String>>>();

    // Due to the very asynchronous nature of DSF, a new breakpoint request can
    // pop up at any time before an ongoing one is completed. The following set
    // is used to store requests until the ongoing operation completes.
    private Set<IBreakpoint> fPendingRequests    = new HashSet<IBreakpoint>();
    private Set<IBreakpoint> fPendingBreakpoints = new HashSet<IBreakpoint>();

    private Map<ICBreakpoint, IMarker> fBreakpointMarkerProblems =
        new HashMap<ICBreakpoint, IMarker>();

    ///////////////////////////////////////////////////////////////////////////
    // String constants
    ///////////////////////////////////////////////////////////////////////////

    private static final String NULL_STRING = ""; //$NON-NLS-1$

    static final String CONTEXT_ALREADY_INITIALIZED  = "Context already initialized";  //$NON-NLS-1$
    static final String INVALID_CONTEXT_TYPE         = "Invalid context type";         //$NON-NLS-1$
    static final String INVALID_CONTEXT              = "Invalid context";              //$NON-NLS-1$

    static final String UNABLE_TO_READ_BREAKPOINT    = "Unable to read initial breakpoint attributes"; //$NON-NLS-1$
    static final String BREAKPOINT_NOT_INSTALLED     = "Breakpoints not installed for given context";  //$NON-NLS-1$
    static final String BREAKPOINT_ALREADY_INSTALLED = "Breakpoint already installed"; //$NON-NLS-1$
    static final String BREAKPOINT_ALREADY_REMOVED   = "Breakpoint already removed";   //$NON-NLS-1$

    static final String INVALID_BREAKPOINT           = "Invalid breakpoint";                    //$NON-NLS-1$
    static final String UNKNOWN_BREAKPOINT           = "Unknown breakpoint";                    //$NON-NLS-1$
    static final String INVALID_PARAMETER            = "Invalid breakpoint parameter(s)";       //$NON-NLS-1$

    static final String NO_DEBUGGER_PATH             = "No debugger path for breakpoint";       //$NON-NLS-1$
    static final String NO_MARKER_FOR_BREAKPOINT     = "No marker associated with breakpoint";  //$NON-NLS-1$

    ///////////////////////////////////////////////////////////////////////////
    // AbstractDsfService
    ///////////////////////////////////////////////////////////////////////////

    /**
     * The service constructor.
     * Performs basic instantiation (method initialize() performs the real
     * service initialization asynchronously).
     * 
     * @param session       the debugging session
     * @param debugModelId  the debugging model
     */
    public MIBreakpointsManager(DsfSession session, String debugModelId) {
        super(session);
        fDebugModelId = debugModelId;
    }

    //-------------------------------------------------------------------------
    // initialize
    //-------------------------------------------------------------------------
    // - Collect references for the services we interact with
    // - Register to interesting events
    // - Obtain the list of platform breakpoints
    // - Register the service for interested parties
    //-------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see org.eclipse.dd.dsf.service.AbstractDsfService#initialize(org.eclipse.dd.dsf.concurrent.RequestMonitor)
     */
    @Override
    public void initialize(final RequestMonitor rm) {
        super.initialize(
            new RequestMonitor(getExecutor(), rm) {
                @Override
                protected void handleSuccess() {
                    doInitialize(rm);
                }});
    }

    /**
     * @param rm
     */
    private void doInitialize(RequestMonitor rm) {
        
        // Get the required services references from central repository
        fConnection     = getServicesTracker().getService(ICommandControl.class);
        fRunControl     = getServicesTracker().getService(IRunControl.class);
        fSourceLookup   = getServicesTracker().getService(ISourceLookup.class);
        fBreakpoints    = getServicesTracker().getService(IBreakpoints.class);
        fBreakpointManager = DebugPlugin.getDefault().getBreakpointManager();
        fBreakpointActionManager = CDebugCorePlugin.getDefault().getBreakpointActionManager();

        // Register to the useful events
        getSession().addServiceEventListener(this, null);
        fBreakpointManager.addBreakpointListener(this);
        fBreakpointManager.addBreakpointManagerListener(this);

        // And register this service
        register(new String[] { MIBreakpointsManager.class.getName() },
                 new Hashtable<String, String>());
        rm.done();
    }

    //-------------------------------------------------------------------------
    // shutdown
    //-------------------------------------------------------------------------
    // - Un-register the service
    // - Stop listening to events
    // - Remove the breakpoints installed by this service
    //
    //  Since we are shutting down, there is no overwhelming need
    //  to keep the maps coherent...
    //-------------------------------------------------------------------------
    
    /* (non-Javadoc)
     * @see org.eclipse.dd.dsf.service.AbstractDsfService#shutdown(org.eclipse.dd.dsf.concurrent.RequestMonitor)
     */
    @Override
    public void shutdown(final RequestMonitor rm) {

        // Stop accepting requests and events
        unregister();
        getSession().removeServiceEventListener(this);
        fBreakpointManager.removeBreakpointListener(this);
        fBreakpointManager.removeBreakpointManagerListener(this);

        // Cleanup the breakpoints that are still installed by the service.
        // Use a counting monitor which will call mom to complete the shutdown
        // after the breakpoints are un-installed (successfully or not).
        CountingRequestMonitor countingRm = new CountingRequestMonitor(getExecutor(), rm) {
            @Override
            protected void handleCompleted() {
                MIBreakpointsManager.super.shutdown(rm);
            }
        };

        List<IBreakpointsTargetDMContext> targetBPKeys = new ArrayList<IBreakpointsTargetDMContext>(fTargetBPs.size());
        targetBPKeys.addAll(0, fTargetBPs.keySet());
        for (IBreakpointsTargetDMContext dmc : targetBPKeys) {
            stopTrackingBreakpoints(dmc, countingRm);
        }
        countingRm.setDoneCount(targetBPKeys.size());
    }

    //-------------------------------------------------------------------------
    // getBundleContext
    //-------------------------------------------------------------------------

    /* (non-Javadoc)
     * @see org.eclipse.dd.dsf.service.AbstractDsfService#getBundleContext()
     */
    @Override
    protected BundleContext getBundleContext() {
        return MIPlugin.getBundleContext();
    }

    ///////////////////////////////////////////////////////////////////////////
    // IBreakpointsManager
    ///////////////////////////////////////////////////////////////////////////

    //-------------------------------------------------------------------------
    // startTrackingBreakpoints
    //-------------------------------------------------------------------------
    // - Augment the maps with the new execution context
    // - Install the platform breakpoints on the selected target
    //-------------------------------------------------------------------------

    /**
     * Install and begin tracking breakpoints for given context.  The service
     * will keep installing new breakpoints that appear in the IDE for this
     * context until {@link #uninstallBreakpoints(IDMContext)} is called for that
     * context.
     * @param dmc Context to start tracking breakpoints for.
     * @param rm Completion callback.
     */
    public void startTrackingBreakpoints(final IBreakpointsTargetDMContext dmc, final RequestMonitor rm) {

        // Validate the execution context
        if (dmc == null) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, REQUEST_FAILED, INVALID_CONTEXT, null));
            rm.done();
            return;
        }

        // Make sure a mapping for this execution context does not already exist
        Map<ICBreakpoint,Map<String, Object>> platformBPs = fPlatformBPs.get(dmc);
        Map<ICBreakpoint, Vector<IBreakpointDMContext>> breakpointIDs = fBreakpointIDs.get(dmc);
        Map<IBreakpointDMContext, ICBreakpoint> targetIDs = fTargetBPs.get(dmc);
        Map<ICBreakpoint, Set<String>> threadIDs = fBreakpointThreads.get(dmc);
        if ((platformBPs != null) || (breakpointIDs != null) || (targetIDs != null) || (threadIDs != null)) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, CONTEXT_ALREADY_INITIALIZED, null));
            rm.done();
            return;
        }

        // Create entries in the breakpoint tables for the new context. These entries should only
        // be removed when this service stops tracking breakpoints for the given context.
        fPlatformBPs.put(dmc, new HashMap<ICBreakpoint, Map<String, Object>>());
        fBreakpointIDs.put(dmc, new HashMap<ICBreakpoint, Vector<IBreakpointDMContext>>());
        fTargetBPs.put(dmc, new HashMap<IBreakpointDMContext, ICBreakpoint>());
        fBreakpointThreads.put(dmc, new HashMap<ICBreakpoint, Set<String>>());

        // Install the platform breakpoints (stored in fPlatformBPs) on the target.
        new Job("DSF BreakpointsManager: Install initial breakpoints on target") { //$NON-NLS-1$
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                // Submit the runnable to plant the breakpoints on dispatch thread.
                getExecutor().submit(new Runnable() {
                    public void run() {
                        installInitialBreakpoints(dmc, rm);
                    }
                });

                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /**
     * Installs the breakpoints that existed prior to the activation of this
     * execution context.
     * 
     * @param dmc
     * @param initialPlatformBPs
     * @param rm
     */
    private void installInitialBreakpoints(final IBreakpointsTargetDMContext dmc, final RequestMonitor rm)
    {
        // Retrieve the set of platform breakpoints for this context
        final Map<ICBreakpoint,Map<String, Object>> platformBPs = fPlatformBPs.get(dmc);
        if (platformBPs == null) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, INVALID_CONTEXT, null));
            rm.done();
            return;
        }

        // Read current breakpoints from platform and copy their augmented
        // attributes into the local reference map
        try {
            IBreakpoint[] breakpoints = DebugPlugin.getDefault().getBreakpointManager().getBreakpoints(fDebugModelId);
            for (IBreakpoint breakpoint : breakpoints) {
                if (supportsBreakpoint(breakpoint)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> attributes = breakpoint.getMarker().getAttributes();
                    attributes.put(ATTR_DEBUGGER_PATH, NULL_STRING);
                    attributes.put(ATTR_THREAD_FILTER, extractThreads(dmc, (ICBreakpoint) breakpoint));
                    attributes.put(ATTR_THREAD_ID, NULL_STRING);
                    platformBPs.put((ICBreakpoint) breakpoint, attributes);
                }
            }
        } catch (CoreException e) {
            IStatus status = new Status(
                IStatus.ERROR, MIPlugin.PLUGIN_ID, REQUEST_FAILED, UNABLE_TO_READ_BREAKPOINT, e);
            rm.setStatus(status);
            rm.done();
        }

        // Install the individual breakpoints on the dispatcher thread
        // Requires a counting monitor to know when we are done
        final CountingRequestMonitor countingRm = new CountingRequestMonitor(getExecutor(), rm);
        countingRm.setDoneCount(platformBPs.size());

        for (final ICBreakpoint breakpoint : platformBPs.keySet()) {
            final Map<String, Object> attributes = platformBPs.get(breakpoint);
            // Upon determining the debuggerPath, the breakpoint is installed
            determineDebuggerPath(dmc, attributes, new RequestMonitor(getExecutor(), countingRm) {
                @Override
                protected void handleSuccess() {
                	// Install only if the breakpoint is enabled at startup (Bug261082)
                	boolean bpEnabled = attributes.get(ICBreakpoint.ENABLED).equals(true) && fBreakpointManager.isEnabled();
                	if (bpEnabled)
                		installBreakpoint(dmc, breakpoint, attributes, new RequestMonitor(getExecutor(), countingRm));
                	else
                		countingRm.done();
                }
            });
        }
    }

    //-------------------------------------------------------------------------
    // stopTrackingBreakpoints
    //-------------------------------------------------------------------------
    // - Remove the target breakpoints for the given execution context
    // - Update the maps
    //-------------------------------------------------------------------------

    /**
     * Uninstall and stop tracking breakpoints for the given context.
     * @param dmc Context to start tracking breakpoints for.
     * @param rm Completion callback.
     */
    public void stopTrackingBreakpoints(final IBreakpointsTargetDMContext dmc, final RequestMonitor rm) {

        // Validate the context
        if (dmc == null) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, INVALID_CONTEXT, null));
            rm.done();
            return;
        }

        // Retrieve the set of platform breakpoints for this context
        final Map<ICBreakpoint,Map<String, Object>> platformBPs = fPlatformBPs.get(dmc);
        if (platformBPs == null) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, INVALID_CONTEXT, null));
            rm.done();
            return;
        }

        // Un-install the individual breakpoints on the dispatcher thread
        // (requires a counting monitor to know when we are done).
        // On completion (success or failure), update the maps.
        final CountingRequestMonitor countingRm = new CountingRequestMonitor(getExecutor(), rm) {
            @Override
            protected void handleCompleted() {
                fPlatformBPs.remove(dmc);
                fBreakpointIDs.remove(dmc);
                fTargetBPs.remove(dmc);
                fBreakpointThreads.remove(dmc);
                rm.done();
            }
        };
        countingRm.setDoneCount(platformBPs.size());

        for (final ICBreakpoint breakpoint : platformBPs.keySet()) {
            uninstallBreakpoint(dmc, breakpoint,
                new RequestMonitor(getExecutor(), countingRm) {
                    @Override
                    protected void handleCompleted() {
                        countingRm.done();
                    }
                });
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Back-end interface functions
    ///////////////////////////////////////////////////////////////////////////

    //-------------------------------------------------------------------------
    // installBreakpoint
    //-------------------------------------------------------------------------

    /**
     * Install a platform breakpoint on the back-end. For a given context, a
     * platform breakpoint can resolve into multiple back-end breakpoints when
     * threads are taken into account.
     * 
     * @param dmc
     * @param breakpoint
     * @param attributes
     * @param rm
     */
    private void installBreakpoint(IBreakpointsTargetDMContext dmc, final ICBreakpoint breakpoint,
        final Map<String, Object> attributes, final RequestMonitor rm)
    {
        // Retrieve the breakpoint maps
        final Map<ICBreakpoint,Map<String,Object>> platformBPs = fPlatformBPs.get(dmc);
        assert platformBPs != null;

        final Map<ICBreakpoint, Vector<IBreakpointDMContext>> breakpointIDs = fBreakpointIDs.get(dmc);
        assert breakpointIDs != null;

        final Map<IBreakpointDMContext, ICBreakpoint> targetBPs = fTargetBPs.get(dmc);
        assert targetBPs != null;

        final Map<ICBreakpoint, Set<String>> threadsIDs = fBreakpointThreads.get(dmc);
        assert threadsIDs != null;

        // Minimal validation
        if (breakpointIDs.containsKey(breakpoint) || targetBPs.containsValue(breakpoint)) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, BREAKPOINT_ALREADY_INSTALLED, null));
            rm.done();
            return;
        }

        // Ensure the breakpoint has a valid debugger source path
        if (breakpoint instanceof ICLineBreakpoint && !(breakpoint instanceof ICAddressBreakpoint)) {
            String debuggerPath = (String) attributes.get(ATTR_DEBUGGER_PATH);
            if (debuggerPath == null || debuggerPath == NULL_STRING) {
                rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, REQUEST_FAILED, NO_DEBUGGER_PATH, null));
                rm.done();
                return;
            }
        }

        // A back-end breakpoint needs to be installed for each specified thread
        // Note: This is a bit academic since [1] thread info is not kept by the
        // BreakpointManager (so it can not possibly be restored when a target is
        // started), and [2] the standard GUI doesn't allow to specify thread at
        // breakpoint creation. However, it is conceivable that an enhanced Editor
        // would permit it.
        final Set<String> threads = getThreads(attributes);

        // Update the breakpoint state when all back-end breakpoints have been installed
        final CountingRequestMonitor installRM = new CountingRequestMonitor(getExecutor(), rm) {
            @Override
            protected void handleCompleted() {
                // Store the platform breakpoint
                platformBPs.put(breakpoint, attributes);
                rm.done();
            }
        };
        installRM.setDoneCount(threads.size());

        // Install the back-end breakpoint(s)
        for (final String thread : threads) {
            DataRequestMonitor<IBreakpointDMContext> drm =
                new DataRequestMonitor<IBreakpointDMContext>(getExecutor(), installRM) {
                    @Override
                    protected void handleSuccess() {
                        // Add the new back-end breakpoint to the map
                        Vector<IBreakpointDMContext> list = breakpointIDs.get(breakpoint);
                        if (list == null)
                            list = new Vector<IBreakpointDMContext>();
                        IBreakpointDMContext targetBP = getData();
                        list.add(targetBP);
                        breakpointIDs.put(breakpoint, list);

                        // Add the reverse mapping
                        targetBPs.put(targetBP, breakpoint);

                        // And update the corresponding thread list
                        Set<String> thrds = threadsIDs.get(breakpoint);
                        if (thrds == null)
                            thrds = new HashSet<String>();
                        thrds.add(thread);
                        threadsIDs.put(breakpoint, thrds);

                        // Reset the thread (is it necessary?)
                        attributes.put(ATTR_THREAD_ID, NULL_STRING);

                        // Finally, update the platform breakpoint
                        try {
							breakpoint.incrementInstallCount();
						} catch (CoreException e) {
						}
                        installRM.done();
                    }

                    @Override
                    protected void handleError() {
                        addBreakpointProblemMarker(breakpoint, "Breakpoint attribute problem: installation failed", IMarker.SEVERITY_WARNING); //$NON-NLS-1$
                        installRM.done();
                    }
                };

            // Convert the breakpoint attributes for the back-end
            attributes.put(ATTR_THREAD_ID, thread);
            Map<String,Object> targetAttributes = convertToTargetBreakpoint(breakpoint, attributes);
            fBreakpoints.insertBreakpoint(dmc, targetAttributes, drm);
        }
    }

    private void addBreakpointProblemMarker(final ICBreakpoint breakpoint, final String description, final int severity) {

        new Job("Add Breakpoint Problem Marker") { //$NON-NLS-1$
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                
                if (breakpoint instanceof ICLineBreakpoint) {
                    ICLineBreakpoint lineBreakpoint = (ICLineBreakpoint) breakpoint;
                    try {
                        // Locate the workspace resource via the breakpoint marker
                        IMarker breakpoint_marker = lineBreakpoint.getMarker();
                        IResource resource = breakpoint_marker.getResource();

                        // Add a problem marker to the resource
                        IMarker problem_marker = resource.createMarker(BreakpointProblems.BREAKPOINT_PROBLEM_MARKER_ID);
                        int line_number = lineBreakpoint.getLineNumber();
                        problem_marker.setAttribute(IMarker.LOCATION,    String.valueOf(line_number));
                        problem_marker.setAttribute(IMarker.MESSAGE,     description);
                        problem_marker.setAttribute(IMarker.SEVERITY,    severity);
                        problem_marker.setAttribute(IMarker.LINE_NUMBER, line_number);

                        // And save the baby
                        fBreakpointMarkerProblems.put(breakpoint, problem_marker);
                    } catch (CoreException e) {
                    }
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void removeBreakpointProblemMarker(final ICBreakpoint breakpoint) {

        new Job("Remove Breakpoint Problem Marker") { //$NON-NLS-1$
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                
                IMarker marker = fBreakpointMarkerProblems.remove(breakpoint);
                if (marker != null) {
                    try {
                        marker.delete();
                    } catch (CoreException e) {
                    }
                }

                return Status.OK_STATUS;
            }
        }.schedule();
    }

    //-------------------------------------------------------------------------
    // uninstallBreakpoint
    //-------------------------------------------------------------------------

    /**
     * Un-install an individual breakpoint on the back-end. For one platform
     * breakpoint in a given execution context, there could be multiple
     * corresponding back-end breakpoints (one per thread).
     * 
     * @param dmc
     * @param breakpoint
     * @param rm
     */
    private void uninstallBreakpoint(final IBreakpointsTargetDMContext dmc,
            final ICBreakpoint breakpoint, final RequestMonitor rm)
    {
        // Retrieve the breakpoint maps
        final Map<ICBreakpoint,Map<String,Object>> platformBPs = fPlatformBPs.get(dmc);
        assert platformBPs != null;

        final Map<ICBreakpoint, Vector<IBreakpointDMContext>> breakpointIDs = fBreakpointIDs.get(dmc);
        assert breakpointIDs != null;

        final Map<IBreakpointDMContext, ICBreakpoint> targetBPs = fTargetBPs.get(dmc);
        assert targetBPs != null;

        final Map<ICBreakpoint, Set<String>> threadsIDs = fBreakpointThreads.get(dmc);
        assert threadsIDs != null;

        // Remove breakpoint problem marker (if any)
        removeBreakpointProblemMarker(breakpoint);

        // Minimal validation
        if (!platformBPs.containsKey(breakpoint) || !breakpointIDs.containsKey(breakpoint) || !targetBPs.containsValue(breakpoint)) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, BREAKPOINT_ALREADY_REMOVED, null));
            rm.done();
            return;
        }

        // Remove completion monitor
        // Upon completion, update the mappings
        CountingRequestMonitor removeRM = new CountingRequestMonitor(getExecutor(), rm) {
            @Override
            protected void handleSuccess() {
                // Update the mappings
                platformBPs.remove(breakpoint);
                threadsIDs.remove(breakpoint);

                Vector<IBreakpointDMContext> contexts = breakpointIDs.get(breakpoint);
                if (contexts != null) {
                    for (IBreakpointDMContext context : contexts)
                        targetBPs.remove(context);
                }

                breakpointIDs.get(breakpoint).clear();
                breakpointIDs.remove(breakpoint);

                fPendingRequests.remove(breakpoint);

				rm.done();
            }
        };

        // Remove the back-end breakpoints
        Vector<IBreakpointDMContext> list = breakpointIDs.get(breakpoint);
        int count = 0;
        if (list != null) {
            for (IBreakpointDMContext bp : list) {
                fBreakpoints.removeBreakpoint(bp, removeRM);
                try {
					breakpoint.decrementInstallCount();
				} catch (CoreException e) {
				}
            }
            count = list.size();
        }
        removeRM.setDoneCount(count);
    }

    //-------------------------------------------------------------------------
    // modifyBreakpoint
    //-------------------------------------------------------------------------

    /**
     * Modify a platform breakpoint which can translate to quite a few updates
     * on the target...
     * 
     * @param dmc
     * @param breakpoint
     * @param attributes
     * @param oldValues
     * @param rm
     */
    private void modifyBreakpoint(final IBreakpointsTargetDMContext dmc, final ICBreakpoint breakpoint,
            final Map<String,Object> attributes, final IMarkerDelta oldValues, final RequestMonitor rm)
    {
        // Retrieve the breakpoint maps
        final Map<ICBreakpoint,Map<String,Object>> platformBPs = fPlatformBPs.get(dmc);
        assert platformBPs != null;

        final Map<ICBreakpoint, Vector<IBreakpointDMContext>> breakpointIDs = fBreakpointIDs.get(dmc);
        assert breakpointIDs != null;

        final Map<IBreakpointDMContext, ICBreakpoint> targetBPs = fTargetBPs.get(dmc);
        assert targetBPs != null;

        final Map<ICBreakpoint, Set<String>> threadsIDs = fBreakpointThreads.get(dmc);
        assert threadsIDs != null;

        // Minimal validation
        if (!platformBPs.containsKey(breakpoint)) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, UNKNOWN_BREAKPOINT, null));
            rm.done();
            return;
        }
         
        // Check if the breakpoint is installed: it might not have been if it wasn't enabled at startup (Bug261082)
        if (!breakpointIDs.containsKey(breakpoint) && !targetBPs.containsValue(breakpoint)) {
         	// Install only if the breakpoint is enabled
         	boolean bpEnabled = attributes.get(ICBreakpoint.ENABLED).equals(true) && fBreakpointManager.isEnabled();
         	if (bpEnabled) {
                 attributes.put(ATTR_DEBUGGER_PATH, NULL_STRING);
                 attributes.put(ATTR_THREAD_FILTER, extractThreads(dmc, breakpoint));
                 attributes.put(ATTR_THREAD_ID, NULL_STRING);
                 determineDebuggerPath(dmc, attributes, new RequestMonitor(getExecutor(), rm) {
                     @Override
                     protected void handleSuccess() {
                       	installBreakpoint(dmc, breakpoint, attributes, new RequestMonitor(getExecutor(), rm));
                     }
                 });
         	}
         	else {
                 rm.done();
         	}
         	return;
        }

        // Get the original breakpoint attributes
        final Map<String,Object> original_attributes = platformBPs.get(breakpoint);
        if (original_attributes == null) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, INVALID_BREAKPOINT, null));
            rm.done();
            return;
        }

        // Determine the attributes delta
        final Map<String,Object> oldAttributes = new HashMap<String,Object>(original_attributes);
        oldAttributes.put(ATTR_THREAD_FILTER, threadsIDs.get(breakpoint));

        final Set<String> newThreads = extractThreads(dmc, breakpoint);
        Map<String,Object> newAttributes = new HashMap<String,Object>(attributes);
        newAttributes.put(ATTR_THREAD_FILTER, newThreads);

        final Map<String,Object> attributesDelta = determineAttributesDelta(oldAttributes, newAttributes);

        // Get the list of back-end breakpoints
        final Vector<IBreakpointDMContext> oldTargetBPs = new Vector<IBreakpointDMContext>(breakpointIDs.get(breakpoint));
        if (oldTargetBPs == null) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, INVALID_BREAKPOINT, null));
            rm.done();
            return;
        }

        // We're all set for the breakpoint update.
        //
        // The path for a simple update is straightforward:
        // - For each back-end BP corresponding to a platform BP
        //   - Send an update command to the back-end
        //   - If the operation succeeded, update the data structures
        //   - If the operation failed, try to roll-back
        //
        // In cases where the the back-end breakpoint cannot be
        // simply updated (e.g. thread filter modification), the old
        // breakpoint has to be removed and new one(s) inserted.
        //
        // The path for such an update is:
        // - Install the updated breakpoint
        // - In the operation succeeded
        //   - Remove the old breakpoint(s)
        //   - Perform any pending update

        // Update completion monitor
        final CountingRequestMonitor updateRM = new CountingRequestMonitor(getExecutor(), rm) {
            @Override
            protected void handleSuccess() {
                // Success: simply store the new attributes
                platformBPs.put(breakpoint, attributes);
                rm.done();
            }

            @Override
            protected void handleError() {
                // Reset the breakpoint attributes. This will trigger a
                // breakpoint change event and the correct delta will be
                // computed, resulting in a correctly restored breakpoint
                // at the back-end.
                rollbackAttributes(breakpoint, oldValues);
                platformBPs.put(breakpoint, attributes);

                rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, REQUEST_FAILED, INVALID_PARAMETER, null));
                rm.done();
            }
        };

        // Everything OK: remove the old back-end breakpoints
        final Vector<IBreakpointDMContext> newTargetBPs = new Vector<IBreakpointDMContext>();
        final CountingRequestMonitor removeRM = new CountingRequestMonitor(getExecutor(), rm) {
            @Override
            protected void handleSuccess() {
                // All right! Save the new list and perform the final update
                Map<ICBreakpoint, Vector<IBreakpointDMContext>> breakpointIDs = fBreakpointIDs.get(dmc);
                if (breakpointIDs == null) {
                    rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, REQUEST_FAILED, INVALID_BREAKPOINT, null));
                    rm.done();
                    return;
                }
                breakpointIDs.put(breakpoint, newTargetBPs);
                for (IBreakpointDMContext ref : newTargetBPs) {
                    fBreakpoints.updateBreakpoint(ref, attributesDelta, updateRM);
                }
                updateRM.setDoneCount(newTargetBPs.size());
            }};

        // New back-end breakpoints insertion monitor
        // Holds the list of new back-end breakpoint contexts of the platform breakpoint
        final DataRequestMonitor<Vector<IBreakpointDMContext>> insertRM =
            new DataRequestMonitor<Vector<IBreakpointDMContext>>(getExecutor(), null) {

                @Override
                // In theory, we could have had a partial success and the original threads
                // list would be invalid. We think it is highly unlikely so we assume that
                // either everything went fine or else everything failed.
                protected void handleSuccess() {
                    // Get the list of new back-end breakpoints contexts
                    newTargetBPs.addAll(getData());
                    threadsIDs.put(breakpoint, newThreads);
                    for (IBreakpointDMContext ref : oldTargetBPs) {
                        fBreakpoints.removeBreakpoint(ref, removeRM);
                        try {
							breakpoint.decrementInstallCount();  // A tad early but it should work...
						} catch (CoreException e) {
						}
                    }
                    removeRM.setDoneCount(oldTargetBPs.size());
                }
                
                @Override
                protected void handleError() {
                    // Keep the old threads list and reset the attributes
                    // (bad attributes are the likely cause of failure)
                    updateRM.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, REQUEST_FAILED, INVALID_PARAMETER, null));
                    updateRM.setDoneCount(0);
                }
        };

        // If the changes in the breakpoint attributes justify it, install a
        // new set of back-end breakpoint(s) and then update them
        if (needsResinstallation(attributesDelta)) {
            reinstallBreakpoint(dmc, breakpoint, attributes, newThreads, insertRM);
        }
        else {
            // Update the back-end breakpoint(s) state
            for (IBreakpointDMContext ref : oldTargetBPs) {
                fBreakpoints.updateBreakpoint(ref, attributesDelta, updateRM);
            }
            updateRM.setDoneCount(oldTargetBPs.size());
        }
    }

    /**
     * Re-install the back-end breakpoints
     * 
     * @param context       the target context
     * @param breakpoint    the platform breakpoint
     * @param attributes    breakpoint augmented attributes
     * @param threads       list of threads where breakpoint is to be installed
     * @param drm           will contain the list of successfully installed back-end breakpoints
     */
    private void reinstallBreakpoint(final IBreakpointsTargetDMContext context, final ICBreakpoint breakpoint,
            final Map<String,Object> attributes, Set<String> threads, final DataRequestMonitor<Vector<IBreakpointDMContext>> drm)
    {
        // Our new list of back-end breakpoints. Built as we go.
        final Vector<IBreakpointDMContext> breakpointList = new Vector<IBreakpointDMContext>();

        // Counting monitor for the new back-end breakpoints to install
        // Once we're done, return the new list of back-end breakpoints contexts
        final CountingRequestMonitor installRM = new CountingRequestMonitor(getExecutor(), drm) {
            @Override
            protected void handleSuccess() {
                // Report whatever we have managed to install
                // It is very likely installation either succeeded or failed for all
                drm.setData(breakpointList);
                drm.done();
            }
        };
        installRM.setDoneCount(threads.size());

        // And install the new back-end breakpoints
        for (String thread : threads) {
            // Convert the breakpoint attributes for the back-end
            // Refresh the set of attributes at each iteration just in case...
            Map<String,Object> attrs = convertToTargetBreakpoint(breakpoint, attributes);
            if (!fBreakpointManager.isEnabled()) {
                attrs.put(MIBreakpoints.IS_ENABLED, false);
            }
            // Add the secret ingredient..
            attrs.put(MIBreakpointDMData.THREAD_ID, thread);

            // Then install the spiked breakpoint
            fBreakpoints.insertBreakpoint(context, attrs,
                new DataRequestMonitor<IBreakpointDMContext>(getExecutor(), installRM) {
                    @Override
                    protected void handleSuccess() {
                        // Add the new back-end breakpoint context to the list
                        breakpointList.add(getData());
                        try {
							breakpoint.incrementInstallCount();
						} catch (CoreException e) {
						}
                        installRM.done();
                    }

                    @Override
                    protected void handleError() {
                        // Add the new back-end breakpoint context to the list
                        installRM.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, REQUEST_FAILED, INVALID_PARAMETER, null));
                        installRM.done();
                    }
            });
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // IBreakpointManagerListener implementation
    ///////////////////////////////////////////////////////////////////////////

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.IBreakpointManagerListener#breakpointManagerEnablementChanged(boolean)
     */
    public void breakpointManagerEnablementChanged(boolean enabled) {

        // Only modify enabled breakpoints
        for (IBreakpointsTargetDMContext context : fBreakpointIDs.keySet()) {
            for (ICBreakpoint breakpoint : fBreakpointIDs.get(context).keySet()) {
                try {
                    if (breakpoint.isEnabled()) {
                        for (IBreakpointDMContext ref : fBreakpointIDs.get(context).get(breakpoint)) {
                            Map<String,Object> delta = new HashMap<String,Object>();
                            delta.put(MIBreakpoints.IS_ENABLED, enabled);
                            fBreakpoints.updateBreakpoint(ref, delta, new RequestMonitor(getExecutor(), null));
                        }
                    }
                } catch (CoreException e) {
                }
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // IBreakpointListener implementation
    ///////////////////////////////////////////////////////////////////////////

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.IBreakpointListener#breakpointAdded(org.eclipse.debug.core.model.IBreakpoint)
     */
    @ThreadSafe
    public void breakpointAdded(final IBreakpoint breakpoint) {

        if (supportsBreakpoint(breakpoint)) {
            try {
                // Retrieve the breakpoint attributes
                @SuppressWarnings("unchecked")
                final Map<String, Object> attrs = breakpoint.getMarker().getAttributes();

                getExecutor().execute(new DsfRunnable() {
                    public void run() {
                        final CountingRequestMonitor countingRm = new CountingRequestMonitor(getExecutor(), null) {
                            @Override
                            protected void handleError() {
                                if (getStatus().getSeverity() == IStatus.ERROR) {
                                    MIPlugin.getDefault().getLog().log(getStatus());
                                }
                            }
                        };
                        countingRm.setDoneCount(fPlatformBPs.size());

                        // Install the breakpoint in all the execution contexts
                        for (final IBreakpointsTargetDMContext dmc : fPlatformBPs.keySet()) {
                            determineDebuggerPath(dmc, attrs,
                                    new RequestMonitor(getExecutor(), countingRm) {
                                    @Override
                                    protected void handleSuccess() {
                                        installBreakpoint(dmc, (ICBreakpoint) breakpoint,
                                            attrs, new RequestMonitor(getExecutor(), countingRm));
                                    }
                                });
                        }
                    }
                });
            } catch (CoreException e) {
            } catch (RejectedExecutionException e) {
            }
        }
    }

    /**
     * @param bp
     * @return
     * @throws CoreException
     */
    private IDsfBreakpointExtension getFilterExtension(ICBreakpoint bp) throws CoreException {
        return (IDsfBreakpointExtension) bp.getExtension(GDB_DEBUG_MODEL_ID, ICBreakpointExtension.class);
    }

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.IBreakpointListener#breakpointChanged(org.eclipse.debug.core.model.IBreakpoint, org.eclipse.core.resources.IMarkerDelta)
     */
    public void breakpointChanged(final IBreakpoint breakpoint, final IMarkerDelta delta) {

        if (supportsBreakpoint(breakpoint)) {

            try {
                // Retrieve the breakpoint attributes
                @SuppressWarnings("unchecked")
                final Map<String, Object> attrs = breakpoint.getMarker().getAttributes();
                if (!fBreakpointManager.isEnabled()) {
                    attrs.put(ICBreakpoint.ENABLED, false);
                }

                // Modify the breakpoint in all the target contexts
                getExecutor().execute( new DsfRunnable() {
                    public void run() {

                        // If the breakpoint is currently being updated, queue the request and exit
                        if (fPendingRequests.contains(breakpoint)) {
                            fPendingBreakpoints.add(breakpoint);
                            return;
                        }

                        // Keep track of the updates
                        final CountingRequestMonitor countingRm = new CountingRequestMonitor(getExecutor(), null) {
                            @Override
                            protected void handleCompleted() {

                                if (!isSuccess()) {
                                    if (getStatus().getSeverity() == IStatus.ERROR) {
                                        MIPlugin.getDefault().getLog().log(getStatus());
                                    }
                                }

                                // Indicate that the pending request has completed
                                fPendingRequests.remove(breakpoint);

                                // Process the next pending update for this breakpoint
                                if (fPendingBreakpoints.contains(breakpoint)) {
                                    fPendingBreakpoints.remove(breakpoint);
                                    breakpointChanged(breakpoint, delta);
                                }
                            }
                        };
                        countingRm.setDoneCount(fPlatformBPs.size());

                        // Mark the breakpoint as being updated and go
                        fPendingRequests.add(breakpoint);
                        
                        // Modify the breakpoint in all the execution contexts
                        for (final IBreakpointsTargetDMContext dmc : fPlatformBPs.keySet()) {
                            determineDebuggerPath(dmc, attrs,
                                new RequestMonitor(getExecutor(), countingRm) {
                                    @Override
                                    protected void handleSuccess() {
                                        modifyBreakpoint(dmc, (ICBreakpoint) breakpoint, attrs, delta, new RequestMonitor(getExecutor(), countingRm));
                                    }
                                 });
                        }
                    }
                });
            } catch (CoreException e) {
            } catch (RejectedExecutionException e) {
            }
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.IBreakpointListener#breakpointRemoved(org.eclipse.debug.core.model.IBreakpoint, org.eclipse.core.resources.IMarkerDelta)
     */
    public void breakpointRemoved(final IBreakpoint breakpoint, IMarkerDelta delta) {

        if (supportsBreakpoint(breakpoint)) {
            try {
                getExecutor().execute(new DsfRunnable() {
                    public void run() {
                        CountingRequestMonitor countingRm = new CountingRequestMonitor(getExecutor(), null) {
                            @Override
                            protected void handleError() {
                                if (getStatus().getSeverity() == IStatus.ERROR) {
                                    MIPlugin.getDefault().getLog().log(getStatus());
                                }
                            }
                        };
                        countingRm.setDoneCount(fPlatformBPs.size());

                        // Remove the breakpoint in all the execution contexts
                        for (IBreakpointsTargetDMContext dmc : fPlatformBPs.keySet()) {
                            if (fPlatformBPs.get(dmc).containsKey(breakpoint)) {
                                uninstallBreakpoint(dmc, (ICBreakpoint) breakpoint, countingRm);
                            }
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // IServiceEventListener
    ///////////////////////////////////////////////////////////////////////////

    //-------------------------------------------------------------------------
    // Breakpoints
    //-------------------------------------------------------------------------

    @DsfServiceEventHandler
    public void eventDispatched(BreakpointAddedEvent e) {
    	// Nothing to do - already handled by breakpointAdded()
    }

    @DsfServiceEventHandler
    public void eventDispatched(BreakpointUpdatedEvent e) {
    	// Nothing to do - already handled by breakpointChanged()
    }

    @DsfServiceEventHandler
    public void eventDispatched(BreakpointRemovedEvent e) {
    	// Nothing to do - already handled by breakpointRemoved()
    }

    /*
     * When a watchpoint goes out of scope, it is automatically removed from
     * the back-end. To keep our internal state synchronized, we have to
     * remove it from our breakpoints maps.
     * Unfortunately, GDB doesn't generate the correct event...
     */
    @DsfServiceEventHandler
    public void eventDispatched(MIWatchpointScopeEvent e) {
    }

    //-------------------------------------------------------------------------
    // Breakpoint actions
    //-------------------------------------------------------------------------

    @DsfServiceEventHandler
    public void eventDispatched(MIBreakpointHitEvent e) {
        performBreakpointAction(e.getNumber());
    }

    @DsfServiceEventHandler
    public void eventDispatched(MIWatchpointTriggerEvent e) {
        performBreakpointAction(e.getNumber());
    }

    private void performBreakpointAction(int number) {
        // Identify the platform breakpoint
        final ICBreakpoint breakpoint = findPlatformBreakpoint(number);

        // FIXME: (Bug228703) Temporary hack to have a context
        Object[] contexts = fTargetBPs.keySet().toArray();
        final IBreakpointsTargetDMContext context = (IBreakpointsTargetDMContext) contexts[0];

        // Perform the actions asynchronously (otherwise we can have a deadlock...)
        new Job("Breakpoint action") { //$NON-NLS-1$
            { setSystem(true); }
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                fBreakpointActionManager.executeActions(breakpoint, new BreakpointActionAdapter(getExecutor(), getServicesTracker(), context));
                return Status.OK_STATUS;
            };
        }.schedule();
    }

    // Helper function to locate the platform breakpoint corresponding
    // to the target breakpoint/watchpoint that was just hit

    // FIXME: (Bug228703) Need a way to identify the correct context where the BP was hit
    private ICBreakpoint findPlatformBreakpoint(int targetBreakpointID) {
        Set<IBreakpointsTargetDMContext> targets = fTargetBPs.keySet();
        for (IBreakpointsTargetDMContext target : targets) {
            Map<IBreakpointDMContext, ICBreakpoint> bps = fTargetBPs.get(target);
            Set<IBreakpointDMContext> contexts = bps.keySet();
            for (IBreakpointDMContext context : contexts) {
                if (context instanceof MIBreakpointDMContext) {
                    MIBreakpointDMContext ctx = (MIBreakpointDMContext) context;
                    if (ctx.getReference() == targetBreakpointID) {
                        return bps.get(context);
                    }
                }
            }
        }
        return null;
    }

    //-------------------------------------------------------------------------
    // Session exit
    //-------------------------------------------------------------------------

	// Not used, kept for API compatibility.  
    /**
     * This method is left for API compatibility only.
     * ICommandControlShutdownDMEvent is used instead.
     * @nooverride This method is not intended to be re-implemented or extended by clients.
     * @noreference This method is not intended to be referenced by clients.
     */
    @DsfServiceEventHandler
    public void eventDispatched(MIGDBExitEvent e) {
        terminated();
    }

    /**
     * @since 1.1
     * @nooverride This method is not intended to be re-implemented or extended by clients.
     * @noreference This method is not intended to be referenced by clients.
     */
    @DsfServiceEventHandler
    public void eventDispatched(ICommandControlShutdownDMEvent e) {
        // bug 243899: The call to terminate results in an exception,  
        // need to investigate.
        //terminated();
    }

    private void terminated() {
        clearBreakpointStatus(fPlatformBPs.keySet().toArray(new ICBreakpoint[fPlatformBPs.size()]));
        fPlatformBPs.clear();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Breakpoint status handling functions
    ///////////////////////////////////////////////////////////////////////////

    /**
     * @param bps
     */
    private void clearBreakpointStatus(final ICBreakpoint[] bps)
    {
        new Job("Clear Breakpoints Status") { //$NON-NLS-1$
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                IWorkspaceRunnable wr = new IWorkspaceRunnable() {
                    public void run(IProgressMonitor monitor) throws CoreException {
                        for (ICBreakpoint breakpoint : bps) {
                        	breakpoint.resetInstallCount();
                        }
                    }
                };

                // Create the scheduling rule to clear all bp planted.
                ISchedulingRule rule = null;
                List<ISchedulingRule> markerRules = new ArrayList<ISchedulingRule>();
                for (ICBreakpoint bp : bps) {
                    IMarker marker = bp.getMarker();
                    if (marker != null) {
                        ISchedulingRule markerRule =
                            ResourcesPlugin.getWorkspace().getRuleFactory().markerRule(
                                    marker.getResource());
                        if (markerRule == null) {
                            markerRules = null;
                            break;
                        } else {
                            markerRules.add(markerRule);
                        }
                    }
                }
                if (markerRules != null) {
                    rule = MultiRule.combine(markerRules.toArray(new ISchedulingRule[markerRules.size()]));
                }

                try {
                    ResourcesPlugin.getWorkspace().run(wr, rule, 0, null);
                } catch (CoreException e) {
                    return e.getStatus();
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Support functions
    ///////////////////////////////////////////////////////////////////////////

    /**
     * supportsBreakpoint
     * 
     * Indicates if it is breakpoint we can deal with. For now, it boils down
     * to a CDI breakpoint...
     * 
     * @param bp
     * @return
     */
    private boolean supportsBreakpoint(IBreakpoint bp) {
        if (bp instanceof ICBreakpoint && bp.getModelIdentifier().equals(fDebugModelId)) {
            IMarker marker = bp.getMarker();
            if (marker != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * determineDebuggerPath
     * 
     * Adds the path to the source file to the set of attributes
     * (for the debugger).
     * 
     * @param dmc
     * @param attributes
     * @param rm
     */
    private void determineDebuggerPath(IBreakpointsTargetDMContext dmc,
            final Map<String, Object> attributes, final RequestMonitor rm)
    {
        String hostPath = (String) attributes.get(ICBreakpoint.SOURCE_HANDLE);

        if (hostPath != null) {

            ISourceLookupDMContext srcDmc = DMContexts.getAncestorOfType(dmc, ISourceLookupDMContext.class);
            if (srcDmc != null) {
                fSourceLookup.getDebuggerPath(srcDmc, hostPath,
                    new DataRequestMonitor<String>(getExecutor(), rm) {
                        @Override
                        protected void handleSuccess() {
                            attributes.put(ATTR_DEBUGGER_PATH, adjustDebuggerPath(getData()));
                            rm.done();
                        }
                    });
            } else {
                // Source lookup not available for given context, use the host
                // path for the debugger path.
                attributes.put(ATTR_DEBUGGER_PATH, adjustDebuggerPath(hostPath));
                rm.done();
            }
        } else {
            // Some types of breakpoints do not require a path
            // (e.g. watchpoints)
            rm.done();
        }
    }

    /**
     * See bug232415
     * 
     * @param path	the absolute path to the source file
     * @return
     */
    private String adjustDebuggerPath(String path) {
    	String result = path;
    	// Make it MinGW-specific
    	if (Platform.getOS().startsWith("win")) { //$NON-NLS-1$
        	if (!path.startsWith("/")) { //$NON-NLS-1$
        		result = path.substring(path.lastIndexOf('\\') + 1);
        	}
    	}
    	return result;
    }

    /**
     * Determine the set of modified attributes.
     * Elementary set operations in full action :-)
     * 
     * @param oldAttributes
     * @param newAttributes
     * @return
     */
    private Map<String, Object> determineAttributesDelta(Map<String, Object> oldAttributes, Map<String, Object> newAttributes) {

        Map<String, Object> delta = new HashMap<String,Object>();

        Set<String> oldKeySet = oldAttributes.keySet();
        Set<String> newKeySet = newAttributes.keySet();

        Set<String> commonKeys  = new HashSet<String>(newKeySet); commonKeys.retainAll(oldKeySet);
        Set<String> addedKeys   = new HashSet<String>(newKeySet); addedKeys.removeAll(oldKeySet);
        Set<String> removedKeys = new HashSet<String>(oldKeySet); removedKeys.removeAll(newKeySet);

        // Add the modified attributes
        for (String key : commonKeys) {
            if (!(oldAttributes.get(key).equals(newAttributes.get(key))))
                delta.put(key, newAttributes.get(key));
        }

        // Add the new attributes
        for (String key : addedKeys) {
            delta.put(key, newAttributes.get(key));
        }

        // Remove the deleted attributes
        for (String key : removedKeys) {
            delta.put(key, null);
        }

        return convertedAttributes(delta);
    }

    /**
     * Converts ICBreakpoint attributes to IBreakpoints attributes.
     * 
     * @param cdt_attributes
     * @return
     */
    private Map<String, Object> convertedAttributes(Map<String, Object> cdt_attributes) {

        Map<String,Object> result = new HashMap<String,Object>();

        // IBreakpoint attributes
        if (cdt_attributes.containsKey(ATTR_DEBUGGER_PATH))
            result.put(MIBreakpoints.FILE_NAME, cdt_attributes.get(ATTR_DEBUGGER_PATH));
            
        if (cdt_attributes.containsKey(IMarker.LINE_NUMBER))
            result.put(MIBreakpoints.LINE_NUMBER, cdt_attributes.get(IMarker.LINE_NUMBER));
            
        // ICLineBreakpoint attributes
        if (cdt_attributes.containsKey(ICLineBreakpoint.FUNCTION))
            result.put(MIBreakpoints.FUNCTION, cdt_attributes.get(ICLineBreakpoint.FUNCTION));
            
        if (cdt_attributes.containsKey(ICLineBreakpoint.ADDRESS))
            result.put(MIBreakpoints.ADDRESS, cdt_attributes.get(ICLineBreakpoint.ADDRESS));
            
        // ICBreakpoint attributes
        if (cdt_attributes.containsKey(ICBreakpoint.CONDITION))
            result.put(MIBreakpoints.CONDITION, cdt_attributes.get(ICBreakpoint.CONDITION));
            
        if (cdt_attributes.containsKey(ICBreakpoint.IGNORE_COUNT))
            result.put(MIBreakpoints.IGNORE_COUNT, cdt_attributes.get(ICBreakpoint.IGNORE_COUNT));

        if (cdt_attributes.containsKey(ICBreakpoint.ENABLED))
            result.put(MIBreakpoints.IS_ENABLED, cdt_attributes.get(ICBreakpoint.ENABLED));

        // ICWatchpoint attributes
        if (cdt_attributes.containsKey(ICWatchpoint.EXPRESSION))
            result.put(MIBreakpoints.EXPRESSION, cdt_attributes.get(ICWatchpoint.EXPRESSION));

        if (cdt_attributes.containsKey(ICWatchpoint.READ))
            result.put(MIBreakpoints.READ, cdt_attributes.get(ICWatchpoint.READ));
        
        if (cdt_attributes.containsKey(ICWatchpoint.WRITE))
            result.put(MIBreakpoints.WRITE, cdt_attributes.get(ICWatchpoint.WRITE));

        // Threads
        if (cdt_attributes.containsKey(ATTR_THREAD_FILTER))
            result.put(ATTR_THREAD_FILTER, cdt_attributes.get(ATTR_THREAD_FILTER));

        return result;
    }

    /**
     * Figure out the corresponding number of back-end breakpoints
     * Even though the thread IDs are usually integers, they are
     * stored as strings in CBreakpoints.
     * 
     * @param attributes
     * @return
     */
    @SuppressWarnings("unchecked")
    private Set<String> getThreads(Map<String, Object> attributes) {
        Set<String> threads = (Set<String>) attributes.get(ATTR_THREAD_FILTER);
        if (threads == null) {
            threads = new HashSet<String>();
            threads.add("0");    // Thread 0 means all threads //$NON-NLS-1$
        }
        return threads;
    }

    /**
     * Get the list of threads from the platform breakpoint attributes
     * 
     * @param breakpoint
     * @return
     */
    private Set<String> extractThreads(IBreakpointsTargetDMContext context, ICBreakpoint breakpoint) {
        Set<String> results = new HashSet<String>();

        // Find the ancestor
        List<IExecutionDMContext[]> threads = new ArrayList<IExecutionDMContext[]>(1);

        try {
            // Retrieve the targets
            IDsfBreakpointExtension filterExtension = getFilterExtension(breakpoint);
            IContainerDMContext[] targets = filterExtension.getTargetFilters();

            // If no target is present, breakpoint applies to all.
            if (targets.length == 0) {
                results.add("0"); //$NON-NLS-1$    
                return results;
            }

            // Extract the thread IDs (if there is none, we are covered)
            for (IContainerDMContext ctxt : targets) {
                if (DMContexts.isAncestorOf(ctxt, context)) {
                    threads.add(filterExtension.getThreadFilters(ctxt));
                }
            }
        } catch (CoreException e1) {
        }

        if (supportsThreads(breakpoint)) {
            for (IExecutionDMContext[] targetThreads : threads) {
                if (targetThreads != null) {
                    for (IExecutionDMContext thread : targetThreads) {
                        if (thread instanceof IMIExecutionDMContext) {
                        	IMIExecutionDMContext dmc = (IMIExecutionDMContext) thread;
                            results.add(((Integer) dmc.getThreadId()).toString());
                        }
                    }
                } else {
                    results.add("0"); //$NON-NLS-1$    
                    break;
                }
            }
        } else {
            results.add("0"); //$NON-NLS-1$
        }

        return results;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Non-generic (MI-specific) functions
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Create a target breakpoint from an ICBreakpoint
     * 
     * @param breakpoint
     * @param attributes
     * @return
     */
    protected Map<String,Object> convertToTargetBreakpoint(ICBreakpoint breakpoint, Map<String,Object> attributes) {

        Map<String, Object> properties = new HashMap<String, Object>();

        if (breakpoint instanceof ICWatchpoint) {
            // Convert the CDI watchpoint to an IBreakpoint
            properties.put(MIBreakpoints.BREAKPOINT_TYPE, MIBreakpoints.WATCHPOINT);
            properties.put(MIBreakpoints.EXPRESSION,      attributes.get(ICWatchpoint.EXPRESSION));
            properties.put(MIBreakpoints.READ,            attributes.get(ICWatchpoint.READ));
            properties.put(MIBreakpoints.WRITE,           attributes.get(ICWatchpoint.WRITE));
        }
        else if (breakpoint instanceof ICLineBreakpoint) {
            // Convert the CDI breakpoint to an IBreakpoint
            properties.put(MIBreakpoints.BREAKPOINT_TYPE, MIBreakpoints.BREAKPOINT);
            properties.put(MIBreakpoints.FILE_NAME,       attributes.get(ATTR_DEBUGGER_PATH));
            properties.put(MIBreakpoints.LINE_NUMBER,     attributes.get(IMarker.LINE_NUMBER));
            properties.put(MIBreakpoints.FUNCTION,        attributes.get(ICLineBreakpoint.FUNCTION));
            properties.put(MIBreakpoints.ADDRESS,         attributes.get(ICLineBreakpoint.ADDRESS));
        } else {
        	// catchpoint?
        }

        // Common fields
        properties.put(MIBreakpoints.CONDITION,           attributes.get(ICBreakpoint.CONDITION));
        properties.put(MIBreakpoints.IGNORE_COUNT,        attributes.get(ICBreakpoint.IGNORE_COUNT));
        properties.put(MIBreakpoints.IS_ENABLED,          attributes.get(ICBreakpoint.ENABLED));
        properties.put(MIBreakpointDMData.THREAD_ID,      attributes.get(ATTR_THREAD_ID));

        // Adjust for "skip-all"
        if (!fBreakpointManager.isEnabled()) {
            properties.put(MIBreakpoints.IS_ENABLED, false);
        }

        return properties;
    }

    /**
     * Determine if the modified attributes necessitate
     * a breakpoint removal/re-installation
     * 
     * @param delta
     * @return
     */
    protected boolean needsResinstallation(Map<String,Object> delta) {
        
        // Check if there is any modified attribute
        if (delta == null)
            return false;

        // Check the "critical" attributes
        if (delta.containsKey(ATTR_DEBUGGER_PATH)           // File name
        ||  delta.containsKey(IMarker.LINE_NUMBER)          // Line number
        ||  delta.containsKey(ICLineBreakpoint.FUNCTION)    // Function name
        ||  delta.containsKey(ICLineBreakpoint.ADDRESS)     // Absolute address
        ||  delta.containsKey(ATTR_THREAD_FILTER)           // Thread ID
        ||  delta.containsKey(ICWatchpoint.EXPRESSION)      // Watchpoint expression
        ||  delta.containsKey(ICWatchpoint.READ)            // Watchpoint type
        ||  delta.containsKey(ICWatchpoint.WRITE)) {        // Watchpoint type
            return true;
        }

         return false;
    }

    /**
     * @param breakpoint
     * @param oldValues
     */
    protected void rollbackAttributes(ICBreakpoint breakpoint, IMarkerDelta oldValues) {

        try {
            String new_condition = breakpoint.getCondition();
            if (new_condition == null)
                new_condition = NULL_STRING;
            String old_condition = (oldValues != null) ? oldValues.getAttribute(ICBreakpoint.CONDITION, NULL_STRING) : NULL_STRING;
            if (!old_condition.equals(new_condition)) {
                breakpoint.setCondition(old_condition);
            }
            else {
                breakpoint.setCondition(NULL_STRING);
            }
        } catch (CoreException e) {
        }
    }

    /**
     * Indicates if the back-end supports multiple threads for
     * this type of breakpoint
     * 
     * @param breakpoint
     */
    protected boolean supportsThreads(ICBreakpoint breakpoint) {

        return !(breakpoint instanceof ICWatchpoint);
    }

}
