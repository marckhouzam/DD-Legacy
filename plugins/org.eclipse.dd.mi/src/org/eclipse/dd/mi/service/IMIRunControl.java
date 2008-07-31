/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson			  - Modified for additional functionality
 *******************************************************************************/
package org.eclipse.dd.mi.service;

import org.eclipse.dd.dsf.debug.service.IRunControl;

/**
 * This interface provides a method for creating execution contexts.
 */
public interface IMIRunControl extends IRunControl
{
	public IMIExecutionDMContext createMIExecutionContext(IContainerDMContext container, int threadId);
}
