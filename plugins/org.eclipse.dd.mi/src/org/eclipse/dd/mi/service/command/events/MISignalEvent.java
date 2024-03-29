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
 *  *stopped,reason="signal-received",signal-name="SIGINT",signal-meaning="Interrupt",thread-id="0",frame={addr="0x400e18e1",func="__libc_nanosleep",args=[],file="__libc_nanosleep",line="-1"}
 *
 */
@Immutable
public class MISignalEvent extends MIStoppedEvent {

    final private String sigName;
    final private String sigMeaning;

    protected MISignalEvent(
        IExecutionDMContext ctx, int token, MIResult[] results, MIFrame frame, 
        String sigName, String sigMeaning) 
    {
        super(ctx, token, results, frame);
        this.sigName = sigName;
        this.sigMeaning = sigMeaning;
    }
    
    public String getName() {
    	return sigName;
    }

    public String getMeaning() {
    	return sigMeaning;
    }


    @Deprecated
    public static MISignalEvent parse(
        MIRunControl runControl, IContainerDMContext containerDmc, int token, MIResult[] results) 
    {
        String sigName = ""; //$NON-NLS-1$
        String sigMeaning = ""; //$NON-NLS-1$

        for (int i = 0; i < results.length; i++) {
			String var = results[i].getVariable();
			MIValue value = results[i].getMIValue();
			String str = ""; //$NON-NLS-1$
			if (value instanceof MIConst) {
				str = ((MIConst)value).getString();
			}

			if (var.equals("signal-name")) { //$NON-NLS-1$
				sigName = str;
			} else if (var.equals("signal-meaning")) { //$NON-NLS-1$
				sigMeaning = str;
			} 
		}
        MIStoppedEvent stoppedEvent = MIStoppedEvent.parse(runControl, containerDmc, token, results); 
        return new MISignalEvent(stoppedEvent.getDMContext(), token, results, stoppedEvent.getFrame(), sigName, sigMeaning);

    }
    
    /**
     * @since 1.1
     */
    public static MISignalEvent parse(IExecutionDMContext dmc, int token, MIResult[] results) 
    {
       String sigName = ""; //$NON-NLS-1$
       String sigMeaning = ""; //$NON-NLS-1$

       for (int i = 0; i < results.length; i++) {
           String var = results[i].getVariable();
           MIValue value = results[i].getMIValue();
           String str = ""; //$NON-NLS-1$
           if (value instanceof MIConst) {
               str = ((MIConst)value).getString();
           }

           if (var.equals("signal-name")) { //$NON-NLS-1$
               sigName = str;
           } else if (var.equals("signal-meaning")) { //$NON-NLS-1$
               sigMeaning = str;
           } 
       }
       MIStoppedEvent stoppedEvent = MIStoppedEvent.parse(dmc, token, results); 
       return new MISignalEvent(stoppedEvent.getDMContext(), token, results, stoppedEvent.getFrame(), sigName, sigMeaning);
    }

}
