/*******************************************************************************
 * Copyright (c) 2004, 2005 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * QNX Software Systems - Initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.gdb.internal.ui.breakpoints;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.cdt.debug.ui.CDebugUIPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.IDsfStatusConstants;
import org.eclipse.dd.dsf.concurrent.ImmediateExecutor;
import org.eclipse.dd.dsf.concurrent.Query;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.debug.service.IProcesses;
import org.eclipse.dd.dsf.debug.service.IRunControl;
import org.eclipse.dd.dsf.debug.service.IProcesses.IProcessDMContext;
import org.eclipse.dd.dsf.debug.service.IProcesses.IThreadDMContext;
import org.eclipse.dd.dsf.debug.service.IProcesses.IThreadDMData;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.dsf.debug.service.command.ICommandControlService;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.gdb.internal.provisional.breakpoints.CBreakpointGdbThreadsFilterExtension;
import org.eclipse.dd.gdb.internal.provisional.launching.GdbLaunch;
import org.eclipse.dd.gdb.internal.provisional.service.IGDBBackend;
import org.eclipse.dd.gdb.internal.ui.GdbUIPlugin;
import org.eclipse.dd.mi.service.IMIExecutionDMContext;
import org.eclipse.dd.mi.service.IMIProcesses;
import org.eclipse.dd.mi.service.MIProcesses;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.jface.viewers.CheckStateChangedEvent;
import org.eclipse.jface.viewers.CheckboxTreeViewer;
import org.eclipse.jface.viewers.ICheckStateListener;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.osgi.util.tracker.ServiceTracker;

public class GdbThreadFilterEditor {

    /**
     * Comment for ThreadFilterEditor.
     */
    public class CheckHandler implements ICheckStateListener {

        /*
         * (non-Javadoc)
         * 
         * @see org.eclipse.jface.viewers.ICheckStateListener#checkStateChanged(org.eclipse.jface.viewers.CheckStateChangedEvent)
         */
        public void checkStateChanged(CheckStateChangedEvent event) {
            Object element = event.getElement();
            if (element instanceof IContainerDMContext) {
                checkTarget((IContainerDMContext) element, event.getChecked());
            } else if (element instanceof IExecutionDMContext) {
                checkThread((IExecutionDMContext) element, event.getChecked());
            }
        }

        /**
         * Check or uncheck a debug target in the tree viewer. When a debug
         * target is checked, attempt to check all of the target's threads by
         * default. When a debug target is unchecked, uncheck all its threads.
         */
        protected void checkTarget(IContainerDMContext target, boolean checked) {
            getThreadViewer().setChecked(target, checked);
            getThreadViewer().setGrayed(target, false);
            getThreadViewer().expandToLevel(target, AbstractTreeViewer.ALL_LEVELS);
            IExecutionDMContext[] threads = syncGetThreads(target);
            for (int i = 0; i < threads.length; i++) {
                getThreadViewer().setChecked(threads[i], checked);
                getThreadViewer().setGrayed(threads[i], false);
            }
        }

        /**
         * Check or uncheck a thread. Update the thread's debug target.
         */
        protected void checkThread(IExecutionDMContext thread, boolean checked) {
            getThreadViewer().setChecked(thread, checked);
            IContainerDMContext target = DMContexts.getAncestorOfType(thread, IContainerDMContext.class);
            IExecutionDMContext[] threads = syncGetThreads(target);
            int checkedNumber = 0;
            for (int i = 0; i < threads.length; i++) {
                if (getThreadViewer().getChecked(threads[i])) {
                    ++checkedNumber;
                }
            }
            if (checkedNumber == 0) {
                getThreadViewer().setChecked(target, false);
                getThreadViewer().setGrayed(target, false);
            } else if (checkedNumber == threads.length) {
                getThreadViewer().setChecked(target, true);
                getThreadViewer().setGrayed(target, false);
            } else {
                getThreadViewer().setGrayChecked(target, true);
            }
        }
    }

    /**
     * Comment for ThreadFilterEditor.
     */
    public class ThreadFilterContentProvider implements ITreeContentProvider {

        /**
         * Constructor for ThreadFilterContentProvider.
         */
        public ThreadFilterContentProvider() {
            super();
            // TODO Auto-generated constructor stub
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.eclipse.jface.viewers.ITreeContentProvider#getChildren(java.lang.Object)
         */
        public Object[] getChildren(Object parent) {
            if (parent instanceof IContainerDMContext) {
                return syncGetThreads((IContainerDMContext) parent);
            }

            if (parent instanceof ILaunchManager) {
                List<Object> children = new ArrayList<Object>();
                ILaunch[] launches = ((ILaunchManager) parent).getLaunches();
                IContainerDMContext target;
                for (int i = 0; i < launches.length; i++) {
                    if (launches[i] instanceof GdbLaunch) {
                        target = syncGetContainer((GdbLaunch) launches[i]);
                        if (target != null) {
                            children.add(target);
                        }
                    }
                }
                return children.toArray();
            }
            return new Object[0];
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.eclipse.jface.viewers.ITreeContentProvider#getParent(java.lang.Object)
         */
        public Object getParent(Object element) {
            if (element instanceof IContainerDMContext) {
                return DebugPlugin.getDefault().getLaunchManager();
            }
            if (element instanceof IExecutionDMContext) {
                return DMContexts.getAncestorOfType((IExecutionDMContext) element, IContainerDMContext.class);
            }
            return null;
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.eclipse.jface.viewers.ITreeContentProvider#hasChildren(java.lang.Object)
         */
        public boolean hasChildren(Object element) {
            return getChildren(element).length > 0;
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.eclipse.jface.viewers.IStructuredContentProvider#getElements(java.lang.Object)
         */
        public Object[] getElements(Object inputElement) {
            return getChildren(inputElement);
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.eclipse.jface.viewers.IContentProvider#dispose()
         */
        public void dispose() {
        }

        /*
         * (non-Javadoc)
         * 
         * @see org.eclipse.jface.viewers.IContentProvider#inputChanged(org.eclipse.jface.viewers.Viewer,
         *      java.lang.Object, java.lang.Object)
         */
        public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
        }
    }

    public class ThreadFilterLabelProvider extends LabelProvider {
        
        @Override
        public Image getImage(Object element) {
            if (element instanceof IContainerDMContext) {
                return DebugUITools.getImage(IDebugUIConstants.IMG_OBJS_DEBUG_TARGET);
            } else {
                return DebugUITools.getImage(IDebugUIConstants.IMG_OBJS_THREAD_RUNNING);
            }
        }
        
        @Override
        public String getText(Object element) {
            if (element instanceof IContainerDMContext) {
                return syncGetContainerLabel((IContainerDMContext)element);
            } else {
                return syncGetThreadLabel((IExecutionDMContext)element);
            }
        }
    }

    
    private CBreakpointGdbThreadFilterPage fPage;

    private CheckboxTreeViewer fThreadViewer;

    private ThreadFilterContentProvider fContentProvider;

    private CheckHandler fCheckHandler;

    /**
     * Constructor for ThreadFilterEditor.
     */
    public GdbThreadFilterEditor(Composite parent, CBreakpointGdbThreadFilterPage page) {
        fPage = page;
        fContentProvider = new ThreadFilterContentProvider();
        fCheckHandler = new CheckHandler();
        createThreadViewer(parent);
    }

    protected CBreakpointGdbThreadFilterPage getPage() {
        return fPage;
    }

    private void createThreadViewer(Composite parent) {
        Label label = new Label(parent, SWT.NONE);
        label.setText("&Restrict to Selected Targets and Threads:"); //$NON-NLS-1$
        label.setFont(parent.getFont());
        label.setLayoutData(new GridData());
        GridData data = new GridData(GridData.FILL_BOTH);
        data.heightHint = 100;
        fThreadViewer = new CheckboxTreeViewer(parent, SWT.BORDER);
        fThreadViewer.addCheckStateListener(fCheckHandler);
        fThreadViewer.getTree().setLayoutData(data);
        fThreadViewer.getTree().setFont(parent.getFont());
        fThreadViewer.setContentProvider(fContentProvider);
        fThreadViewer.setLabelProvider(new ThreadFilterLabelProvider());
        fThreadViewer.setInput(DebugPlugin.getDefault().getLaunchManager());
        setInitialCheckedState();
    }

    /**
     * Returns the debug targets that appear in the tree
     */
    protected IContainerDMContext[] getDebugTargets() {
        Object input = fThreadViewer.getInput();
        if (!(input instanceof ILaunchManager)) {
            return new IContainerDMContext[0];
        }
        List<Object> targets = new ArrayList<Object>();
        ILaunch[] launches = ((ILaunchManager) input).getLaunches();
        IContainerDMContext target;
        for (int i = 0; i < launches.length; i++) {
            if (launches[i] instanceof GdbLaunch) {
                target = syncGetContainer((GdbLaunch) launches[i]);
                if (target != null) {
                    targets.add(target);
                }
            }
        }
        return targets.toArray(new IContainerDMContext[targets.size()]);
    }

    protected CheckboxTreeViewer getThreadViewer() {
        return fThreadViewer;
    }

    /**
     * Sets the initial checked state of the tree viewer. The initial state
     * should reflect the current state of the breakpoint. If the breakpoint has
     * a thread filter in a given thread, that thread should be checked.
     */
    protected void setInitialCheckedState() {
        CBreakpointGdbThreadsFilterExtension filterExtension = fPage.getFilterExtension();
        try {
            IContainerDMContext[] targets = filterExtension.getTargetFilters();

            // TODO: Hack to properly initialize the target/thread list
            // Should be done in filterExtension.initialize() but we don't know
            // how to get the target list from an ICBreakpoint...
            if (targets.length == 0) {
            	targets = getDebugTargets();
            	for (IContainerDMContext target : targets) {
            		filterExtension.setTargetFilter(target);
            	}
            }
            // TODO: End of hack

            for (int i = 0; i < targets.length; i++) {
                IExecutionDMContext[] filteredThreads = filterExtension.getThreadFilters(targets[i]);
                if (filteredThreads != null) {
                    for (int j = 0; j < filteredThreads.length; ++j)
                        fCheckHandler.checkThread(filteredThreads[j], true);
                } else {
                    fCheckHandler.checkTarget(targets[i], true);
                }
            }
        } catch (CoreException e) {
            CDebugUIPlugin.log(e);
        }
    }

    protected void doStore() {
        CBreakpointGdbThreadsFilterExtension filterExtension = fPage.getFilterExtension();
        IContainerDMContext[] targets = getDebugTargets();
        for (int i = 0; i < targets.length; ++i) {
            try {
                if (getThreadViewer().getChecked(targets[i])) {
                    if (getThreadViewer().getGrayed(targets[i])) {
                        IExecutionDMContext[] threads = getTargetThreadFilters(targets[i]);
                        filterExtension.setThreadFilters(threads);
                    } else {
                        filterExtension.setTargetFilter(targets[i]);
                    }
                } else {
                    filterExtension.removeTargetFilter(targets[i]);
                }
                DebugPlugin.getDefault().getBreakpointManager().fireBreakpointChanged(fPage.getBreakpoint());
            } catch (CoreException e) {
                CDebugUIPlugin.log(e);
            }
        }
    }

    private IExecutionDMContext[] getTargetThreadFilters(IContainerDMContext target) {
        Object[] threads = ((ITreeContentProvider) getThreadViewer().getContentProvider()).getChildren(target);
        ArrayList<Object> list = new ArrayList<Object>(threads.length);
        for (int i = 0; i < threads.length; ++i) {
            if (getThreadViewer().getChecked(threads[i]))
                list.add(threads[i]);
        }
        return list.toArray(new IExecutionDMContext[list.size()]);
    }

    private IContainerDMContext syncGetContainer(final GdbLaunch launch) {
        final DsfSession session = launch.getSession();

        class ContainerQuery extends Query<IContainerDMContext> {
            @Override
            protected void execute(DataRequestMonitor<IContainerDMContext> rm) {
                if (!session.isActive()) {
                    rm.setStatus(getFailStatus(IDsfStatusConstants.INVALID_STATE, "Launch's session not active.")); //$NON-NLS-1$
                    rm.done();
                    return;
                }

                ServiceTracker tracker1 = new ServiceTracker(GdbUIPlugin.getBundleContext(), ICommandControlService.class
                    .getName(), null);
                tracker1.open();

                ICommandControlService commandControl = (ICommandControlService) tracker1.getService();
                ServiceTracker tracker2 = new ServiceTracker(GdbUIPlugin.getBundleContext(), IMIProcesses.class
                    .getName(), null);
                tracker2.open();
                IMIProcesses procService = (IMIProcesses) tracker2.getService();
                
                if (commandControl != null && procService != null) {
               		IProcessDMContext procDmc = procService.createProcessContext(commandControl.getContext(), MIProcesses.UNIQUE_GROUP_ID);
               		IContainerDMContext containerDmc = procService.createContainerContext(procDmc, MIProcesses.UNIQUE_GROUP_ID);

                    rm.setData(containerDmc);
                } else {
                    rm.setStatus(getFailStatus(IDsfStatusConstants.INVALID_STATE, "GDB Control or Process service not accessible.")); //$NON-NLS-1$
                }
                rm.done();
                tracker1.close();
                tracker2.close();
            }
        }

        ContainerQuery query = new ContainerQuery();
        try {
            session.getExecutor().execute(query);
            return query.get();
        } catch (RejectedExecutionException e) {
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
        }
        return null;
    }

    private IExecutionDMContext[] syncGetThreads(final IContainerDMContext container) {
        final DsfSession session = DsfSession.getSession(container.getSessionId());
        if (session == null) {
            return new IExecutionDMContext[0];
        }

        class ThreadsQuery extends Query<IExecutionDMContext[]> {
            @Override
            protected void execute(DataRequestMonitor<IExecutionDMContext[]> rm) {
                if (!session.isActive()) {
                    rm.setStatus(getFailStatus(IDsfStatusConstants.INVALID_STATE, "Container's session not active.")); //$NON-NLS-1$
                    rm.done();
                    return;
                }

                ServiceTracker tracker = new ServiceTracker(GdbUIPlugin.getBundleContext(), IRunControl.class
                    .getName(), null);
                tracker.open();
                IRunControl runControl = (IRunControl) tracker.getService();
                if (runControl != null) {
                    runControl.getExecutionContexts(container, rm);
                } else {
                    rm.setStatus(getFailStatus(IDsfStatusConstants.INVALID_STATE, "GDB Control not accessible.")); //$NON-NLS-1$
                    rm.done();
                }
                tracker.close();
            }
        }

        ThreadsQuery query = new ThreadsQuery();
        try {
            session.getExecutor().execute(query);
            return query.get();
        } catch (RejectedExecutionException e) {
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
        }
        return new IExecutionDMContext[0];
    }

    private String syncGetContainerLabel(final IContainerDMContext container) {
        final DsfSession session = DsfSession.getSession(container.getSessionId());
        if (session == null) {
            return "Error reading data"; //$NON-NLS-1$
        }

        class ContainerLabelQuery extends Query<String> {
            @Override
            protected void execute(DataRequestMonitor<String> rm) {
                if (!session.isActive()) {
                    rm.setStatus(getFailStatus(IDsfStatusConstants.INVALID_STATE, "Container's session not active.")); //$NON-NLS-1$
                    rm.done();
                    return;
                }

                ServiceTracker tracker = new ServiceTracker(GdbUIPlugin.getBundleContext(), IGDBBackend.class
                    .getName(), null);
                tracker.open();
                IGDBBackend backend = (IGDBBackend) tracker.getService();
                if (backend != null) {
                    rm.setData(backend.getProgramPath().toOSString());
                } else {
                    rm.setStatus(getFailStatus(IDsfStatusConstants.INVALID_STATE, "GDB Backend not accessible.")); //$NON-NLS-1$
                }
                rm.done();
                tracker.close();
            }
        }

        ContainerLabelQuery query = new ContainerLabelQuery();
        try {
            session.getExecutor().execute(query);
            return query.get();
        } catch (RejectedExecutionException e) {
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
        }
        return "Error reading data"; //$NON-NLS-1$
    }

    private String syncGetThreadLabel(final IExecutionDMContext thread) {
        final DsfSession session = DsfSession.getSession(thread.getSessionId());
        if (session == null) {
            return "Error reading data"; //$NON-NLS-1$
        }

        class ThreadLabelQuery extends Query<String> {
            @Override
            protected void execute(final DataRequestMonitor<String> rm) {
                if (!session.isActive()) {
                    rm.setStatus(getFailStatus(IDsfStatusConstants.INVALID_STATE, "Container's session not active.")); //$NON-NLS-1$
                    rm.done();
                    return;
                }

                ServiceTracker tracker = new ServiceTracker(GdbUIPlugin.getBundleContext(), IProcesses.class
                    .getName(), null);
                tracker.open();
                IProcesses procService = (IProcesses) tracker.getService();
                if (procService != null) {
                    IThreadDMContext threadDmc = DMContexts.getAncestorOfType(thread, IThreadDMContext.class);
                	procService.getExecutionData(threadDmc, new DataRequestMonitor<IThreadDMData>(
                        ImmediateExecutor.getInstance(), rm) {
                        @Override
                        protected void handleSuccess() {
                            final StringBuilder builder = new StringBuilder("Thread["); //$NON-NLS-1$
                            builder.append(((IMIExecutionDMContext)thread).getThreadId());
                            builder.append("] "); //$NON-NLS-1$
                            builder.append(getData().getId());
                            builder.append(getData().getName());

                            rm.setData(builder.toString());
                            rm.done();
                        }
                    });
                } else {
                    rm.setStatus(getFailStatus(IDsfStatusConstants.INVALID_STATE, "IProcesses service not accessible.")); //$NON-NLS-1$
                    rm.done();
                }
                tracker.close();
            }
        }

        ThreadLabelQuery query = new ThreadLabelQuery();
        try {
            session.getExecutor().execute(query);
            return query.get();
        } catch (RejectedExecutionException e) {
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
        }
        return "Error reading data"; //$NON-NLS-1$
    }

    private Status getFailStatus(int code, String message) {
        return new Status(IStatus.ERROR, GdbUIPlugin.PLUGIN_ID, code, message, null);
    }
}
