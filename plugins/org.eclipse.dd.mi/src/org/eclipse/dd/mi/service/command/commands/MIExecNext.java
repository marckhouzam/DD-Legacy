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
import org.eclipse.dd.mi.service.IMIExecutionDMContext;
import org.eclipse.dd.mi.service.command.output.MIInfo;

/**
 * 
 *     -exec-next [--thread <tid>] [count]
 *
 *  Asynchronous command.  Resumes execution of the inferior program,
 *  stopping when the beginning of the next source line is reached.
 * 
 */
public class MIExecNext extends MICommand<MIInfo> 
{
	public MIExecNext(IExecutionDMContext dmc) {
	    this(dmc, 1);
	}

	public MIExecNext(IExecutionDMContext dmc, int count) {
	    super(dmc, "-exec-next", new String[] { Integer.toString(count) }); //$NON-NLS-1$
	}

	public MIExecNext(IMIExecutionDMContext dmc, boolean setThread) {
	    this(dmc, setThread, 1);
	}

	public MIExecNext(IMIExecutionDMContext dmc, boolean setThread, int count) {
	    super(dmc, "-exec-next");	//$NON-NLS-1$
	    if (setThread) {
	    	setParameters(new String[] { "--thread", Integer.toString(dmc.getThreadId()), Integer.toString(count) }); //$NON-NLS-1$
	    } else {
	    	setParameters(new String[] { Integer.toString(count) });
	    }
	}
}