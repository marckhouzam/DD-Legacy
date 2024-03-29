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

import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.mi.service.command.output.MIInfo;

/**
 * This command connects to a remote target.
 */
public class MITargetSelect extends MICommand<MIInfo> {

	@Deprecated
	public MITargetSelect(IDMContext ctx, String host, String port) {
		this(ctx, host, port, true);
	}
	
	@Deprecated	
	public MITargetSelect(IDMContext ctx, String serialDevice) {
		this(ctx, serialDevice, true);
	}

	/**
	 * @since 1.1
	 */
	public MITargetSelect(IDMContext ctx, String host, String port, boolean extended) {
		super(ctx, "-target-select", new String[] { extended ? "extended-remote" : "remote", host + ":" + port}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
	}
	
	/**
	 * @since 1.1
	 */
	public MITargetSelect(IDMContext ctx, String serialDevice, boolean extended) {
		super(ctx, "-target-select", new String[] { extended ? "extended-remote" : "remote", serialDevice}); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

}
