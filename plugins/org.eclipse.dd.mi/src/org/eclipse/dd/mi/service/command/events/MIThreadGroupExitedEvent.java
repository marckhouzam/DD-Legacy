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

package org.eclipse.dd.mi.service.command.events;

import org.eclipse.dd.dsf.concurrent.Immutable;
import org.eclipse.dd.dsf.debug.service.IProcesses.IProcessDMContext;


/**
 * This can only be detected by gdb/mi after GDB 6.8.
 *
 */
@Immutable
public class MIThreadGroupExitedEvent extends MIEvent<IProcessDMContext> {

    final private String fGroupId;

    public MIThreadGroupExitedEvent(IProcessDMContext ctx, int token, String groupId) {
        super(ctx, token, null);
        fGroupId = groupId;
    }
    
    public String getGroupId() { return fGroupId; }
}
