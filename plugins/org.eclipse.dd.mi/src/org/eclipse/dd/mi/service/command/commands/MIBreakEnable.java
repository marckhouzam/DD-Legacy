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

package org.eclipse.dd.mi.service.command.commands;

import org.eclipse.dd.dsf.debug.service.IBreakpoints.IBreakpointsTargetDMContext;
import org.eclipse.dd.mi.service.command.output.MIInfo;

/**
 * 
 *    -break-enable ( BREAKPOINT )+
 *
 * Enable (previously disabled) BREAKPOINT(s).
 * 
 * Result:
 *  ^done
 */
 
public class MIBreakEnable extends MICommand<MIInfo>
{
    public MIBreakEnable (IBreakpointsTargetDMContext ctx, int[] array) {
        super(ctx, "-break-enable"); //$NON-NLS-1$
        if (array != null && array.length > 0) {
            String[] brkids = new String[array.length];
            for (int i = 0; i < array.length; i++) {
                brkids[i] = Integer.toString(array[i]);
            }
            setParameters(brkids);
        } 
    }
}
