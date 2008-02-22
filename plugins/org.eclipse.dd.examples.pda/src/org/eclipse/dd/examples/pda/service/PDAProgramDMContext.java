/*******************************************************************************
 * Copyright (c) 2007 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.examples.pda.service;

import org.eclipse.core.runtime.PlatformObject;
import org.eclipse.dd.dsf.datamodel.AbstractDMContext;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.debug.service.IBreakpoints.IBreakpointsTargetDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.dsf.service.DsfSession;

/**
 * Top-level Data Model context for the PDA debugger representing the PDA 
 * program.  
 * <p>
 * The PDA debugger is a single-threaded application.  Therefore this
 * top level context implements IExecutionDMContext directly, hence this
 * context can be used to call the IRunControl service to perform run
 * control opreations.
 * </p>
 * <p>
 * Also, the PDA debugger allows setting breakpoints in scope of the 
 * whole program only, so this context can be used with the breakpoints 
 * service to install/remove breakpoints.
 * </p>
 * <p>
 * Note: There should only be one instance of PDACommandControlDMContext created
 * by each PDA command control, so its equals method defaults to using 
 * instance comparison. 
 * </p>
 */
public class PDAProgramDMContext extends PlatformObject
    implements IExecutionDMContext, IBreakpointsTargetDMContext 
{
    final static IDMContext[] EMPTY_PARENTS_ARRAY = new IDMContext[0];
    
    final private String fSessionId;
    final private String fProgram;
    
    public PDAProgramDMContext(String sessionId, String program) {
        fSessionId = sessionId;
        fProgram = program;
    }

    public String getSessionId() {
        return fSessionId;
    }
    
    public String getProgram() {
        return fProgram;
    }
    
    public IDMContext[] getParents() {
        return EMPTY_PARENTS_ARRAY;
    }
    
    @Override
    public String toString() {
        return "PDA(" + getSessionId() + ")";
    }

    /**
     * @see AbstractDMContext#getAdapter(Class)
     */
    @Override
    @SuppressWarnings("unchecked")
    public Object getAdapter(Class adapterType) {
        Object retVal = null;
        DsfSession session = DsfSession.getSession(fSessionId);
        if (session != null) {
            retVal = session.getModelAdapter(adapterType);
        }
        if (retVal == null) {
            retVal = super.getAdapter(adapterType);
        }
        return retVal;
    }

}
