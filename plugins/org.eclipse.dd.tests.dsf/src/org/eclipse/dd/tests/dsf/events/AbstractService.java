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
package org.eclipse.dd.tests.dsf.events;

import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.concurrent.ThreadSafe;
import org.eclipse.dd.dsf.service.AbstractDsfService;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.tests.dsf.DsfTestPlugin;
import org.osgi.framework.BundleContext;

/**
 * Test service class used to test event behavior.  It has three types of events
 * and three methods to receive the events.  
 *
 */
abstract public class AbstractService extends AbstractDsfService 
{
    AbstractService(DsfSession session) {
        super(session);
    }
    
    @Override protected BundleContext getBundleContext() {
        return DsfTestPlugin.getBundleContext();
    }    

    @Override public void initialize(final RequestMonitor requestMonitor) {
        super.initialize(
            new RequestMonitor(getExecutor(), requestMonitor) { 
                @Override
                public void handleSuccess() {
                    doInitialize(requestMonitor);
                }
            });
    }
            
    private void doInitialize(RequestMonitor requestMonitor) {
        getSession().addServiceEventListener(this, null);
        requestMonitor.done();
    }

    @Override public void shutdown(RequestMonitor requestMonitor) {
        getSession().removeServiceEventListener(this);
        super.shutdown(requestMonitor);
    }
    
    ///////////////////////////////////////////////////////////////////////////
    // Test API
    /** Records the number in the event 1 object when this service received the event. */
    int fEvent1RecipientNumber;

    /** Records the number in the event 2 object when this service received the event. */
    int fEvent2RecipientNumber;

    /** Records the number in the event 3 object when this service received the event. */
    int fEvent3RecipientNumber;
    
    /** Simple event class 1 */
    public class Event1 {
        // 1-based counter for the recipient of the event.
        int fRecipientNumberCounter = 1;
    }

    /** Simple event class 2.  Note it doesn't have any relation to event 1 */
    public class Event2 {
        int fRecipientNumberCounter = 1;        
    }

    /** Simple event class 3.  Note it does sub-class event 1 */
    public class Event3 extends Event1 {}
 
    @ThreadSafe
    public void dispatchEvent1() { 
        getSession().dispatchEvent(new Event1(), getProperties());
    }
    
    @ThreadSafe
    public void dispatchEvent2() { 
        getSession().dispatchEvent(new Event2(), getProperties());
    }
    
    @ThreadSafe
    public void dispatchEvent3() { 
        getSession().dispatchEvent(new Event3(), getProperties());
    }
    
    /**  Handles event 1 (and event 3 which derives from event 1) */
    @DsfServiceEventHandler public void eventDispatched(Event1 e) {
        fEvent1RecipientNumber = e.fRecipientNumberCounter++;
    }

    /**  Handles event 2 only */
    @DsfServiceEventHandler public void eventDispatched(Event2 e) {
        fEvent2RecipientNumber = e.fRecipientNumberCounter++;
    }

    /**  Handles event 3 only */
    @DsfServiceEventHandler public void eventDispatched(Event3 e) {
        fEvent3RecipientNumber = e.fRecipientNumberCounter++;
    }
}
