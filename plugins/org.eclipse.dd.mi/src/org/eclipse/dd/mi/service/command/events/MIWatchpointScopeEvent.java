/*******************************************************************************
 * Copyright (c) 2000, 2007 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     QNX Software Systems - Initial API and implementation
 *     Wind River Systems   - Modified for new DSF Reference Implementation
 *******************************************************************************/

package org.eclipse.dd.mi.service.command.events;

import org.eclipse.dd.dsf.concurrent.Immutable;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.mi.service.MIRunControl;
import org.eclipse.dd.mi.service.command.output.MIConst;
import org.eclipse.dd.mi.service.command.output.MIFrame;
import org.eclipse.dd.mi.service.command.output.MIResult;
import org.eclipse.dd.mi.service.command.output.MIValue;

/**
 *  *stopped,reason="watchpoint-scope",wpnum="5",
 *
 */
@Immutable
public class MIWatchpointScopeEvent extends MIStoppedEvent {

    final private int number;

    protected MIWatchpointScopeEvent(
        IExecutionDMContext ctx, int token, MIResult[] results, MIFrame frame, int number)
    {
        super(ctx, token, results, frame);
        this.number = number;
    }

    public int getNumber() {
        return number;
    }

    @Deprecated
    public static MIWatchpointScopeEvent parse(
        MIRunControl runControl, IContainerDMContext containerDmc, int token, MIResult[] results) 
    {
        int number = 0;
        for (int i = 0; i < results.length; i++) {
            String var = results[i].getVariable();
            MIValue value = results[i].getMIValue();

            if (var.equals("wpnum")) { //$NON-NLS-1$
                if (value instanceof MIConst) {
                    String str = ((MIConst) value).getString();
                    try {
                        number = Integer.parseInt(str.trim());
                    } catch (NumberFormatException e) {
                    }
                }
            } 
        }

        MIStoppedEvent stoppedEvent = MIStoppedEvent.parse(runControl, containerDmc, token, results); 
        return new MIWatchpointScopeEvent(stoppedEvent.getDMContext(), token, results, stoppedEvent.getFrame(), number);
    }

    /**
     * @since 1.1
     */
    public static MIWatchpointScopeEvent parse(IExecutionDMContext dmc, int token, MIResult[] results) 
    {
       int number = 0;
       for (int i = 0; i < results.length; i++) {
           String var = results[i].getVariable();
           MIValue value = results[i].getMIValue();

           if (var.equals("wpnum")) { //$NON-NLS-1$
               if (value instanceof MIConst) {
                   String str = ((MIConst) value).getString();
                   try {
                       number = Integer.parseInt(str.trim());
                   } catch (NumberFormatException e) {
                   }
               }
           } 
       }

       MIStoppedEvent stoppedEvent = MIStoppedEvent.parse(dmc, token, results); 
       return new MIWatchpointScopeEvent(stoppedEvent.getDMContext(), token, results, stoppedEvent.getFrame(), number);
    }
}
