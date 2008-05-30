/*******************************************************************************
 * Copyright (c) 2007, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.update;

import org.eclipse.dd.dsf.debug.service.IRunControl.ISuspendedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.StateChangeReason;
import org.eclipse.dd.dsf.ui.viewmodel.update.IElementUpdateTester;
import org.eclipse.dd.dsf.ui.viewmodel.update.ManualUpdatePolicy;

/**
 * 
 */
public class BreakpointHitUpdatePolicy extends ManualUpdatePolicy {
    
    public static String BREAKPOINT_HIT_UPDATE_POLICY_ID = "org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.update.breakpointHitUpdatePolicy";  //$NON-NLS-1$
    
    @Override
    public String getID() {
        return BREAKPOINT_HIT_UPDATE_POLICY_ID;
    }
    
    @Override
    public String getName() {
        return "Breakpoint Hit"; //$NON-NLS-1$
    }
    
    @Override
    public IElementUpdateTester getElementUpdateTester(Object event) {
        if(event instanceof ISuspendedDMEvent) {
            ISuspendedDMEvent suspendedEvent = (ISuspendedDMEvent)event; 
            if(suspendedEvent.getReason().equals(StateChangeReason.BREAKPOINT)) {
                return super.getElementUpdateTester(REFRESH_EVENT);
            }
        }
        return super.getElementUpdateTester(event);
    }
}
