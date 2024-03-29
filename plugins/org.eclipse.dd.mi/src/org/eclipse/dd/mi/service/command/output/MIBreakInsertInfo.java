/*******************************************************************************
 * Copyright (c) 2000, 2006 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     QNX Software Systems - Initial API and implementation
 *     Wind River Systems   - Modified for new DSF Reference Implementation
 *******************************************************************************/

package org.eclipse.dd.mi.service.command.output;

import java.util.ArrayList;
import java.util.List;

/**
 * -break-insert main
 * ^done,bkpt={number="1",type="breakpoint",disp="keep",enabled="y",addr="0x08048468",func="main",file="hello.c",line="4",times="0"}
 * -break-insert -a p
 * ^done,hw-awpt={number="2",exp="p"}
 * -break-watch -r p
 * ^done,hw-rwpt={number="4",exp="p"}
 * -break-watch p
 * ^done,wpt={number="6",exp="p"}
 */
public class MIBreakInsertInfo extends MIInfo {

    MIBreakpoint[] breakpoints;

    public MIBreakInsertInfo(MIOutput record) {
        super(record);
        breakpoints = null;
        List<MIBreakpoint> aList = new ArrayList<MIBreakpoint>(1);
        if (isDone()) {
            MIResultRecord rr = record.getMIResultRecord();
            if (rr != null) {
                MIResult[] results =  rr.getMIResults();
                for (int i = 0; i < results.length; i++) {
                    String var = results[i].getVariable();
                    MIValue val = results[i].getMIValue();
                    MIBreakpoint bpt = null;
                    if (var.equals("wpt")) { //$NON-NLS-1$
                        if (val instanceof MITuple) {
                            bpt = new MIBreakpoint((MITuple)val);
                            bpt.setEnabled(true);
                            bpt.setWriteWatchpoint(true);
                        }
                    } else if (var.equals("bkpt")) { //$NON-NLS-1$
                        if (val instanceof MITuple) {
                            bpt = new MIBreakpoint((MITuple)val);
                            bpt.setEnabled(true);
                        }
                    } else if (var.equals("hw-awpt")) { //$NON-NLS-1$
                        if (val instanceof MITuple) {
                            bpt = new MIBreakpoint((MITuple)val);
                            bpt.setAccessWatchpoint(true);
                            bpt.setEnabled(true);
                        }
                    } else if (var.equals("hw-rwpt")) { //$NON-NLS-1$
                        if (val instanceof MITuple) {
                            bpt = new MIBreakpoint((MITuple)val);
                            bpt.setReadWatchpoint(true);
                            bpt.setEnabled(true);
                        }
                    }
                    if (bpt != null) {
                        aList.add(bpt);
                    }
                }
            }
        }
        breakpoints = aList.toArray(new MIBreakpoint[aList.size()]);
    }

    public MIBreakpoint[] getMIBreakpoints() {
        return breakpoints;
    }
}
