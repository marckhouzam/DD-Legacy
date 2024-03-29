/*******************************************************************************
 * Copyright (c) 2007 Ericsson and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Ericsson - Initial API and implementation
 *******************************************************************************/

package org.eclipse.dd.mi.service.command.commands;

import org.eclipse.dd.dsf.debug.service.IBreakpoints.IBreakpointsTargetDMContext;
import org.eclipse.dd.mi.service.command.output.MIInfo;

/**
 * 
 *  -break-after NUMBER COUNT
 *
 *  The breakpoint number NUMBER is not in effect until it has been hit
 *  COUNT times.  The count becomes part of the `-break-list' output
 *  (see the description of the DsfMIBreakList).
 */
 
public class MIBreakAfter extends MICommand<MIInfo>
{
    public MIBreakAfter(IBreakpointsTargetDMContext ctx, int breakpoint, int ignoreCount) {
        super(ctx, "-break-after"); //$NON-NLS-1$
		setParameters(new String[] { Integer.toString(breakpoint), Integer.toString(ignoreCount) });
    }
}
