/*******************************************************************************
 * Copyright (c) 2008 Ericsson and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Ericsson - Initial API and implementation
 *******************************************************************************/

package org.eclipse.dd.mi.service.command.commands;

import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerDMContext;
import org.eclipse.dd.mi.service.command.output.MIOutput;
import org.eclipse.dd.mi.service.command.output.MIThreadInfoInfo;

/**
 * 
 * -thread-info [ thread-id ]
 *
 * Reports information about either a specific thread, if [thread-id] is present,
 * or about all threads. When printing information about all threads, also reports
 * the current thread.
 * 
 */
public class MIThreadInfo extends MICommand<MIThreadInfoInfo> {
	
	public MIThreadInfo(IContainerDMContext dmc) {
		super(dmc, "-thread-info"); //$NON-NLS-1$
	}

	public MIThreadInfo(IContainerDMContext dmc, int threadId) {
		super(dmc, "-thread-info", new String[]{ Integer.toString(threadId) }); //$NON-NLS-1$
	}

    @Override
    public MIThreadInfoInfo getResult(MIOutput out) {
        return new MIThreadInfoInfo(out);
    }
}
