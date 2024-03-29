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
import org.eclipse.dd.mi.service.command.output.MITuple;
import org.eclipse.dd.mi.service.command.output.MIValue;

/**
 *  *stopped,reason="watchpoint-trigger",wpt={number="2",exp="i"},value={old="0",new="1"},thread-id="0",frame={addr="0x08048534",func="main",args=[{name="argc",value="1"},{name="argv",value="0xbffff18c"}],file="hello.c",line="10"}
 *
 */
@Immutable
public class MIWatchpointTriggerEvent extends MIStoppedEvent {

    final private int number;
    final private String exp;
    final private String oldValue;
    final private String newValue;

    protected MIWatchpointTriggerEvent(
        IExecutionDMContext ctx, int token, MIResult[] results, MIFrame frame, 
        int number, String exp, String oldValue, String newValue) 
    {
        super(ctx, token, results, frame);
        this.number = number;
        this.exp = exp;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public int getNumber() {
        return number;
    }

    public String getExpression() {
        return exp;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    @Deprecated
    public static MIWatchpointTriggerEvent parse(
        MIRunControl runControl, IContainerDMContext containerDmc, int token, MIResult[] results) 
    {
        int number = 0;
        String exp = ""; //$NON-NLS-1$
        String oldValue = ""; //$NON-NLS-1$
        String newValue = ""; //$NON-NLS-1$

        for (int i = 0; i < results.length; i++) {
            String var = results[i].getVariable();
            MIValue value = results[i].getMIValue();

            if (var.equals("wpt") || var.equals("hw-awpt") || var.equals("hw-rwpt")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if (value instanceof MITuple) {
                    for (MIResult wptResult : ((MITuple) value).getMIResults()) {
                        String wptVar = wptResult.getVariable();
                        MIValue wptValue = wptResult.getMIValue();

                        if (wptVar.equals("number")) { //$NON-NLS-1$
                            if (wptValue instanceof MIConst) {
                                String str = ((MIConst) wptValue).getString();
                                try {
                                    number = Integer.parseInt(str);
                                } catch (NumberFormatException e) {
                                }
                            }
                        } else if (wptVar.equals("exp")) { //$NON-NLS-1$
                            if (wptValue instanceof MIConst) {
                                exp = ((MIConst) wptValue).getString();
                            }
                        }
                    }
                }
            } else if (var.equals("value")) { //$NON-NLS-1$
                if (value instanceof MITuple) {
                    for (MIResult valueResult : ((MITuple)value).getMIResults()) {
                        String valueVar = valueResult.getVariable();
                        MIValue valueValue = valueResult.getMIValue();
                        String str = ""; //$NON-NLS-1$
                        if (valueValue instanceof MIConst) {
                            str = ((MIConst) valueValue).getString();
                        }

                        if (valueVar.equals("old")) { //$NON-NLS-1$
                            oldValue = str;
                        } else if (valueVar.equals("new")) { //$NON-NLS-1$
                            newValue = str;
                        } else if (valueVar.equals("value")) { //$NON-NLS-1$
                            oldValue = newValue = str;
                        }
                    }

                }
            } 
        }
        MIStoppedEvent stoppedEvent = MIStoppedEvent.parse(runControl, containerDmc, token, results); 
        return new MIWatchpointTriggerEvent(stoppedEvent.getDMContext(), token, results, stoppedEvent.getFrame(), number, exp, oldValue, newValue);
    }

    /**
     * @since 1.1
     */
    public static MIWatchpointTriggerEvent parse(IExecutionDMContext dmc, int token, MIResult[] results) 
    {
       int number = 0;
       String exp = ""; //$NON-NLS-1$
       String oldValue = ""; //$NON-NLS-1$
       String newValue = ""; //$NON-NLS-1$

       for (int i = 0; i < results.length; i++) {
           String var = results[i].getVariable();
           MIValue value = results[i].getMIValue();

           if (var.equals("wpt") || var.equals("hw-awpt") || var.equals("hw-rwpt")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
               if (value instanceof MITuple) {
                   for (MIResult wptResult : ((MITuple) value).getMIResults()) {
                       String wptVar = wptResult.getVariable();
                       MIValue wptValue = wptResult.getMIValue();

                       if (wptVar.equals("number")) { //$NON-NLS-1$
                           if (wptValue instanceof MIConst) {
                               String str = ((MIConst) wptValue).getString();
                               try {
                                   number = Integer.parseInt(str);
                               } catch (NumberFormatException e) {
                               }
                           }
                       } else if (wptVar.equals("exp")) { //$NON-NLS-1$
                           if (wptValue instanceof MIConst) {
                               exp = ((MIConst) wptValue).getString();
                           }
                       }
                   }
               }
           } else if (var.equals("value")) { //$NON-NLS-1$
               if (value instanceof MITuple) {
                   for (MIResult valueResult : ((MITuple)value).getMIResults()) {
                       String valueVar = valueResult.getVariable();
                       MIValue valueValue = valueResult.getMIValue();
                       String str = ""; //$NON-NLS-1$
                       if (valueValue instanceof MIConst) {
                           str = ((MIConst) valueValue).getString();
                       }

                       if (valueVar.equals("old")) { //$NON-NLS-1$
                           oldValue = str;
                       } else if (valueVar.equals("new")) { //$NON-NLS-1$
                           newValue = str;
                       } else if (valueVar.equals("value")) { //$NON-NLS-1$
                           oldValue = newValue = str;
                       }
                   }

               }
           } 
       }
       MIStoppedEvent stoppedEvent = MIStoppedEvent.parse(dmc, token, results); 
       return new MIWatchpointTriggerEvent(stoppedEvent.getDMContext(), token, results, stoppedEvent.getFrame(), number, exp, oldValue, newValue);
    }
}
