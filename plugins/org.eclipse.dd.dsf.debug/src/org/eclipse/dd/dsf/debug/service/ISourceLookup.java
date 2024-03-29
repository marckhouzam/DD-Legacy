/*******************************************************************************
 * Copyright (c) 2006, 2007 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.service;

import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.datamodel.IDMEvent;
import org.eclipse.dd.dsf.service.IDsfService;

/**
 * Service for mapping debugger paths to host paths.  This service is needed
 * primarily by other services that need to access source-path mappings, such
 * as the breakpoints service.  For UI components, the platform source lookup
 * interfaces could be sufficient.
 */
public interface ISourceLookup extends IDsfService {

    public interface ISourceLookupDMContext extends IDMContext {}
        
    public interface ISourceLookupChangedDMEvent extends IDMEvent<ISourceLookupDMContext> {}
    
    /**
     * Retrieves the host source object for given debugger path string.
     */
    void getSource(ISourceLookupDMContext ctx, String debuggerPath, DataRequestMonitor<Object> rm);
    
    /**
     * Retrieves the debugger path string for given host source object.
     */
    void getDebuggerPath(ISourceLookupDMContext ctx, Object source, DataRequestMonitor<String> rm);
}
