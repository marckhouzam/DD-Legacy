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
package org.eclipse.dd.dsf.datamodel;

import org.eclipse.dd.dsf.concurrent.GetDataDone;
import org.eclipse.dd.dsf.service.IDsfService;

/**
 * Interface for DSF services that provide model data to clients.  
 * <p>
 * For completeness this service interface derives from <code>IDMData</data> 
 * and has a method which allows clients to retrieve the DM Context that 
 * represents the service data. 
 */
public interface IDMService extends IDsfService, IDMData {
    /**
     * Returns the context representing the service in the data model.  It is 
     * usually used in events to indicate that lists of contexts in this 
     * service are changed. 
     */
    IDMContext<?> getServiceContext();
    
    /**
     * Retrieves model data object for given context.  This method makes it
     * un-necessary for every model service to declare a separate method 
     * for retrieving model data of specific type.
     */
    <V extends IDMData> void getModelData(IDMContext<V> dmc, GetDataDone<V> done);
}
