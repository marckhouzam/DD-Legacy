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
 * This interface provides access to controlling and monitoring the execution 
 * state of a process being debugged.  This interface does not actually 
 * provide methods for creating or destroying execution contexts, it doesn't
 * even have methods for getting labels.  That's because it is expected that
 * higher level services, ones that deal with processes, kernels, or target 
 * features will provide that functionality. 
 */
public interface IMIRunControl extends IRunControl
{
	public IMIExecutionDMContext createMIExecutionContext(IContainerDMContext container, int threadId);
}