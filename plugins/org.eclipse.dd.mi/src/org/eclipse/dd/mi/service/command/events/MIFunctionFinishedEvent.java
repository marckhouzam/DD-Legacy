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
 * *stopped,reason="function-finished",thread-id="0",frame={addr="0x0804855a",func="main",args=[{name="argc",value="1"},{name="argv",value="0xbffff18c"}],file="hello.c",line="17"},gdb-result-var="$1",return-value="10"
 */
@Immutable
public class MIFunctionFinishedEvent extends MIStoppedEvent {

    final private String gdbResult;
    final private String returnValue;
    final private String returnType;

    protected MIFunctionFinishedEvent(
        IExecutionDMContext ctx, int token, MIResult[] results, MIFrame frame, String gdbResult, 
        String returnValue, String returnType) 
    {
        super(ctx, token, results, frame);
        this.gdbResult = gdbResult;
        this.returnValue = returnValue;
        this.returnType = returnType;
    }

    public String getGDBResultVar() {
    	return gdbResult;
    }

    public String getReturnValue() {
    	return returnValue;
    }

    public String getReturnType() {
    	return returnType;
    }

    @Deprecated
    public static MIFunctionFinishedEvent parse(
        MIRunControl runControl, IContainerDMContext containerDmc, int token, MIResult[] results) 
    {
        String gdbResult = ""; //$NON-NLS-1$
        String returnValue = ""; //$NON-NLS-1$
        String returnType = ""; //$NON-NLS-1$
        
        for (int i = 0; i < results.length; i++) {
			String var = results[i].getVariable();
			MIValue value = results[i].getMIValue();
			String str = ""; //$NON-NLS-1$
			if (value instanceof MIConst) {
				str = ((MIConst)value).getString();
			}

			if (var.equals("gdb-result-var")) { //$NON-NLS-1$
				gdbResult = str;
			} else if (var.equals("return-value")) { //$NON-NLS-1$
				returnValue = str;
			} else if (var.equals("return-type")) { //$NON-NLS-1$
				returnType = str;
			} 
		}

        MIStoppedEvent stoppedEvent = MIStoppedEvent.parse(runControl, containerDmc, token, results); 
        return new MIFunctionFinishedEvent(stoppedEvent.getDMContext(), token, results, stoppedEvent.getFrame(), gdbResult, returnValue, returnType);
    }

    /**
     * @since 1.1
     */
    public static MIFunctionFinishedEvent parse(IExecutionDMContext dmc, int token, MIResult[] results) 
    {
       String gdbResult = ""; //$NON-NLS-1$
       String returnValue = ""; //$NON-NLS-1$
       String returnType = ""; //$NON-NLS-1$

       for (int i = 0; i < results.length; i++) {
           String var = results[i].getVariable();
           MIValue value = results[i].getMIValue();
           String str = ""; //$NON-NLS-1$
           if (value instanceof MIConst) {
               str = ((MIConst)value).getString();
           }

           if (var.equals("gdb-result-var")) { //$NON-NLS-1$
               gdbResult = str;
           } else if (var.equals("return-value")) { //$NON-NLS-1$
               returnValue = str;
           } else if (var.equals("return-type")) { //$NON-NLS-1$
               returnType = str;
           } 
       }

       MIStoppedEvent stoppedEvent = MIStoppedEvent.parse(dmc, token, results); 
       return new MIFunctionFinishedEvent(stoppedEvent.getDMContext(), token, results, stoppedEvent.getFrame(), gdbResult, returnValue, returnType);
    }
}
