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
 *     Ericsson				- Modified for handling of execution contexts
 *******************************************************************************/

package org.eclipse.dd.mi.service.command.commands;

import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.mi.service.command.output.MIInfo;

/**
 * 
 *      -exec-run
 *
 *   Asynchronous command.  Starts execution of the inferior from the
 * beginning.  The inferior executes until either a breakpoint is
 * encountered or the program exits.
 * 
 */
public class MIExecRun extends MICommand<MIInfo> 
{
    public MIExecRun(IExecutionDMContext dmc) {
        super(dmc, "-exec-run"); //$NON-NLS-1$
    }
    
    public MIExecRun(IExecutionDMContext dmc, String[] args) {
        super(dmc, "-exec-run", args); //$NON-NLS-1$
    }
}
