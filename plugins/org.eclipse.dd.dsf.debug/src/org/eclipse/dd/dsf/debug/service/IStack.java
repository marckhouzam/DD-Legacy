/*******************************************************************************
 * Copyright (c) 2006 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.service;

import org.eclipse.cdt.core.IAddress;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.datamodel.IDMData;
import org.eclipse.dd.dsf.datamodel.IDMService;

/**
 * Stack service provides access to stack information for a 
 * given execution context.
 */
public interface IStack extends IDMService {

    /**
     * Context for a specific stack frame.  Besides allowing access to stack
     * frame data, this context is used by other services that require a stack
     * frame for evaluation.  
     */
    public interface IFrameDMContext extends IDMContext<IFrameDMData> {}

    /**
     * Stack frame information. 
     */
    public interface IFrameDMData extends IDMData {
        int getLevel();
        IAddress getAddress();
        String getFile();
        String getFunction();
        int getLine();
        int getColumn();
    }
    
    /**
     * Variable context.  This context only provides access to limited 
     * expression information.  For displaying complete information, 
     * Expressions service should be used.
     */
    public interface IVariableDMContext extends IDMContext<IVariableDMData> {}

    /** 
     * Stack frame variable information.
     */
    public interface IVariableDMData extends IDMData {
        String getName();
        String getValue();
    }

    /**
     * Returns whether the stack frames can be retrieved for given thread.
     */
    boolean isStackAvailable(IDMContext<?> execContext);
    
    /**
     * Retrieves list of stack frames for the given execution context.  Request
     * will fail if the stack frame data is not available.
     */
    void getFrames(IDMContext<?> execContext, DataRequestMonitor<IFrameDMContext[]> rm);
    
    /**
     * Retrieves the top stack frame for the given execution context.  
     * Retrieving just the top frame DMC and corresponding data can be much 
     * more efficient than just retrieving the whole stack, before the data
     * is often included in the stopped event.  Also for some UI functionality, 
     * such as setpping, only top stack frame is often needed. 
     * @param execContext
     * @param rm
     */
    void getTopFrame(IDMContext<?> execContext, DataRequestMonitor<IFrameDMContext> rm);
    
    /**
     * Retrieves variables which were arguments to the stack frame's function.
     */
    void getArguments(IDMContext<?> frameCtx, DataRequestMonitor<IVariableDMContext[]> rm);
    
    /**
     * Retrieves variables local to the stack frame.
     */
    void getLocals(IDMContext<?> frameCtx, DataRequestMonitor<IVariableDMContext[]> rm);
}
