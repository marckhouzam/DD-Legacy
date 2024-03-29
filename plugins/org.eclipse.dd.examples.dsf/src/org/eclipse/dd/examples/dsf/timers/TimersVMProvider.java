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
package org.eclipse.dd.examples.dsf.timers;

import java.util.concurrent.RejectedExecutionException;

import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.dsf.ui.viewmodel.AbstractVMAdapter;
import org.eclipse.dd.dsf.ui.viewmodel.IRootVMNode;
import org.eclipse.dd.dsf.ui.viewmodel.IVMNode;
import org.eclipse.dd.dsf.ui.viewmodel.datamodel.AbstractDMVMProvider;
import org.eclipse.dd.examples.dsf.timers.AlarmService.TriggersChangedEvent;
import org.eclipse.dd.examples.dsf.timers.TimerService.TimersChangedEvent;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IColumnPresentation;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;

/**
 * The View Model provider for the Timers view.  This provider allows for 
 * switching between two different view layouts:
 * <ol>
 *  <li>Timers -> Triggers -> Alarms</li>
 *  <li>Triggers -> Timers -> Alarms</li>
 * </ol>  
 * A special event is sent when the layout is changed in order to generate
 * a proper delta to refresh the view. 
 */
@SuppressWarnings("restriction")
public class TimersVMProvider extends AbstractDMVMProvider {

    /** Event indicating that the timers view layout has changed */
    public static class TimersViewLayoutChanged {}
    
    /** Enumeration of possible layouts for the timers view model */
    public enum ViewLayout { TRIGGERS_AT_TOP, TIMERS_AT_TOP }
    
    public TimersVMProvider(AbstractVMAdapter adapter, IPresentationContext presentationContext, DsfSession session) {
        super(adapter, presentationContext, session);
        // Set the initial view layout.
        setViewLayout(ViewLayout.TIMERS_AT_TOP);   
    }
    
    /** 
     * Configures a new layout for the timers view model.
     * @param layout New layout to use.
     */
    public void setViewLayout(ViewLayout layout) {
        clearNodes();
        if (layout == ViewLayout.TRIGGERS_AT_TOP) {
            IRootVMNode root = new TimersRootVMNode(this); 
            IVMNode triggersNode = new TriggersVMNode(this, getSession());
            addChildNodes(root, new IVMNode[] { triggersNode });
            IVMNode timersNode = new TimersVMNode(this, getSession());
            addChildNodes(triggersNode, new IVMNode[] { timersNode });
            IVMNode alarmNode = new AlarmsVMNode(this, getSession());
            addChildNodes(timersNode, new IVMNode[] { alarmNode });
            setRootNode(root);
        } else if (layout == ViewLayout.TIMERS_AT_TOP) {
            IRootVMNode root = new TimersRootVMNode(this); 
            IVMNode timersNode = new TimersVMNode(this, getSession());
            addChildNodes(root, new IVMNode[] { timersNode });
            IVMNode triggersNode = new TriggersVMNode(this, getSession());
            addChildNodes(timersNode, new IVMNode[] { triggersNode });
            IVMNode alarmNode = new AlarmsVMNode(this, getSession());
            addChildNodes(triggersNode, new IVMNode[] { alarmNode });
            setRootNode(root);
        }
        
        handleEvent(new TimersViewLayoutChanged());
    }
    
    @Override
    public IColumnPresentation createColumnPresentation(IPresentationContext context, Object element) {
        return new TimersViewColumnPresentation();
    }

    @Override
    public String getColumnPresentationId(IPresentationContext context, Object element) {
        return TimersViewColumnPresentation.ID;
    }

    // Add a handler for the triggers and timers changed events.  The 
    // AbstractDMVMProvider superclass automatically registers this provider
    // for all IDMEvent events, however these two events do not implement
    // IDMEvent
    @DsfServiceEventHandler
    public void eventDispatched(final TriggersChangedEvent event) {
        if (isDisposed()) return;

        try {
            getExecutor().execute(new Runnable() {
                public void run() {
                    if (isDisposed()) return;
                    handleEvent(event);
                }
            });
        } catch (RejectedExecutionException e) {}
    }

    @DsfServiceEventHandler
    public void eventDispatched(final TimersChangedEvent event) {
        if (isDisposed()) return;

        try {
            getExecutor().execute(new Runnable() {
                public void run() {
                    if (isDisposed()) return;
                    handleEvent(event);
                }
            });
        } catch (RejectedExecutionException e) {}
    }
}
