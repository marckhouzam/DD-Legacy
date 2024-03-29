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

import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.mi.service.command.output.MIInfo;

/**
 * 
 *      -exec-next-instruction [count]
 *
 *   Asynchronous command.  Executes one machine instruction.  If the
 * instruction is a function call continues until the function returns.  If
 * the program stops at an instruction in the middle of a source line, the
 * address will be printed as well.
 * 
 */
public class MIExecNextInstruction extends MICommand<MIInfo> 
{
    public MIExecNextInstruction(IExecutionDMContext dmc) {
        this(dmc, 1);
    }

    public MIExecNextInstruction(IExecutionDMContext dmc, int count) {
        super(dmc, "-exec-next-instruction", new String[] { Integer.toString(count) }); //$NON-NLS-1$
    }
}
