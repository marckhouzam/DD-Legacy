/*******************************************************************************
 * Copyright (c) 2008 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.launch;

import org.eclipse.dd.dsf.datamodel.AbstractDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;

/**
 * Event to increase the stack frame limit for an execution context.
 * 
 * @since 1.1
 */
public class ExpandStackEvent extends AbstractDMEvent<IExecutionDMContext> {
    
    public ExpandStackEvent(IExecutionDMContext execCtx) {
        super(execCtx);
    }

}
