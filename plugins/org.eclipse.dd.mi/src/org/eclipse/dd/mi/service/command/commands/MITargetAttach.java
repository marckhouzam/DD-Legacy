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

import org.eclipse.dd.dsf.debug.service.command.ICommandControlService.ICommandControlDMContext;
import org.eclipse.dd.mi.service.command.output.MIInfo;

/**
 * -target-attach < --pid PID | THREAD_GROUP_ID >
 * 
 * This command attaches to the process specified by the PID
 * or THREAD_GROUP_ID
 * @since 1.1
 */
public class MITargetAttach extends MICommand<MIInfo> {

	public MITargetAttach(ICommandControlDMContext ctx, String groupId) {
		super(ctx, "-target-attach", new String[] {groupId}); //$NON-NLS-1$
	}
}
